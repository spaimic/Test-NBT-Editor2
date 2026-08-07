package com.spaimic.nbttool.chunk

import com.spaimic.nbttool.nbt.NbtIO
import com.spaimic.nbttool.nbt.NbtTag
import com.spaimic.nbttool.shizuku.LevelDbBridge
import kotlin.math.abs

/** Compact 16x16 map tile. Index = x * 16 + z. */
data class ChunkMapData(val colors: IntArray, val surfaceY: IntArray)

data class EntityMarker(val worldX: Int, val worldY: Int, val worldZ: Int, val identifier: String)
data class BlockEntityMarker(val worldX: Int, val worldY: Int, val worldZ: Int, val id: String)

data class ScanStats(
    val totalLoadedChunks: Int,
    val outliersDropped: Int,
    val noSubchunkFound: Int,
    val subchunkParseFailed: Int,
    val parsedOk: Int,
    val unknownBlockNames: Set<String>,
    val failureReasonCounts: List<Pair<String, Int>>,
    val failureReasonExamples: Map<String, String>
)

data class ScanResult(
    val grid: Map<Pair<Int, Int>, ChunkMapData>,
    val stats: ScanStats,
    val entityMarkers: List<EntityMarker>,
    val blockEntityMarkers: List<BlockEntityMarker>
)

object ChunkScanner {

    fun scanOverworld(bridge: LevelDbBridge): ScanResult {
        // Keep all subchunk values grouped by chunk. We then process each chunk
        // from its highest subchunk downward and stop as soon as all 256 columns
        // have a surface. This fixes the old "only highest subchunk" bug while
        // avoiding a full 3-D String array for every chunk.
        val subchunksByChunk = HashMap<Pair<Int, Int>, MutableList<Pair<Int, ByteArray>>>()
        val loadedChunks = HashSet<Pair<Int, Int>>()
        val blockEntityValueByChunk = HashMap<Pair<Int, Int>, ByteArray>()
        val digpValueByChunk = HashMap<Pair<Int, Int>, ByteArray>()
        val actorValueByIdHex = HashMap<String, ByteArray>()

        bridge.forEachRawEntry { key, value ->
            val info = ChunkKeys.parse(key)
            if (info != null && info.dimension == 0) {
                val pos = info.chunkX to info.chunkZ
                loadedChunks.add(pos)
                when (info.tag) {
                    ChunkKeys.TAG_SUBCHUNK_PREFIX -> if (info.subChunkY != null) {
                        subchunksByChunk.getOrPut(pos) { ArrayList() }.add(info.subChunkY to value)
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
        var parseFailures = 0
        val unknownBlockNames = HashSet<String>()
        val failureReasonCounts = HashMap<String, Int>()
        val failureReasonExamples = HashMap<String, String>()
        val result = HashMap<Pair<Int, Int>, ChunkMapData>(filtered.size)
        val entityMarkers = ArrayList<EntityMarker>()
        val blockEntityMarkers = ArrayList<BlockEntityMarker>()

        for (pos in filtered) {
            val colors = IntArray(256) { BlockColors.fallbackUnparsed }
            val surfaceY = IntArray(256)
            val filled = BooleanArray(256)
            var filledCount = 0
            var chunkParsed = false
            val subchunks = subchunksByChunk[pos]

            if (subchunks.isNullOrEmpty()) {
                noSubchunkFound++
            } else {
                subchunks.sortByDescending { it.first }
                for ((subY, bytes) in subchunks) {
                    if (filledCount == 256) break
                    when (val parseResult = SubChunkParser.fillTopSurface(bytes, subY, colors, surfaceY, filled, unknownBlockNames)) {
                        is SubChunkParser.ParseResult.Failure -> {
                            parseFailures++
                            failureReasonCounts[parseResult.reason] = (failureReasonCounts[parseResult.reason] ?: 0) + 1
                            failureReasonExamples.getOrPut(parseResult.reason) { parseResult.hexSample }
                        }
                        is SubChunkParser.ParseResult.Success -> {
                            if (parseResult.newlyFilled > 0) chunkParsed = true
                            filledCount += parseResult.newlyFilled
                        }
                    }
                }
                if (chunkParsed) parsedOk++
            }

            // Air-only columns are left with a neutral fallback. This can happen
            // in chunks containing an empty vertical column, but it should never
            // be confused with a completely unparsed chunk.
            result[pos] = ChunkMapData(colors, surfaceY)

            blockEntityValueByChunk[pos]?.let { bytes ->
                try {
                    for (compound in NbtIO.readConcatenatedCompounds(bytes)) {
                        val id = (compound.entries["id"] as? NbtTag.Str)?.value ?: continue
                        val x = (compound.entries["x"] as? NbtTag.Int)?.value ?: continue
                        val y = (compound.entries["y"] as? NbtTag.Int)?.value ?: continue
                        val z = (compound.entries["z"] as? NbtTag.Int)?.value ?: continue
                        blockEntityMarkers.add(BlockEntityMarker(x, y, z, id))
                    }
                } catch (_: Exception) { }
            }

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
                    } catch (_: Exception) { }
                }
            }
        }

        val topReasons = failureReasonCounts.entries.sortedByDescending { it.value }.take(8).map { it.key to it.value }
        val stats = ScanStats(
            loadedChunks.size,
            outliersDropped,
            noSubchunkFound,
            parseFailures,
            parsedOk,
            unknownBlockNames,
            topReasons,
            failureReasonExamples
        )
        return ScanResult(result, stats, entityMarkers, blockEntityMarkers)
    }

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
