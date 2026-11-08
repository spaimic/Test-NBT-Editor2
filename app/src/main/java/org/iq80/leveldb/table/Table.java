/*
 * Copyright (C) 2011 the original author or authors.
 * See the notice.md file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.iq80.leveldb.table;

import com.google.common.base.Throwables;
import org.iq80.leveldb.impl.SeekingIterable;
import org.iq80.leveldb.util.Closeables;
import org.iq80.leveldb.util.Slice;
import org.iq80.leveldb.util.TableIterator;
import org.iq80.leveldb.util.VariableLengthQuantity;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Comparator;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

public abstract class Table
        implements SeekingIterable<Slice, Slice>
{
    // Decompressed-block cache shared by all tables (see BlockCache).
    private static final BlockCache BLOCK_CACHE = new BlockCache(
            Math.max(24L << 20, Math.min(64L << 20, Runtime.getRuntime().maxMemory() / 8)));
    private static final ConcurrentHashMap<String, Long> TABLE_IDS = new ConcurrentHashMap<String, Long>();
    private static final AtomicLong NEXT_TABLE_ID = new AtomicLong();

    /** "hits misses sizeBytes maxBytes" for the block cache (diagnostics). */
    public static long[] blockCacheStats()
    {
        return new long[] {BLOCK_CACHE.hits.get(), BLOCK_CACHE.misses.get(), BLOCK_CACHE.sizeBytes(), BLOCK_CACHE.maxBytes()};
    }

    public static void resetBlockCacheStats()
    {
        BLOCK_CACHE.resetStats();
    }

    /** Drops every cached block (called whenever a database is opened). */
    public static void clearBlockCache()
    {
        BLOCK_CACHE.clear();
    }

    private final long tableId;
    protected final String name;
    protected final FileChannel fileChannel;
    protected final Comparator<Slice> comparator;
    protected final boolean verifyChecksums;
    protected final Block indexBlock;
    protected final BlockHandle metaindexBlockHandle;

    public Table(String name, FileChannel fileChannel, Comparator<Slice> comparator, boolean verifyChecksums)
            throws IOException
    {
        requireNonNull(name, "name is null");
        requireNonNull(fileChannel, "fileChannel is null");
        long size = fileChannel.size();
        checkArgument(size >= Footer.ENCODED_LENGTH, "File is corrupt: size must be at least %s bytes", Footer.ENCODED_LENGTH);
        requireNonNull(comparator, "comparator is null");

        this.name = name;
        // The id covers path + size + modification time, so a file that was replaced (for
        // example by restoring a backup) is never confused with the old file of the same name.
        String idKey = name + '|' + size + '|' + new java.io.File(name).lastModified();
        Long id = TABLE_IDS.get(idKey);
        if (id == null) {
            Long fresh = NEXT_TABLE_ID.incrementAndGet();
            Long prev = TABLE_IDS.putIfAbsent(idKey, fresh);
            id = prev != null ? prev : fresh;
        }
        this.tableId = id;
        this.fileChannel = fileChannel;
        this.verifyChecksums = verifyChecksums;
        this.comparator = comparator;

        Footer footer = init();
        indexBlock = readBlock(footer.getIndexBlockHandle());
        metaindexBlockHandle = footer.getMetaindexBlockHandle();
    }

    protected abstract Footer init()
            throws IOException;

    @Override
    public TableIterator iterator()
    {
        return new TableIterator(this, indexBlock.iterator());
    }

    public Block openBlock(Slice blockEntry)
    {
        BlockHandle blockHandle = BlockHandle.readBlockHandle(blockEntry.input());
        long cacheKey = (tableId << 40) | (blockHandle.getOffset() & 0xFFFFFFFFFFL);
        Block dataBlock = BLOCK_CACHE.get(cacheKey);
        if (dataBlock != null) {
            return dataBlock;
        }
        try {
            dataBlock = readBlock(blockHandle);
        }
        catch (IOException e) {
            throw Throwables.propagate(e);
        }
        BLOCK_CACHE.put(cacheKey, dataBlock);
        return dataBlock;
    }

    protected static ByteBuffer uncompressedScratch = ByteBuffer.allocateDirect(4 * 1024 * 1024);

    protected abstract Block readBlock(BlockHandle blockHandle)
            throws IOException;

    protected int uncompressedLength(ByteBuffer data)
            throws IOException
    {
        int length = VariableLengthQuantity.readVariableLengthInt(data.duplicate());
        return length;
    }

    /**
     * Given a key, return an approximate byte offset in the file where
     * the data for that key begins (or would begin if the key were
     * present in the file).  The returned value is in terms of file
     * bytes, and so includes effects like compression of the underlying data.
     * For example, the approximate offset of the last key in the table will
     * be close to the file length.
     */
    public long getApproximateOffsetOf(Slice key)
    {
        BlockIterator iterator = indexBlock.iterator();
        iterator.seek(key);
        if (iterator.hasNext()) {
            BlockHandle blockHandle = BlockHandle.readBlockHandle(iterator.next().getValue().input());
            return blockHandle.getOffset();
        }

        // key is past the last key in the file.  Approximate the offset
        // by returning the offset of the metaindex block (which is
        // right near the end of the file).
        return metaindexBlockHandle.getOffset();
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("Table");
        sb.append("{name='").append(name).append('\'');
        sb.append(", comparator=").append(comparator);
        sb.append(", verifyChecksums=").append(verifyChecksums);
        sb.append('}');
        return sb.toString();
    }

    public Callable<?> closer()
    {
        return new Closer(fileChannel);
    }

    private static class Closer
            implements Callable<Void>
    {
        private final Closeable closeable;

        public Closer(Closeable closeable)
        {
            this.closeable = closeable;
        }

        @Override
        public Void call()
        {
            Closeables.closeQuietly(closeable);
            return null;
        }
    }
}
