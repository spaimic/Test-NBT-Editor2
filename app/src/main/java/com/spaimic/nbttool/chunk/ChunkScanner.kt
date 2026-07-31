package com.spaimic.nbttool.chunk

import com.spaimic.nbttool.shizuku.LevelDbBridge
import kotlin.math.abs

/** One rendered map column: the color to show, and the real block Y of its surface. */
data class ColumnInfo(val color: Int, val surfaceY: Int)

/** Why each chunk ended up the color it did — surfaced to the user so a "why is
 * everything gray" report can be diagnosed from real numbers instead of guessing. */
data class ScanStats(
    val totalLoadedChunks: Int,
    val outliersDropped: Int,
    val noSubchunkFound: Int,
    val subchunkParseFailed: Int,
    val parsedOk: Int,
    val unknownBlockNames: Set<String>
)

data class ScanResult(val grid: Map<Pair<Int, Int>, Array<Array<ColumnInfo>>>, val stats: ScanStats)

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
     *
     * Chunk-key detection is a heuristic (byte length + a plausible tag byte),
     * not a guaranteed-correct parse — an unrelated key can occasionally match
     * it and produce a chunk position wildly far from the real explored area.
     * Those outliers are filtered out (by distance from the median position)
     * before rendering, so one bad match doesn't blow out the whole map's
     * bounding box / auto-center point.
     */
    fun scanOverworld(bridge: LevelDbBridge): ScanResult {
        val bestSubchunkByChunk = HashMap<Pair<Int, Int>, Pair<Int, ByteArray>>()
        val loadedChunks = HashSet<Pair<Int, Int>>()

        bridge.forEachRawEntry { key, value ->
            val info = ChunkKeys.parse(key)
            if (info != null && info.dimension == 0) {
                val pos = info.chunkX to info.chunkZ
                loadedChunks.add(pos)
                if (info.tag == ChunkKeys.TAG_SUBCHUNK_PREFIX && info.subChunkY != null) {
                    val current = bestSubchunkByChunk[pos]
                    if (current == null || info.subChunkY > current.first) {
                        bestSubchunkByChunk[pos] = info.subChunkY to value
                    }
                }
            }
        }

        val filtered = dropOutliers(loadedChunks)
        val outliersDropped = loadedChunks.size - filtered.size

        var noSubchunkFound = 0
        var subchunkParseFailed = 0
        var parsedOk = 0
        val unknownBlockNames = HashSet<String>()

        val result = HashMap<Pair<Int, Int>, Array<Array<ColumnInfo>>>()
        for (pos in filtered) {
            val best = bestSubchunkByChunk[pos]
            val grid = Array(16) { Array(16) { ColumnInfo(BlockColors.fallbackUnparsed, 0) } }
            if (best == null) {
                noSubchunkFound++
            } else {
                val (topY, bytes) = best
                val blocks = SubChunkParser.parse(bytes)
                if (blocks == null) {
                    subchunkParseFailed++
                } else {
                    parsedOk++
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
                            if (foundName != "minecraft:air" && !BlockColors.isKnown(foundName)) {
                                unknownBlockNames.add(foundName)
                            }
                            grid[x][z] = ColumnInfo(BlockColors.colorFor(foundName), worldY)
                        }
                    }
                }
            }
            result[pos] = grid
        }

        val stats = ScanStats(loadedChunks.size, outliersDropped, noSubchunkFound, subchunkParseFailed, parsedOk, unknownBlockNames)
        return ScanResult(result, stats)
    }

    /** Drops chunk positions far from the median cluster — almost certainly a
     * false-positive key match rather than a real, distant explored chunk. */
    private fun dropOutliers(positions: Set<Pair<Int, Int>>): Set<Pair<Int, Int>> {
        if (positions.size <= 2) return positions
        val xs = positions.map { it.first }.sorted()
        val zs = positions.map { it.second }.sorted()
        val medianX = xs[xs.size / 2]
        val medianZ = zs[zs.size / 2]
        val maxChunkDistance = 2000 // ~32,000 blocks from the median — generous, but bounds the damage
        return positions.filter { (x, z) -> abs(x - medianX) <= maxChunkDistance && abs(z - medianZ) <= maxChunkDistance }.toSet()
    }
}
