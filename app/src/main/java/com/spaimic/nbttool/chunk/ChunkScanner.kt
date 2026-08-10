package com.spaimic.nbttool.chunk

import android.graphics.Color
import com.spaimic.nbttool.nbt.NbtIO
import com.spaimic.nbttool.nbt.NbtTag
import com.spaimic.nbttool.shizuku.LevelDbBridge
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

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
     * Build 121: exact port of Blocktopograph's water depth shading
     * (map/renderer/SatelliteRenderer.getColumnColour). Repeatedly alpha-composites
     * a color of alpha [BlockColors.WATER_LAYER_ALPHA] over [floorColor], [depth]
     * times, using the closed form of that composite instead of an actual loop.
     */
    private fun blendWaterDepth(waterColor: Int, floorColor: Int, depth: Int): Int {
        val a = BlockColors.WATER_LAYER_ALPHA
        // alphaRemain after `depth` layers, matching Blocktopograph's loop cutoff
        // (it stops once alphaRemain < 0.1 -- by depth 8 this is already ~0.4%).
        val remain = (1f - a).toDouble().pow(min(depth, 8).toDouble()).toFloat()
        fun mix(top: Int, bottom: Int) = (top * (1f - remain) + bottom * remain).toInt().coerceIn(0, 255)
        return Color.rgb(
            mix(Color.red(waterColor), Color.red(floorColor)),
            mix(Color.green(waterColor), Color.green(floorColor)),
            mix(Color.blue(waterColor), Color.blue(floorColor))
        )
    }

    /**
     * Looks a few blocks beneath a water surface (within the same decoded
     * subchunk) to find the water's depth and what's underneath it, then
     * blends toward that floor color the same way Blocktopograph does. Air
     * pockets (underwater caves) are skipped rather than counted as depth.
     */
    private fun blendWaterColumn(
        blocks: Array<Array<Array<String>>>,
        x: Int, z: Int, y: Int,
        waterColor: Int,
        dimension: Int
    ): Int {
        var depth = 1
        var floorColor: Int? = null
        var yy = y - 1
        val floorLimit = max(0, y - 8)
        while (yy >= floorLimit) {
            when (val below = blocks[x][z][yy]) {
                "minecraft:water", "minecraft:flowing_water" -> depth++
                "minecraft:air", "minecraft:cave_air" -> {}
                else -> {
                    floorColor = BlockColors.colorForDimension(below, dimension)
                    break
                }
            }
            yy--
        }
        return blendWaterDepth(waterColor, floorColor ?: waterColor, depth)
    }

    private fun isWater(name: String) = name == "minecraft:water" || name == "minecraft:flowing_water"

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

        val grid = Array(16) { Array(16) { ColumnInfo(BlockColors.fallbackUnparsed, Int.MIN_VALUE) } }
        var hadSuccessfulSubchunk = false
        val biomeData = try {
            bridge.read(ChunkKeys.data3DKey(chunkX, chunkZ, dimension))?.let { BiomeMapParser.parse(it) }
        } catch (_: Exception) { null }

        // IMPORTANT: inspect every successfully decoded subchunk. The previous
        // implementation stopped once a column had a candidate surface, which
        // could preserve a lower block when records arrived out of order or when
        // a higher subchunk was decoded later. The surface is the MAXIMUM Y of a
        // non-transparent block across all available subchunks.
        for ((subY, bytes) in subchunks.sortedByDescending { it.first }) {
            when (val parsed = SubChunkParser.parse(bytes)) {
                is SubChunkParser.ParseResult.Failure -> {
                    failureReasonCounts[parsed.reason] = (failureReasonCounts[parsed.reason] ?: 0) + 1
                    failureReasonExamples.putIfAbsent(parsed.reason, parsed.hexSample)
                }
                is SubChunkParser.ParseResult.Success -> {
                    hadSuccessfulSubchunk = true
                    for (x in 0 until 16) for (z in 0 until 16) {
                        for (y in 15 downTo 0) {
                            val name = parsed.blocks[x][z][y]
                            if (name != "minecraft:air" && !BlockColors.isMapTransparent(name)) {
                                val surfaceY = subY * 16 + y
                                // This scan is already top-down within a subchunk.
                                // Never allow a lower candidate to replace a higher one.
                                if (surfaceY > grid[x][z].surfaceY) {
                                    if (!BlockColors.isKnown(name)) unknownBlockNames.add(name)
                                    val biomeId = BiomeMapParser.biomeAt(biomeData, surfaceY, x, z, subY)
                                    val baseColor = BlockColors.colorForDimension(name, dimension)
                                    val tinted = if (dimension == 0) BiomeTint.apply(name, baseColor, biomeId) else baseColor
                                    val finalColor = if (dimension == 0 && isWater(name))
                                        blendWaterColumn(parsed.blocks, x, z, y, tinted, dimension)
                                    else tinted
                                    grid[x][z] = ColumnInfo(finalColor, surfaceY, name)
                                }
                                break
                            }
                        }
                    }
                }
            }
        }
        if (!hadSuccessfulSubchunk) return null

        // A completely empty/transparent column is valid, but it should not carry
        // Int.MIN_VALUE into the map shading pass. Keep it at the lowest world Y.
        for (x in 0 until 16) for (z in 0 until 16) {
            if (grid[x][z].surfaceY == Int.MIN_VALUE) {
                grid[x][z] = grid[x][z].copy(surfaceY = -64)
            }
        }

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

        // Build 121: elevation shading is no longer baked into the stored column
        // color here. Blocktopograph itself only shades at render/tile-draw time
        // (SatelliteRenderer/HeightmapRenderer), using the exact west/north
        // sigmoid now in MapView.reliefShading; baking a second, different shading
        // pass in here as well would double-apply and drift from that formula.
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

        val grid = Array(16) { Array(16) { ColumnInfo(BlockColors.fallbackUnparsed, Int.MIN_VALUE) } }
        var hadSuccessfulSubchunk = false
        val biomeData = try {
            bridge.read(ChunkKeys.data3DKey(chunkX, chunkZ))?.let { BiomeMapParser.parse(it) }
        } catch (_: Exception) { null }

        // Do not stop after finding the first surface. Compute the true maximum Y
        // independently for every X/Z column across every decoded subchunk.
        for ((subY, rawKey) in index.subchunks[pos].orEmpty().sortedByDescending { it.first }) {
            val bytes = bridge.read(rawKey) ?: continue
            when (val parsed = SubChunkParser.parse(bytes)) {
                is SubChunkParser.ParseResult.Failure -> {
                    failureReasonCounts[parsed.reason] = (failureReasonCounts[parsed.reason] ?: 0) + 1
                    failureReasonExamples.putIfAbsent(parsed.reason, parsed.hexSample)
                }
                is SubChunkParser.ParseResult.Success -> {
                    hadSuccessfulSubchunk = true
                    val blocks = parsed.blocks
                    for (x in 0 until 16) for (z in 0 until 16) {
                        for (y in 15 downTo 0) {
                            val name = blocks[x][z][y]
                            if (name != "minecraft:air" && !BlockColors.isMapTransparent(name)) {
                                val surfaceY = subY * 16 + y
                                if (surfaceY > grid[x][z].surfaceY) {
                                    if (!BlockColors.isKnown(name)) unknownBlockNames.add(name)
                                    val biomeId = BiomeMapParser.biomeAt(biomeData, surfaceY, x, z, subY)
                                    val baseColor = BlockColors.colorForDimension(name, 0)
                                    val tinted = BiomeTint.apply(name, baseColor, biomeId)
                                    val finalColor = if (isWater(name)) blendWaterColumn(blocks, x, z, y, tinted, 0) else tinted
                                    grid[x][z] = ColumnInfo(finalColor, surfaceY, name)
                                }
                                break
                            }
                        }
                    }
                }
            }
        }

        if (!hadSuccessfulSubchunk) return null
        for (x in 0 until 16) for (z in 0 until 16) {
            if (grid[x][z].surfaceY == Int.MIN_VALUE) {
                grid[x][z] = grid[x][z].copy(surfaceY = -64)
            }
        }

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
