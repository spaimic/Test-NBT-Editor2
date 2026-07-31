package com.spaimic.nbttool.chunk

import com.spaimic.nbttool.shizuku.LevelDbBridge

/** One rendered map column: the color to show, and the real block Y of its surface. */
data class ColumnInfo(val color: Int, val surfaceY: Int)

object ChunkScanner {

    /**
     * Scans every key in the world's DB once, groups subchunk entries by chunk
     * position, and for each loaded chunk decodes only its highest present
     * subchunk (the usual location of the surface) into a 16x16 grid of column
     * colors. A chunk that exists but whose subchunk format we can't parse
     * (unsupported/legacy) still counts as "loaded" — just shown with a neutral
     * color instead of crashing the whole map.
     *
     * Only decodes the single topmost subchunk per column — a floating
     * structure sitting exactly on a subchunk boundary with nothing below it
     * in that same subchunk could show as the wrong block. Full multi-subchunk
     * scanning would be more accurate but far slower on a phone for a large
     * explored world.
     */
    fun scanOverworld(bridge: LevelDbBridge): Map<Pair<Int, Int>, Array<Array<ColumnInfo>>> {
        val subchunksByChunk = HashMap<Pair<Int, Int>, HashMap<Int, ByteArray>>()
        val loadedChunks = HashSet<Pair<Int, Int>>()

        bridge.forEachRawEntry { key, value ->
            val info = ChunkKeys.parse(key)
            if (info != null && info.dimension == 0) {
                val pos = info.chunkX to info.chunkZ
                loadedChunks.add(pos)
                if (info.tag == ChunkKeys.TAG_SUBCHUNK_PREFIX && info.subChunkY != null) {
                    subchunksByChunk.getOrPut(pos) { HashMap() }[info.subChunkY] = value
                }
            }
        }

        val result = HashMap<Pair<Int, Int>, Array<Array<ColumnInfo>>>()
        for (pos in loadedChunks) {
            val subs = subchunksByChunk[pos]
            val grid = Array(16) { Array(16) { ColumnInfo(BlockColors.fallbackUnparsed, 0) } }
            if (subs != null && subs.isNotEmpty()) {
                val topY = subs.keys.max()
                val blocks = SubChunkParser.parse(subs.getValue(topY))
                if (blocks != null) {
                    for (x in 0 until 16) {
                        for (z in 0 until 16) {
                            var foundY = -1
                            var foundName = "minecraft:air"
                            for (y in 15 downTo 0) {
                                val name = blocks[x][z][y]
                                if (name != "minecraft:air") {
                                    foundY = y; foundName = name; break
                                }
                            }
                            val worldY = topY * 16 + if (foundY >= 0) foundY else 0
                            grid[x][z] = ColumnInfo(BlockColors.colorFor(foundName), worldY)
                        }
                    }
                }
            }
            result[pos] = grid
        }
        return result
    }
}
