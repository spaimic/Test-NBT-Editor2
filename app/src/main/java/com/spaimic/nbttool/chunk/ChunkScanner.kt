package com.spaimic.nbttool.chunk

import com.spaimic.nbttool.nbt.NbtIO
import com.spaimic.nbttool.nbt.NbtTag
import com.spaimic.nbttool.shizuku.LevelDbBridge
import kotlin.math.abs

/** One rendered map column: the color to show, and the real block Y of its surface. */
data class ColumnInfo(val color: Int, val surfaceY: Int, val material: String = "")

/** One entity's position + identifier, for drawing a marker on the map. */
data class EntityMarker(val worldX: Int, val worldY: Int, val worldZ: Int, val identifier: String)

/** One block entity's position + id, for drawing a marker on the map. */
data class BlockEntityMarker(val worldX: Int, val worldY: Int, val worldZ: Int, val id: String)

data class DiamondCoordinate(val x: Int, val y: Int, val z: Int)
data class OreCoordinate(val x: Int, val y: Int, val z: Int)

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
    val grid: Map<Pair<Int, Int>, Array<Array<ColumnInfo>>>,
    val stats: ScanStats,
    val entityMarkers: List<EntityMarker>,
    val blockEntityMarkers: List<BlockEntityMarker>
)

/**
 * Lightweight index of an Overworld LevelDB.
 *
 * Building this index reads KEYS only. Values are read later, only for chunks
 * that are inside the MapView viewport. This is the important distinction from
 * the old scanner, which read every key/value pair and decoded every chunk before
 * the map could appear.
 */
class ChunkIndex private constructor(
    val chunkPositions: Set<Pair<Int, Int>>,
    internal val subchunks: Map<Pair<Int, Int>, List<Pair<Int, ByteArray>>>,
    internal val blockEntityKeys: Map<Pair<Int, Int>, ByteArray>,
    internal val digpKeys: Map<Pair<Int, Int>, ByteArray>,
    internal val actorKeys: Map<String, ByteArray>
) {
    fun hasChunk(x: Int, z: Int): Boolean = (x to z) in chunkPositions
    fun subchunkCount(x: Int, z: Int): Int = subchunks[x to z]?.size ?: 0

    companion object {
        fun fromKeys(keys: List<LevelDbBridge.KeyEntry>): ChunkIndex =
            fromRawKeys(keys.asSequence().map { it.rawKey })

        fun fromRawKeys(keys: Sequence<ByteArray>): ChunkIndex {
            val positions = HashSet<Pair<Int, Int>>()
            val subs = HashMap<Pair<Int, Int>, MutableList<Pair<Int, ByteArray>>>()
            val blockEntities = HashMap<Pair<Int, Int>, ByteArray>()
            val digp = HashMap<Pair<Int, Int>, ByteArray>()
            val actors = HashMap<String, ByteArray>()

            for (raw in keys) {
                val key = raw.copyOf()
                val info = ChunkKeys.parse(key)
                if (info != null && info.dimension == 0) {
                    val pos = info.chunkX to info.chunkZ
                    positions.add(pos)
                    when (info.tag) {
                        ChunkKeys.TAG_SUBCHUNK_PREFIX -> {
                            info.subChunkY?.let { y ->
                                subs.getOrPut(pos) { ArrayList() }.add(y to key)
                            }
                        }
                        ChunkKeys.TAG_BLOCK_ENTITY -> blockEntities[pos] = key
                    }
                } else if (EntityKeys.isOverworldDigpKey(key)) {
                    digp[EntityKeys.digpChunkXZ(key)] = key
                } else if (EntityKeys.isActorPrefixKey(key)) {
                    actors[EntityKeys.actorIdHex(key)] = key
                }
            }
            for (list in subs.values) list.sortByDescending { it.first }
            return ChunkIndex(positions, subs, blockEntities, digp, actors)
        }

        fun fromBridgeFast(
            bridge: LevelDbBridge,
            onNamedKey: ((String, ByteArray) -> Unit)? = null
        ): ChunkIndex {
            val positions = HashSet<Pair<Int, Int>>()
            val subs = HashMap<Pair<Int, Int>, MutableList<Pair<Int, ByteArray>>>()
            val blockEntities = HashMap<Pair<Int, Int>, ByteArray>()
            val digp = HashMap<Pair<Int, Int>, ByteArray>()
            val actors = HashMap<String, ByteArray>()

            bridge.forEachRawKey { raw ->
                val key = raw.copyOf()
                val info = ChunkKeys.parse(key)
                if (info != null && info.dimension == 0) {
                    val pos = info.chunkX to info.chunkZ
                    positions.add(pos)
                    when (info.tag) {
                        ChunkKeys.TAG_SUBCHUNK_PREFIX ->
                            info.subChunkY?.let { y -> subs.getOrPut(pos) { ArrayList() }.add(y to key) }
                        ChunkKeys.TAG_BLOCK_ENTITY -> blockEntities[pos] = key
                    }
                } else if (EntityKeys.isOverworldDigpKey(key)) {
                    digp[EntityKeys.digpChunkXZ(key)] = key
                } else if (EntityKeys.isActorPrefixKey(key)) {
                    actors[EntityKeys.actorIdHex(key)] = key
                }

                if (onNamedKey != null) {
                    val name = if (key.all { (it.toInt() and 0xFF) in 0x20..0x7E })
                        String(key, Charsets.US_ASCII) else null
                    if (name != null && (name == "~local_player" ||
                        name.startsWith("structuretemplate_") || name.startsWith("player_"))) {
                        onNamedKey(name, key.copyOf())
                    }
                }
            }
            for (list in subs.values) list.sortByDescending { it.first }
            return ChunkIndex(positions, subs, blockEntities, digp, actors)
        }
    }
}

