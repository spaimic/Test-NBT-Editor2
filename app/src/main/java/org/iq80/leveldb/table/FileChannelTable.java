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

import org.iq80.leveldb.util.Slice;
import org.iq80.leveldb.util.Slices;
import org.iq80.leveldb.util.Snappy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Comparator;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import static org.iq80.leveldb.CompressionType.SNAPPY;
import static org.iq80.leveldb.CompressionType.ZLIB;
import static org.iq80.leveldb.CompressionType.ZLIB_RAW;

public class FileChannelTable
        extends Table
{
    public FileChannelTable(String name, FileChannel fileChannel, Comparator<Slice> comparator, boolean verifyChecksums)
            throws IOException
    {
        super(name, fileChannel, comparator, verifyChecksums);
    }

    @Override
    protected Footer init()
            throws IOException
    {
        long size = fileChannel.size();
        ByteBuffer footerData = read(size - Footer.ENCODED_LENGTH, Footer.ENCODED_LENGTH);
        return Footer.readFooter(Slices.copiedBuffer(footerData));
    }

    @SuppressWarnings({"AssignmentToStaticFieldFromInstanceMethod", "NonPrivateFieldAccessedInSynchronizedContext"})
    @Override
    protected Block readBlock(BlockHandle blockHandle)
            throws IOException
    {
        // read block trailer
        ByteBuffer trailerData = read(blockHandle.getOffset() + blockHandle.getDataSize(), BlockTrailer.ENCODED_LENGTH);
        BlockTrailer blockTrailer = BlockTrailer.readBlockTrailer(Slices.copiedBuffer(trailerData));

// todo re-enable crc check when ported to support direct buffers
//        // only verify check sums if explicitly asked by the user
//        if (verifyChecksums) {
//            // checksum data and the compression type in the trailer
//            PureJavaCrc32C checksum = new PureJavaCrc32C();
//            checksum.update(data.getRawArray(), data.getRawOffset(), blockHandle.getDataSize() + 1);
//            int actualCrc32c = checksum.getMaskedValue();
//
//            checkState(blockTrailer.getCrc32c() == actualCrc32c, "Block corrupted: checksum mismatch");
//        }

        // decompress data

        ByteBuffer uncompressedBuffer = read(blockHandle.getOffset(), blockHandle.getDataSize());
        Slice uncompressedData;
        if (blockTrailer.getCompressionType() == SNAPPY) {
            synchronized (FileChannelTable.class) {
                int uncompressedLength = uncompressedLength(uncompressedBuffer);
                if (uncompressedScratch.capacity() < uncompressedLength) {
                    uncompressedScratch = ByteBuffer.allocateDirect(uncompressedLength);
                }
                uncompressedScratch.clear();

                Snappy.uncompress(uncompressedBuffer, uncompressedScratch);
                uncompressedData = Slices.copiedBuffer(uncompressedScratch);
            }
        }
        else if (blockTrailer.getCompressionType() == ZLIB || blockTrailer.getCompressionType() == ZLIB_RAW) {
            // Bedrock Edition (leveldb-mcpe) compresses blocks with zlib instead of Snappy.
            // ZLIB has the standard 2-byte zlib header; ZLIB_RAW is headerless raw deflate.
            uncompressedData = Slices.copiedBuffer(inflate(uncompressedBuffer, blockTrailer.getCompressionType() == ZLIB_RAW));
        }
        else {
            uncompressedData = Slices.copiedBuffer(uncompressedBuffer);
        }

        return new Block(uncompressedData, comparator);
    }

    private static ByteBuffer inflate(ByteBuffer compressed, boolean nowrap)
            throws IOException
    {
        byte[] input;
        int inputOffset;
        int inputLength;
        if (compressed.hasArray()) {
            input = compressed.array();
            inputOffset = compressed.arrayOffset() + compressed.position();
            inputLength = compressed.remaining();
        }
        else {
            input = new byte[compressed.remaining()];
            compressed.duplicate().get(input);
            inputOffset = 0;
            inputLength = input.length;
        }

        Inflater inflater = new Inflater(nowrap);
        try {
            inflater.setInput(input, inputOffset, inputLength);
            // Bedrock's blocks are typically small (a handful of KB); grow the buffer if needed.
            byte[] output = new byte[Math.max(inputLength * 4, 4096)];
            int totalLength = 0;
            while (!inflater.finished()) {
                if (totalLength == output.length) {
                    byte[] grown = new byte[output.length * 2];
                    System.arraycopy(output, 0, grown, 0, output.length);
                    output = grown;
                }
                int n = inflater.inflate(output, totalLength, output.length - totalLength);
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        throw new IOException("Unexpected end of zlib block data");
                    }
                    break;
                }
                totalLength += n;
            }
            return ByteBuffer.wrap(output, 0, totalLength);
        }
        catch (DataFormatException e) {
            throw new IOException("Invalid zlib block data", e);
        }
        finally {
            inflater.end();
        }
    }

    private ByteBuffer read(long offset, int length)
            throws IOException
    {
        ByteBuffer uncompressedBuffer = ByteBuffer.allocate(length);
        fileChannel.read(uncompressedBuffer, offset);
        if (uncompressedBuffer.hasRemaining()) {
            throw new IOException("Could not read all the data");
        }
        uncompressedBuffer.clear();
        return uncompressedBuffer;
    }
}
