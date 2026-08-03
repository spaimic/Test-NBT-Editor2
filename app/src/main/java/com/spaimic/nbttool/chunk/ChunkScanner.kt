package com.spaimic.nbttool.chunk

import com.spaimic.nbttool.nbt.NbtIO
import com.spaimic.nbttool.nbt.NbtTag
import com.spaimic.nbttool.shizuku.LevelDbBridge
import kotlin.math.abs

/** One rendered map column: the color to show, and the real block Y of its surface. */
data class ColumnInfo(val color: Int, val surfaceY: Int)

/** One entity's position + identifier, for drawing a marker on the map. */
data class EntityMarker(val worldX: Int, val worldY: Int, val worldZ: Int, val identifier: String)

/** One block entity's position + id, for drawing a marker on the map. */
data class BlockEntityMarker(val worldX: Int, val worldY: Int, val worldZ: Int, val id: String)

/** Why each chunk ended up the color it did — surfaced to the user so a "why is
 * everything gray" report can be diagnosed from real numbers instead of guessing. */
data class ScanStats(
    val totalLoadedChunks: Int,
    val outliersDropped: Int,
    val noSubchunkFound: Int,
    val subchunkParseFailed: Int,
    val parsedOk: Int,
    val unknownBlockNames: Set<String>,
    /** failure reason (from SubChunkParser) -> how many subchunks hit it, most common first */
    val failureReasonCounts: List<Pair<String, Int>>,
    /** failure reason -> one example hex dump of an actual failing subchunk's bytes */
    val failureReasonExamples: Map<String, String>
)

data class ScanResult(
    val grid: Map<Pair<Int, Int>, Array<Array<ColumnInfo>>>,
    val stats: ScanStats,
    val entityMarkers: List<EntityMarker>,
    val blockEntityMarkers: List<BlockEntityMarker>
)

object ChunkScanner {