object ChunkScanner {

    /**
     * Finds every diamond_ore and deepslate_diamond_ore block in one chunk.
     * Coordinates returned here are absolute world coordinates, and the scan
     * only reads the selected dimension's subchunks.
     */
    fun findDiamondCoordinates(
        bridge: LevelDbBridge,
        chunkX: Int,
        chunkZ: Int,
        dimension: Int = 0
    ): List<DiamondCoordinate> {
        val subchunks = bridge.readSubchunks(chunkX, chunkZ, dimension)
        if (subchunks.isEmpty()) return emptyList()

        val result = ArrayList<DiamondCoordinate>()
        val targetNames = setOf("minecraft:diamond_ore", "minecraft:deepslate_diamond_ore")

        for ((subY, bytes) in subchunks) {
            when (val parsed = SubChunkParser.parse(bytes)) {
                is SubChunkParser.ParseResult.Failure -> continue
                is SubChunkParser.ParseResult.Success -> {
                    val baseY = subY * 16
                    for (x in 0 until 16) {
                        for (z in 0 until 16) {
                            for (y in 0 until 16) {
                                if (parsed.blocks[x][z][y] in targetNames) {
                                    result.add(
                                        DiamondCoordinate(
                                            chunkX * 16 + x,
                                            baseY + y,
                                            chunkZ * 16 + z
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        return result.sortedWith(compareBy<DiamondCoordinate> { it.y }.thenBy { it.x }.thenBy { it.z })
    }

    /** Finds every ancient_debris block in one Nether chunk. */
    fun findAncientDebrisCoordinates(
        bridge: LevelDbBridge,
        chunkX: Int,
        chunkZ: Int,
        dimension: Int = 1
    ): List<OreCoordinate> {
        if (dimension != 1) return emptyList()
        val subchunks = bridge.readSubchunks(chunkX, chunkZ, dimension)
        if (subchunks.isEmpty()) return emptyList()

        val result = ArrayList<OreCoordinate>()
        val targetName = "minecraft:ancient_debris"
        for ((subY, bytes) in subchunks) {
            when (val parsed = SubChunkParser.parse(bytes)) {
                is SubChunkParser.ParseResult.Failure -> continue
                is SubChunkParser.ParseResult.Success -> {
                    val baseY = subY * 16
                    for (x in 0 until 16) for (z in 0 until 16) for (y in 0 until 16) {
                        if (parsed.blocks[x][z][y] == targetName) {
                            result.add(OreCoordinate(chunkX * 16 + x, baseY + y, chunkZ * 16 + z))
                        }
                    }
                }
            }
        }
        return result.sortedWith(compareBy<OreCoordinate> { it.y }.thenBy { it.x }.thenBy { it.z })
    }

    /**
     * Kept for compatibility with older callers. The Map Viewer no longer uses this
     * for initial loading because it would defeat lazy viewport loading.
     */
    fun scanOverworld(bridge: LevelDbBridge): ScanResult {
        val index = ChunkIndex.fromKeys(bridge.listKeys())
        val filtered = dropOutliers(index.chunkPositions)
        val result = HashMap<Pair<Int, Int>, Array<Array<ColumnInfo>>>()
        val entities = ArrayList<EntityMarker>()
        val blockEntities = ArrayList<BlockEntityMarker>()
        var parsed = 0
        var missing = 0
        val unknown = HashSet<String>()
        val reasons = HashMap<String, Int>()
        val examples = HashMap<String, String>()
        for (pos in filtered) {
            val loaded = loadChunk(bridge, index, pos.first, pos.second, unknown, reasons, examples)
            if (loaded != null) {
                result[pos] = loaded.grid
                parsed++
                entities.addAll(loaded.entityMarkers)
                blockEntities.addAll(loaded.blockEntityMarkers)
            } else {
                result[pos] = emptyGrid()
                missing++
            }
        }
        val stats = ScanStats(index.chunkPositions.size, index.chunkPositions.size - filtered.size, missing, reasons.values.sum(),
            parsed, unknown, reasons.entries.sortedByDescending { it.value }.take(8).map { it.key to it.value }, examples)
        return ScanResult(result, stats, entities, blockEntities)
    }

    data class LoadedChunk(
        val grid: Array<Array<ColumnInfo>>,
        val entityMarkers: List<EntityMarker>,
        val blockEntityMarkers: List<BlockEntityMarker>
    )

    data class LoadedMarkers(
        val entityMarkers: List<EntityMarker>,
        val blockEntityMarkers: List<BlockEntityMarker>
    )

    /** Reads only marker records for an already-rendered map chunk. */
    fun loadChunkMarkers(bridge: LevelDbBridge, chunkX: Int, chunkZ: Int, dimension: Int = 0): LoadedMarkers {
        val blockMarkers = ArrayList<BlockEntityMarker>()
        try {
            val bytes = bridge.read(ChunkKeys.blockEntityKey(chunkX, chunkZ, dimension))
            if (bytes != null) {
                for (compound in NbtIO.readConcatenatedCompounds(bytes)) {
                    val id = (compound.entries["id"] as? NbtTag.Str)?.value ?: continue
                    val x = (compound.entries["x"] as? NbtTag.Int)?.value ?: continue
                    val y = (compound.entries["y"] as? NbtTag.Int)?.value ?: continue
                    val z = (compound.entries["z"] as? NbtTag.Int)?.value ?: continue
                    blockMarkers.add(BlockEntityMarker(x, y, z, id))
                }
            }
        } catch (_: Exception) {}

        val entityMarkers = ArrayList<EntityMarker>()
        try {
            val digp = bridge.read(EntityKeys.digpKey(chunkX, chunkZ, dimension))
            if (digp != null) {
                for (actorId in EntityKeys.splitActorIds(digp)) {
                    try {
                        val actor = bridge.read(EntityKeys.actorPrefixKey(actorId)) ?: continue
                        val compound = NbtIO.readRootCompoundNoHeader(actor)
                        val identifier = (compound.entries["identifier"] as? NbtTag.Str)?.value ?: continue
                        val posTag = compound.entries["Pos"] as? NbtTag.ListTag ?: continue
                        val px = (posTag.items.getOrNull(0) as? NbtTag.Float)?.value ?: continue
                        val py = (posTag.items.getOrNull(1) as? NbtTag.Float)?.value ?: continue
                        val pz = (posTag.items.getOrNull(2) as? NbtTag.Float)?.value ?: continue
                        entityMarkers.add(EntityMarker(px.toInt(), py.toInt(), pz.toInt(), identifier))
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
        return LoadedMarkers(entityMarkers, blockMarkers)
    }

    /**
     * Fast startup path: read one chunk directly from LevelDB without waiting for
     * the global index. A single iterator seek retrieves all subchunks for the
     * requested chunk. Entity/block-entity markers are intentionally deferred
     * until the global index is ready.
     */
    fun loadChunkDirect(
        bridge: LevelDbBridge,
        chunkX: Int,
        chunkZ: Int,
        unknownBlockNames: MutableSet<String> = HashSet(),
        failureReasonCounts: MutableMap<String, Int> = HashMap(),
        failureReasonExamples: MutableMap<String, String> = HashMap(),
        dimension: Int = 0,
        includeMarkers: Boolean = true
    ): LoadedChunk? {
        val subchunks = bridge.readSubchunks(chunkX, chunkZ, dimension)
        if (subchunks.isEmpty()) return null

        // The Nether cannot use the Overworld/End "highest block" renderer. Its
        // roof is itself a solid bedrock surface, while the playable terrain is
        // distributed through stacked cave layers underneath it. Decode the full
        // vertical column and use the dedicated layered renderer instead.
        if (dimension == 1) {
            return loadNetherChunkDirect(bridge, chunkX, chunkZ, subchunks, unknownBlockNames, failureReasonCounts, failureReasonExamples, includeMarkers)
        }

        val grid = Array(16) { Array(16) { ColumnInfo(BlockColors.fallbackUnparsed, 0) } }
        val found = Array(16) { BooleanArray(16) }
        val fallback = Array(16) { arrayOfNulls<ColumnInfo>(16) }
        var remaining = 256
        var hadSuccessfulSubchunk = false
        val biomeData = try {
            bridge.read(ChunkKeys.data3DKey(chunkX, chunkZ, dimension))?.let { BiomeMapParser.parse(it) }
        } catch (_: Exception) { null }

        // Surface scan is deliberately exhaustive: Bedrock Overworld build height is
        // Y=-64..320 (20 subchunks, -4..19). Missing subchunks are treated as air,
        // while existing subchunks are scanned from the highest Y downward. The
        // first non-air, non-transparent block in each column is the surface.
        val subchunkByY = subchunks.associate { it.first to it.second }
        for (subY in 19 downTo -4) {
            if (remaining == 0) break
            val bytes = subchunkByY[subY] ?: continue
            when (val parsed = SubChunkParser.parse(bytes)) {
                is SubChunkParser.ParseResult.Failure -> {
                    failureReasonCounts[parsed.reason] = (failureReasonCounts[parsed.reason] ?: 0) + 1
                    failureReasonExamples.putIfAbsent(parsed.reason, parsed.hexSample)
                }
                is SubChunkParser.ParseResult.Success -> {
                    hadSuccessfulSubchunk = true
                    for (x in 0 until 16) for (z in 0 until 16) {
                        if (found[x][z]) continue
                        for (y in 15 downTo 0) {
                            val name = parsed.blocks[x][z][y]
                            if (name == "minecraft:air") continue

                            if (fallback[x][z] == null) {
                                if (!BlockColors.isKnown(name)) unknownBlockNames.add(name)
                                val surfaceY = subY * 16 + y
                                val biomeId = BiomeMapParser.biomeAt(biomeData, surfaceY, x, z, subY)
                                val baseColor = BlockColors.colorForDimension(name, dimension)
                                val tinted = if (dimension == 0) BiomeTint.apply(name, baseColor, biomeId) else baseColor
                                fallback[x][z] = ColumnInfo(tinted, surfaceY, name)
                            }

                            if (!BlockColors.isMapTransparent(name)) {
                                if (!BlockColors.isKnown(name)) unknownBlockNames.add(name)
                                val surfaceY = subY * 16 + y
                                val biomeId = BiomeMapParser.biomeAt(biomeData, surfaceY, x, z, subY)
                                val baseColor = BlockColors.colorForDimension(name, dimension)
                                val tinted = if (dimension == 0) BiomeTint.apply(name, baseColor, biomeId) else baseColor
                                grid[x][z] = ColumnInfo(tinted, surfaceY, name)
                                found[x][z] = true
                                remaining--
                                break
                            }
                        }
                    }
                }
            }
        }
        for (x in 0 until 16) {
            for (z in 0 until 16) {
                if (!found[x][z]) {
                    fallback[x][z]?.let {
                        grid[x][z] = it
                        found[x][z] = true
                    }
                }
            }
        }
        if (!hadSuccessfulSubchunk) return null

        // Marker reads are optional because they are much more expensive than
        // terrain decoding. Build 111 loads terrain first and can add markers
        // asynchronously after the map becomes visible.
        val blockMarkers = ArrayList<BlockEntityMarker>()
        val entityMarkers = ArrayList<EntityMarker>()
        if (includeMarkers) {
            try {
                val bytes = bridge.read(ChunkKeys.blockEntityKey(chunkX, chunkZ, dimension))
                if (bytes != null) {
                    for (compound in NbtIO.readConcatenatedCompounds(bytes)) {
                        val id = (compound.entries["id"] as? NbtTag.Str)?.value ?: continue
                        val x = (compound.entries["x"] as? NbtTag.Int)?.value ?: continue
                        val y = (compound.entries["y"] as? NbtTag.Int)?.value ?: continue
                        val z = (compound.entries["z"] as? NbtTag.Int)?.value ?: continue
                        blockMarkers.add(BlockEntityMarker(x, y, z, id))
                    }
                }
            } catch (_: Exception) {}
    
            try {
                val digp = bridge.read(EntityKeys.digpKey(chunkX, chunkZ, dimension))
                if (digp != null) {
                    for (actorId in EntityKeys.splitActorIds(digp)) {
                        try {
                            val actor = bridge.read(EntityKeys.actorPrefixKey(actorId)) ?: continue
                            val compound = NbtIO.readRootCompoundNoHeader(actor)
                            val identifier = (compound.entries["identifier"] as? NbtTag.Str)?.value ?: continue
                            val posTag = compound.entries["Pos"] as? NbtTag.ListTag ?: continue
                            val px = (posTag.items.getOrNull(0) as? NbtTag.Float)?.value ?: continue
                            val py = (posTag.items.getOrNull(1) as? NbtTag.Float)?.value ?: continue
                            val pz = (posTag.items.getOrNull(2) as? NbtTag.Float)?.value ?: continue
                            entityMarkers.add(EntityMarker(px.toInt(), py.toInt(), pz.toInt(), identifier))
                        } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
    
        }

        MapShading.apply(grid)
        return LoadedChunk(grid, entityMarkers, blockMarkers)
    }

    private fun loadNetherChunkDirect(
        bridge: LevelDbBridge,
        chunkX: Int,
        chunkZ: Int,
        subchunks: List<Pair<Int, ByteArray>>,
        unknownBlockNames: MutableSet<String>,
        failureReasonCounts: MutableMap<String, Int>,
        failureReasonExamples: MutableMap<String, String>,
        includeMarkers: Boolean = true
    ): LoadedChunk? {
        // The legacy Nether renderer worked with a 0..255 vertical world. Keep the
        // same coordinate model here; the modern Bedrock subchunk keys are still
        // decoded individually and the highest positive layers contain the Nether
        // roof/caverns that this renderer is designed to display.
        val blocks = Array(16) { Array(16) { Array(256) { "minecraft:air" } } }
        var hadSuccessfulSubchunk = false

        for ((subY, bytes) in subchunks) {
            val parsed = SubChunkParser.parse(bytes)
            when (parsed) {
                is SubChunkParser.ParseResult.Failure -> {
                    failureReasonCounts[parsed.reason] = (failureReasonCounts[parsed.reason] ?: 0) + 1
                    failureReasonExamples.putIfAbsent(parsed.reason, parsed.hexSample)
                }
                is SubChunkParser.ParseResult.Success -> {
                    hadSuccessfulSubchunk = true
                    val baseY = subY * 16
                    for (x in 0 until 16) for (z in 0 until 16) for (innerY in 0 until 16) {
                        val worldY = baseY + innerY
                        if (worldY !in 0 until 256) continue
                        val name = parsed.blocks[x][z][innerY]
                        blocks[x][z][worldY] = name
                        if (name != "minecraft:air" && !BlockColors.isKnown(name)) unknownBlockNames.add(name)
                    }
                }
            }
        }
        if (!hadSuccessfulSubchunk) return null

        val grid = NetherRenderer.render(blocks)

        val blockMarkers = ArrayList<BlockEntityMarker>()
        val entityMarkers = ArrayList<EntityMarker>()
        if (includeMarkers) {
            try {
                val bytes = bridge.read(ChunkKeys.blockEntityKey(chunkX, chunkZ, 1))
                if (bytes != null) {
                    for (compound in NbtIO.readConcatenatedCompounds(bytes)) {
                        val id = (compound.entries["id"] as? NbtTag.Str)?.value ?: continue
                        val x = (compound.entries["x"] as? NbtTag.Int)?.value ?: continue
                        val y = (compound.entries["y"] as? NbtTag.Int)?.value ?: continue
                        val z = (compound.entries["z"] as? NbtTag.Int)?.value ?: continue
                        blockMarkers.add(BlockEntityMarker(x, y, z, id))
                    }
                }
            } catch (_: Exception) {}
    
            try {
                val digp = bridge.read(EntityKeys.digpKey(chunkX, chunkZ, 1))
                if (digp != null) {
                    for (actorId in EntityKeys.splitActorIds(digp)) {
                        try {
                            val actor = bridge.read(EntityKeys.actorPrefixKey(actorId)) ?: continue
                            val compound = NbtIO.readRootCompoundNoHeader(actor)
                            val identifier = (compound.entries["identifier"] as? NbtTag.Str)?.value ?: continue
                            val posTag = compound.entries["Pos"] as? NbtTag.ListTag ?: continue
                            val px = (posTag.items.getOrNull(0) as? NbtTag.Float)?.value ?: continue
                            val py = (posTag.items.getOrNull(1) as? NbtTag.Float)?.value ?: continue
                            val pz = (posTag.items.getOrNull(2) as? NbtTag.Float)?.value ?: continue
                            entityMarkers.add(EntityMarker(px.toInt(), py.toInt(), pz.toInt(), identifier))
                        } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
    
        }

        return LoadedChunk(grid, entityMarkers, blockMarkers)
    }

    /**
     * Reads one visible chunk only. Subchunks are decoded highest-to-lowest until
     * all 256 X/Z columns have a non-air surface.
     */
    fun loadChunk(
        bridge: LevelDbBridge,
        index: ChunkIndex,
        chunkX: Int,
        chunkZ: Int,
        unknownBlockNames: MutableSet<String> = HashSet(),
        failureReasonCounts: MutableMap<String, Int> = HashMap(),
        failureReasonExamples: MutableMap<String, String> = HashMap()
    ): LoadedChunk? {
        val pos = chunkX to chunkZ
        if (!index.hasChunk(chunkX, chunkZ)) return null

        val grid = Array(16) { Array(16) { ColumnInfo(BlockColors.fallbackUnparsed, 0) } }
        val found = Array(16) { BooleanArray(16) }
        val fallback = Array(16) { arrayOfNulls<ColumnInfo>(16) }
        var remaining = 256
        var hadSuccessfulSubchunk = false
        val biomeData = try {
            bridge.read(ChunkKeys.data3DKey(chunkX, chunkZ))?.let { BiomeMapParser.parse(it) }
        } catch (_: Exception) { null }

        for ((subY, rawKey) in index.subchunks[pos].orEmpty()) {
            if (remaining == 0) break
            val bytes = bridge.read(rawKey) ?: continue
            when (val parsed = SubChunkParser.parse(bytes)) {
                is SubChunkParser.ParseResult.Failure -> {
                    failureReasonCounts[parsed.reason] = (failureReasonCounts[parsed.reason] ?: 0) + 1
                    failureReasonExamples.putIfAbsent(parsed.reason, parsed.hexSample)
                }
                is SubChunkParser.ParseResult.Success -> {
                    hadSuccessfulSubchunk = true
                    val blocks = parsed.blocks
                    for (x in 0 until 16) {
                        for (z in 0 until 16) {
                            if (found[x][z]) continue
                            for (y in 15 downTo 0) {
                                val name = blocks[x][z][y]
                                if (name == "minecraft:air") continue

                                if (fallback[x][z] == null) {
                                    if (!BlockColors.isKnown(name)) unknownBlockNames.add(name)
                                    val surfaceY = subY * 16 + y
                                    val biomeId = BiomeMapParser.biomeAt(biomeData, surfaceY, x, z, subY)
                                    val baseColor = BlockColors.colorForDimension(name, 0)
                                    fallback[x][z] = ColumnInfo(BiomeTint.apply(name, baseColor, biomeId), surfaceY, name)
                                }

                                if (!BlockColors.isMapTransparent(name)) {
                                    if (!BlockColors.isKnown(name)) unknownBlockNames.add(name)
                                    val surfaceY = subY * 16 + y
                                    val biomeId = BiomeMapParser.biomeAt(biomeData, surfaceY, x, z, subY)
                                    val baseColor = BlockColors.colorForDimension(name, 0)
                                    grid[x][z] = ColumnInfo(BiomeTint.apply(name, baseColor, biomeId), surfaceY, name)
                                    found[x][z] = true
                                    remaining--
                                    break
                                }
                            }
                        }
                    }
                }
            }
        }

        for (x in 0 until 16) {
            for (z in 0 until 16) {
                if (!found[x][z]) {
                    fallback[x][z]?.let {
                        grid[x][z] = it
                        found[x][z] = true
                    }
                }
            }
        }

        if (!hadSuccessfulSubchunk) return null

        // Load markers only for this visible chunk. They are deliberately not part
        // of the global initial scan.
        val blockMarkers = ArrayList<BlockEntityMarker>()
        index.blockEntityKeys[pos]?.let { key ->
            try {
                val bytes = bridge.read(key)
                if (bytes != null) {
                    for (compound in NbtIO.readConcatenatedCompounds(bytes)) {
                        val id = (compound.entries["id"] as? NbtTag.Str)?.value ?: continue
                        val x = (compound.entries["x"] as? NbtTag.Int)?.value ?: continue
                        val y = (compound.entries["y"] as? NbtTag.Int)?.value ?: continue
                        val z = (compound.entries["z"] as? NbtTag.Int)?.value ?: continue
                        blockMarkers.add(BlockEntityMarker(x, y, z, id))
                    }
                }
            } catch (_: Exception) {}
        }

        val entityMarkers = ArrayList<EntityMarker>()
        index.digpKeys[pos]?.let { digpKey ->
            try {
                val digpValue = bridge.read(digpKey)
                if (digpValue != null) {
                    for (actorId in EntityKeys.splitActorIds(digpValue)) {
                        val idHex = actorId.joinToString("") { "%02x".format(it) }
                        val actorKey = index.actorKeys[idHex] ?: continue
                        val bytes = bridge.read(actorKey) ?: continue
                        try {
                            val compound = NbtIO.readRootCompoundNoHeader(bytes)
                            val identifier = (compound.entries["identifier"] as? NbtTag.Str)?.value ?: continue
                            val posTag = compound.entries["Pos"] as? NbtTag.ListTag ?: continue
                            val px = (posTag.items.getOrNull(0) as? NbtTag.Float)?.value ?: continue
                            val py = (posTag.items.getOrNull(1) as? NbtTag.Float)?.value ?: continue
                            val pz = (posTag.items.getOrNull(2) as? NbtTag.Float)?.value ?: continue
                            entityMarkers.add(EntityMarker(px.toInt(), py.toInt(), pz.toInt(), identifier))
                        } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
        }

        return LoadedChunk(grid, entityMarkers, blockMarkers)
    }

    private fun emptyGrid() =
        Array(16) { Array(16) { ColumnInfo(BlockColors.fallbackUnparsed, 0) } }

    private fun dropOutliers(positions: Set<Pair<Int, Int>>): Set<Pair<Int, Int>> {
        if (positions.size <= 2) return positions
        val xs = positions.map { it.first }.sorted()
        val zs = positions.map { it.second }.sorted()
        val medianX = xs[xs.size / 2]
        val medianZ = zs[zs.size / 2]
        val maxChunkDistance = 2000
        return positions.filter { (x, z) ->
            abs(x - medianX) <= maxChunkDistance && abs(z - medianZ) <= maxChunkDistance
        }.toSet()
    }
}
