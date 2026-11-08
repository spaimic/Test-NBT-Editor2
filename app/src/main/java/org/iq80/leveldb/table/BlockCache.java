package org.iq80.leveldb.table;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Small LRU cache of decompressed data blocks, shared by every table.
 *
 * The stock iq80 LevelDB has no block cache: every lookup re-reads the block from disk and
 * re-inflates it. Bedrock stores a chunk's records next to each other, and neighbouring
 * chunks share blocks too, so reading a map view touches the same blocks over and over.
 * SSTables are immutable, so a cached block never goes stale (a new file gets a new id).
 * The cache is split into segments to keep lock contention low with many worker threads.
 */
final class BlockCache
{
    private static final int SEGMENTS = 16;

    private final Segment[] segments = new Segment[SEGMENTS];
    private final long maxBytes;
    final AtomicLong hits = new AtomicLong();
    final AtomicLong misses = new AtomicLong();

    BlockCache(long maxBytes)
    {
        this.maxBytes = maxBytes;
        for (int i = 0; i < SEGMENTS; i++) {
            segments[i] = new Segment(maxBytes / SEGMENTS);
        }
    }

    private Segment segmentFor(long key)
    {
        int h = (int) (key ^ (key >>> 32));
        h ^= (h >>> 16);
        return segments[h & (SEGMENTS - 1)];
    }

    Block get(long key)
    {
        Block block = segmentFor(key).get(key);
        if (block != null) {
            hits.incrementAndGet();
        }
        else {
            misses.incrementAndGet();
        }
        return block;
    }

    void put(long key, Block block)
    {
        segmentFor(key).put(key, block);
    }

    long sizeBytes()
    {
        long total = 0;
        for (Segment s : segments) {
            total += s.size();
        }
        return total;
    }

    long maxBytes()
    {
        return maxBytes;
    }

    void clear()
    {
        for (Segment s : segments) {
            s.clear();
        }
    }

    void resetStats()
    {
        hits.set(0);
        misses.set(0);
    }

    private static final class Segment
    {
        private final long max;
        private long size;
        private final LinkedHashMap<Long, Block> map = new LinkedHashMap<Long, Block>(256, 0.75f, true);

        Segment(long max)
        {
            this.max = max;
        }

        synchronized Block get(long key)
        {
            return map.get(key);
        }

        synchronized long size()
        {
            return size;
        }

        synchronized void clear()
        {
            map.clear();
            size = 0;
        }

        synchronized void put(long key, Block block)
        {
            Block old = map.put(key, block);
            if (old != null) {
                size -= old.size();
            }
            size += block.size();
            Iterator<Map.Entry<Long, Block>> it = map.entrySet().iterator();
            while (size > max && map.size() > 1 && it.hasNext()) {
                Map.Entry<Long, Block> eldest = it.next();
                if (eldest.getValue() == block) {
                    continue;
                }
                size -= eldest.getValue().size();
                it.remove();
            }
        }
    }
}