    fun scanOverworld(bridge: LevelDbBridge): ScanResult {
        val bestSubchunkByChunk = HashMap<Pair<Int, Int>, Pair<Int, ByteArray>>()
        val loadedChunks = HashSet<Pair<Int, Int>>()
        val blockEntityValueByChunk = HashMap<Pair<Int, Int>, ByteArray>()
        val digpValueByChunk = HashMap<Pair<Int, Int>, ByteArray>()
        val actorValueByIdHex = HashMap<String, ByteArray>()

        // Single pass over the whole DB — collects terrain, block-entity, and entity
        // data together rather than scanning multiple times.
        bridge.forEachRawEntry { key, value ->
            val info = ChunkKeys.parse(key)
            if (info != null && info.dimension == 0) {
                val pos = info.chunkX to info.chunkZ
                loadedChunks.add(pos)
                when (info.tag) {
                    ChunkKeys.TAG_SUBCHUNK_PREFIX -> if (info.subChunkY != null) {
                        val current = bestSubchunkByChunk[pos]
                        if (current == null || info.subChunkY > current.first) {
                            bestSubchunkByChunk[pos] = info.subChunkY to value
                        }
                    }
                    ChunkKeys.TAG_BLOCK_ENTITY -> blockEntityValueByChunk[pos] = value
                }
            } else if (EntityKeys.isOverworldDigpKey(key)) {
                digpValueByChunk[EntityKeys.digpChunkXZ(key)] = value
            } else if (EntityKeys.isActorPrefixKey(key)) {
                actorValueByIdHex[EntityKeys.actorIdHex(key)] = value
            }
        }

        val filtered = dropOutliers(loadedChunks)
        val outliersDropped = loadedChunks.size - filtered.size

        var noSubchunkFound = 0
        var parsedOk = 0
        val unknownBlockNames = HashSet<String>()
        val failureReasonCounts = HashMap<String, Int>()
        val failureReasonExamples = HashMap<String, String>()

        val result = HashMap<Pair<Int, Int>, Array<Array<ColumnInfo>>>()
        val entityMarkers = ArrayList<EntityMarker>()
        val blockEntityMarkers = ArrayList<BlockEntityMarker>()

        for (pos in filtered) {
            val best = bestSubchunkByChunk[pos]
            val grid = Array(16) { Array(16) { ColumnInfo(BlockColors.fallbackUnparsed, 0) } }
            if (best == null) {
                noSubchunkFound++
            } else {
                val (topY, bytes) = best
                when (val parseResult = SubChunkParser.parse(bytes)) {
                    is SubChunkParser.ParseResult.Failure -> {
                        failureReasonCounts[parseResult.reason] = (failureReasonCounts[parseResult.reason] ?: 0) + 1
                        failureReasonExamples.getOrPut(parseResult.reason) { parseResult.hexSample }
                    }
                    is SubChunkParser.ParseResult.Success -> {
                        parsedOk++
                        val blocks = parseResult.blocks
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
            }
            result[pos] = grid

            // Block entities for this chunk
            blockEntityValueByChunk[pos]?.let { bytes ->
                try {
                    for (compound in NbtIO.readConcatenatedCompounds(bytes)) {
                        val id = (compound.entries["id"] as? NbtTag.Str)?.value ?: continue
                        val x = (compound.entries["x"] as? NbtTag.Int)?.value ?: continue
                        val y = (compound.entries["y"] as? NbtTag.Int)?.value ?: continue
                        val z = (compound.entries["z"] as? NbtTag.Int)?.value ?: continue
                        blockEntityMarkers.add(BlockEntityMarker(x, y, z, id))
                    }
                } catch (e: Exception) { /* skip this chunk's block entities on any parse issue */ }
            }

            // Entities for this chunk
            digpValueByChunk[pos]?.let { digpValue ->
                for (actorId in EntityKeys.splitActorIds(digpValue)) {
                    val idHex = actorId.joinToString("") { "%02x".format(it) }
                    val bytes = actorValueByIdHex[idHex] ?: continue
                    try {
                        val compound = NbtIO.readRootCompoundNoHeader(bytes)
                        val identifier = (compound.entries["identifier"] as? NbtTag.Str)?.value ?: continue
                        val posTag = compound.entries["Pos"] as? NbtTag.ListTag ?: continue
                        val px = (posTag.items.getOrNull(0) as? NbtTag.Float)?.value ?: continue
                        val py = (posTag.items.getOrNull(1) as? NbtTag.Float)?.value ?: continue
                        val pz = (posTag.items.getOrNull(2) as? NbtTag.Float)?.value ?: continue
                        entityMarkers.add(EntityMarker(px.toInt(), py.toInt(), pz.toInt(), identifier))
                    } catch (e: Exception) { /* skip this entity on any parse issue */ }
                }
            }
        }

        val subchunkParseFailed = failureReasonCounts.values.sum()
        val topReasons = failureReasonCounts.entries.sortedByDescending { it.value }.take(8).map { it.key to it.value }
        val stats = ScanStats(loadedChunks.size, outliersDropped, noSubchunkFound, subchunkParseFailed, parsedOk, unknownBlockNames, topReasons, failureReasonExamples)
        return ScanResult(result, stats, entityMarkers, blockEntityMarkers)
    }

    /** Drops chunk positions far from the median cluster — almost certainly a
     * false-positive key match rather than a real, distant explored chunk. */
    private fun dropOutliers(positions: Set<Pair<Int, Int>>): Set<Pair<Int, Int>> {
        if (positions.size <= 2) return positions
        val xs = positions.map { it.first }.sorted()
        val zs = positions.map { it.second }.sorted()
        val medianX = xs[xs.size / 2]
        val medianZ = zs[zs.size / 2]
        val maxChunkDistance = 2000
        return positions.filter { (x, z) -> abs(x - medianX) <= maxChunkDistance && abs(z - medianZ) <= maxChunkDistance }.toSet()
    }
}
