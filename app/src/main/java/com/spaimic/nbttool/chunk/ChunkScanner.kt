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

/** A group of touching ore blocks. Touching includes faces, edges, and corners. */
data class OreCluster<T>(val coordinates: List<T>, val distanceSquaredToPlayer: Long)

/** Result of a radius-in-chunks ore search centered on the player's chunk. */
data class OreSearchResult<T>(
    val playerX: Int,
    val playerY: Int,
    val playerZ: Int,
    val centerChunkX: Int,
    val centerChunkZ: Int,
    val searchedChunks: Int,
    val coordinates: List<T>,
    val clusters: List<OreCluster<T>>
)

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
    ): List<DiamondCoordinate> = findTargetCoordinatesInChunk(
        bridge, chunkX, chunkZ, dimension,
        setOf("minecraft:diamond_ore", "minecraft:deepslate_diamond_ore")
    ).map { DiamondCoordinate(it.x, it.y, it.z) }

    /** Finds every ancient_debris block in one Nether chunk. */
    fun findAncientDebrisCoordinates(
        bridge: LevelDbBridge,
        chunkX: Int,
        chunkZ: Int,
        dimension: Int = 1
    ): List<OreCoordinate> {
        if (dimension != 1) return emptyList()
        return findTargetCoordinatesInChunk(
            bridge, chunkX, chunkZ, dimension, setOf("minecraft:ancient_debris")
        ).map { OreCoordinate(it.x, it.y, it.z) }
    }

    /**
     * Searches the full 9x9 chunk square centered on the player's chunk.
     * A radius of four produces exactly the cross + filled interior described
     * by the Map Viewer ore-finder. Missing/unloaded chunks are simply skipped.
     */
    fun findDiamondsNearPlayer(
        bridge: LevelDbBridge,
        playerX: Int, playerY: Int, playerZ: Int,
        dimension: Int = 0,
        parallelism: Int = 6
    ): OreSearchResult<DiamondCoordinate> {
        val found = searchTargetSquare(
            bridge, playerX, playerY, playerZ, dimension,
            setOf("minecraft:diamond_ore", "minecraft:deepslate_diamond_ore"),
            parallelism
        ).map { DiamondCoordinate(it.x, it.y, it.z) }
        val clusters = clusterCoordinates(found, playerX, playerY, playerZ) { Triple(it.x, it.y, it.z) }
        return OreSearchResult(
            playerX, playerY, playerZ, Math.floorDiv(playerX, 16), Math.floorDiv(playerZ, 16),
            81, found, clusters
        )
    }

    fun findAncientDebrisNearPlayer(
        bridge: LevelDbBridge,
        playerX: Int, playerY: Int, playerZ: Int,
        dimension: Int = 1,
        parallelism: Int = 6
    ): OreSearchResult<OreCoordinate> {
        if (dimension != 1) {
            return OreSearchResult(playerX, playerY, playerZ, Math.floorDiv(playerX, 16), Math.floorDiv(playerZ, 16), 81, emptyList(), emptyList())
        }
        val found = searchTargetSquare(
            bridge, playerX, playerY, playerZ, dimension,
            setOf("minecraft:ancient_debris"), parallelism
        ).map { OreCoordinate(it.x, it.y, it.z) }
        val clusters = clusterCoordinates(found, playerX, playerY, playerZ) { Triple(it.x, it.y, it.z) }
        return OreSearchResult(
            playerX, playerY, playerZ, Math.floorDiv(playerX, 16), Math.floorDiv(playerZ, 16),
            81, found, clusters
        )
    }

    private data class TargetCoordinate(val x: Int, val y: Int, val z: Int)

    private fun findTargetCoordinatesInChunk(
        bridge: LevelDbBridge, chunkX: Int, chunkZ: Int, dimension: Int, targetNames: Set<String>
    ): List<TargetCoordinate> {
        val subchunks = bridge.readSubchunks(chunkX, chunkZ, dimension)
        if (subchunks.isEmpty()) return emptyList()
        val result = ArrayList<TargetCoordinate>()
        for ((subY, bytes) in subchunks) {
            val compact = SubChunkParser.parseCompact(bytes) ?: continue
            val occupiedByLaterLayer = BooleanArray(4096)
            // Later block-storage layers override earlier layers. Walk backwards so
            // a target hidden by a non-air overlay is never reported.
            for (layer in compact.layers.asReversed()) {
                val targetPalette = BooleanArray(layer.palette.size)
                var hasTarget = false
                for (i in layer.palette.indices) {
                    if (layer.palette[i] in targetNames) { targetPalette[i] = true; hasTarget = true }
                }
                for (i in 0 until 4096) {
                    val paletteIndex = layer.indexAt(i)
                    val name = layer.palette.getOrNull(paletteIndex) ?: "minecraft:air"
                    if (!occupiedByLaterLayer[i]) {
                        if (hasTarget && paletteIndex < targetPalette.size && targetPalette[paletteIndex]) {
                            val x = i ushr 8
                            val z = (i ushr 4) and 15
                            val y = i and 15
                            result.add(TargetCoordinate(chunkX * 16 + x, subY * 16 + y, chunkZ * 16 + z))
                        }
                        if (!SubChunkParser.isAirForSearch(name)) occupiedByLaterLayer[i] = true
                    }
                }
            }
        }
        return result
    }

    private fun searchTargetSquare(
        bridge: LevelDbBridge, playerX: Int, playerY: Int, playerZ: Int, dimension: Int,
        targetNames: Set<String>, parallelism: Int
    ): List<TargetCoordinate> {
        val centerX = Math.floorDiv(playerX, 16)
        val centerZ = Math.floorDiv(playerZ, 16)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(parallelism.coerceIn(1, 8))
        try {
            val futures = ArrayList<java.util.concurrent.Future<List<TargetCoordinate>>>(81)
            for (dz in -4..4) for (dx in -4..4) {
                val cx = centerX + dx
                val cz = centerZ + dz
                futures.add(executor.submit<List<TargetCoordinate>> {
                    findTargetCoordinatesInChunk(bridge, cx, cz, dimension, targetNames)
                })
            }
            val result = ArrayList<TargetCoordinate>()
            for (future in futures) result.addAll(future.get())
            return result
        } finally {
            executor.shutdownNow()
        }
    }

    /**
     * Connected components under Chebyshev distance 1 in 3D: face, edge, and
     * corner contact all merge. A chain of touching blocks also belongs to one
     * cluster. Clusters are sorted by their nearest block to the player.
     */
    private fun <T> clusterCoordinates(
        coordinates: List<T>, playerX: Int, playerY: Int, playerZ: Int, position: (T) -> Triple<Int, Int, Int>
    ): List<OreCluster<T>> {
        if (coordinates.isEmpty()) return emptyList()
        val points = coordinates.map(position)
        val byPoint = HashMap<Triple<Int, Int, Int>, Int>(coordinates.size * 2)
        points.forEachIndexed { index, point -> byPoint.putIfAbsent(point, index) }
        val visited = BooleanArray(coordinates.size)
        val groups = ArrayList<List<T>>()
        for (start in coordinates.indices) {
            if (visited[start]) continue
            val queue = java.util.ArrayDeque<Int>()
            val group = ArrayList<T>()
            visited[start] = true
            queue.add(start)
            while (queue.isNotEmpty()) {
                val i = queue.removeFirst()
                group.add(coordinates[i])
                val p = points[i]
                for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
                    if (dx == 0 && dy == 0 && dz == 0) continue
                    val neighbor = Triple(p.first + dx, p.second + dy, p.third + dz)
                    val j = byPoint[neighbor] ?: continue
                    if (!visited[j]) { visited[j] = true; queue.add(j) }
                }
            }
            groups.add(group)
        }
        return groups.map { group ->
            val distance = group.minOf { p ->
                val (x, y, z) = position(p)
                // Player-to-block squared distance; Long avoids overflow for large worlds.
                val dx = x.toLong() - playerX.toLong()
                val dy = y.toLong() - playerY.toLong()
                val dz = z.toLong() - playerZ.toLong()
                dx * dx + dy * dy + dz * dz
            }
            OreCluster(group, distance)
        }.sortedBy { it.distanceSquaredToPlayer }
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
     * Renders one chunk's grid purely from already-fetched raw records — zero
     * LevelDB access happens in here.
     *
     * The whole-map exporter batches every subchunk/biome record for the
     * entire export region into memory with a SINGLE sequential LevelDB scan
     * up front (see WholeMapExporter.renderRows), instead of calling
     * loadChunkDirect() per chunk. Each call to loadChunkDirect() triggers a
     * fresh bridge.readSubchunks()/bridge.read(), and each of those creates a
     * brand-new LevelDB iterator/snapshot — expensive setup work (the vendored
     * org.iq80.leveldb port takes a global lock and rebuilds a merging
     * iterator over every SST file on every single call). Doing that once per
     * chunk, potentially thousands of times per export, dominates whole-map
     * export time far more than the actual per-chunk parsing/rendering CPU
     * work does. This function lets the worker pool spend its time on that
     * CPU work only, with no per-chunk database round trips at all.
     */
    fun loadChunkFromRawData(
        subchunks: List<Pair<Int, ByteArray>>,
        biomeBytes: ByteArray?,
        dimension: Int,
        unknownBlockNames: MutableSet<String> = HashSet(),
        failureReasonCounts: MutableMap<String, Int> = HashMap(),
        failureReasonExamples: MutableMap<String, String> = HashMap()
    ): Array<Array<ColumnInfo>>? {
        if (subchunks.isEmpty()) return null

        if (dimension == 1) {
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
            return NetherRenderer.render(blocks)
        }

        val grid = Array(16) { Array(16) { ColumnInfo(BlockColors.fallbackUnparsed, 0) } }
        val found = Array(16) { BooleanArray(16) }
        val fallback = Array(16) { arrayOfNulls<ColumnInfo>(16) }
        var remaining = 256
        var hadSuccessfulSubchunk = false
        val biomeData = try {
            biomeBytes?.let { BiomeMapParser.parse(it) }
        } catch (_: Exception) { null }

        val subchunkByY = arrayOfNulls<ByteArray>(24)
        for ((subY, bytes) in subchunks) {
            val index = subY + 4
            if (index in subchunkByY.indices) subchunkByY[index] = bytes
        }
        for (subY in 19 downTo -4) {
            if (remaining == 0) break
            val bytes = subchunkByY[subY + 4] ?: continue
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
        return grid
    }

    /**
     * Build 180 fast whole-map renderer.
     *
     * This is deliberately separate from the normal editor scanner. The old
     * whole-map path expanded every subchunk into a 16x16x16 String cube before
     * finding the surface. That is convenient for editing, but very expensive
     * when repeated over thousands of chunks. This path keeps Bedrock's packed
     * words intact, resolves palette entries only when a column needs them, and
     * allocates no per-block String/array objects.
     */
    fun loadChunkFromRawDataFast(
        subchunks: List<Pair<Int, ByteArray>>,
        biomeBytes: ByteArray?,
        dimension: Int,
        unknownBlockNames: MutableSet<String> = HashSet(),
        failureReasonCounts: MutableMap<String, Int> = HashMap(),
        failureReasonExamples: MutableMap<String, String> = HashMap()
    ): Array<Array<ColumnInfo>>? {
        if (subchunks.isEmpty()) return null

        // Keep the old Nether renderer for now: cave-layer rendering genuinely
        // needs more than a single surface sample. Build 180's fast path targets
        // the Overworld/End whole-map capture, which is the common expensive case.
        if (dimension == 1) {
            return loadChunkFromRawData(
                subchunks, biomeBytes, dimension,
                unknownBlockNames, failureReasonCounts, failureReasonExamples
            )
        }

        val grid = Array(16) { Array(16) { ColumnInfo(BlockColors.fallbackUnparsed, 0) } }
        val found = Array(16) { BooleanArray(16) }
        val fallback = arrayOfNulls<ColumnInfo>(256)
        var remaining = 256
        var hadSuccessfulSubchunk = false

        // Whole-map streaming records arrive in LevelDB key order, NOT in
        // vertical Y order. The subchunk index is the final key byte and its
        // byte-wise order is not the same as descending signed Y order.
        // Build 180/181 incorrectly assumed the streaming list was already
        // highest-to-lowest, which made the renderer start at negative/low
        // subchunks and produced an underground/cave-looking map.
        // Always normalize the order here so the fast renderer matches the
        // normal 2D Map Viewer surface selection.
        val orderedSubchunks = subchunks.sortedByDescending { it.first }

        // DATA_3D carries a 16x16 heightmap before the biome palettes. The
        // height value is the first air block above the surface, so for normal
        // terrain we can jump directly to the subchunk containing that surface
        // instead of decoding/scanning every empty subchunk above it. This is
        // the same hint used by Blocktopograph's BedrockChunk renderer.
        // -64 is Bedrock's unknown sentinel; those columns deliberately retain
        // the exhaustive fallback so a missing/stale heightmap cannot hide a
        // valid surface from the renderer.
        val heightMap = IntArray(256) { -64 }
        val hasHeightMap = dimension == 0 &&
            BiomeMapParser.heightMapFast(biomeBytes, heightMap)
        val startSubchunk = IntArray(256) { Int.MIN_VALUE }
        if (hasHeightMap) {
            for (column in 0 until 256) {
                val h = heightMap[column]
                if (h > -64) startSubchunk[column] = Math.floorDiv(h - 1, 16)
            }
        }

        for ((subY, bytes) in orderedSubchunks) {
            if (remaining == 0) break

            // If every column is either already resolved or has a valid
            // heightmap hint below this subchunk, the entire packed subchunk
            // can be skipped without even decoding its palettes.
            if (hasHeightMap) {
                var needed = false
                for (column in 0 until 256) {
                    val x = column and 15
                    val z = column ushr 4
                    if (found[x][z]) continue
                    val hint = startSubchunk[column]
                    if (hint == Int.MIN_VALUE || subY <= hint) {
                        needed = true
                        break
                    }
                }
                if (!needed) continue
            }

            val compact = SubChunkParser.parseCompact(bytes)
            if (compact == null) {
                failureReasonCounts["compact subchunk parse failed"] =
                    (failureReasonCounts["compact subchunk parse failed"] ?: 0) + 1
                val n = minOf(24, bytes.size)
                failureReasonExamples.putIfAbsent(
                    "compact subchunk parse failed",
                    "size=${bytes.size} bytes: " +
                        (0 until n).joinToString(" ") { "%02x".format(bytes[it]) }
                )
                continue
            }
            hadSuccessfulSubchunk = true

            // Resolve palette metadata once per storage layer. During the hot
            // 256-column loop we only need integer color/flags and the name for
            // the one block that becomes visible.
            data class PaletteMeta(
                val names: Array<String>,
                val colors: IntArray,
                val transparent: BooleanArray
            )
            val meta = ArrayList<PaletteMeta>(compact.layers.size)
            for (layer in compact.layers) {
                val colors = IntArray(layer.palette.size)
                val transparent = BooleanArray(layer.palette.size)
                for (i in layer.palette.indices) {
                    val name = layer.palette[i]
                    colors[i] = BlockColors.colorForDimension(name, dimension)
                    transparent[i] = BlockColors.isMapTransparent(name)
                }
                meta.add(PaletteMeta(layer.palette, colors, transparent))
            }

            for (z in 0 until 16) {
                for (x in 0 until 16) {
                    val column = (z shl 4) or x
                    if (found[x][z]) continue

                    // Find the highest non-air block in the effective storage.
                    // Later Bedrock storage layers have precedence when they
                    // contain a non-air value, matching mergeLayerInto().
                    // When the heightmap is valid, start at the exact surface
                    // subchunk/local Y instead of walking empty cells above it.
                    var y = 15
                    if (hasHeightMap) {
                        val hint = startSubchunk[column]
                        if (hint != Int.MIN_VALUE) {
                            if (subY > hint) continue
                            if (subY == hint) {
                                y = (heightMap[column] - 1 - subY * 16).coerceIn(0, 15)
                            }
                        }
                    }
                    while (y >= 0) {
                        val linear = SubChunkParser.linearIndex(x, z, y)
                        var selectedLayer = -1
                        var selectedPalette = -1
                        var layerIndex = compact.layers.lastIndex
                        while (layerIndex >= 0) {
                            val layer = compact.layers[layerIndex]
                            val paletteIndex = layer.indexAt(linear)
                            val name = meta[layerIndex].names.getOrNull(paletteIndex)
                            if (name != null && name != "minecraft:air" && name != "air") {
                                selectedLayer = layerIndex
                                selectedPalette = paletteIndex
                                break
                            }
                            layerIndex--
                        }

                        if (selectedLayer >= 0) {
                            val layerMeta = meta[selectedLayer]
                            val name = layerMeta.names.getOrElse(selectedPalette) { "minecraft:unknown" }
                            val worldY = subY * 16 + y
                            val baseColor = layerMeta.colors.getOrElse(
                                selectedPalette
                            ) { BlockColors.fallbackUnparsed }

                            if (fallback[column] == null) {
                                if (!BlockColors.isKnown(name)) unknownBlockNames.add(name)
                                fallback[column] = ColumnInfo(baseColor, worldY, name)
                            }

                            if (!layerMeta.transparent.getOrElse(selectedPalette) { false }) {
                                if (!BlockColors.isKnown(name)) unknownBlockNames.add(name)
                                grid[x][z] = ColumnInfo(baseColor, worldY, name)
                                found[x][z] = true
                                remaining--
                            }
                            break
                        }
                        y--
                    }
                }
            }
        }

        for (z in 0 until 16) {
            for (x in 0 until 16) {
                if (!found[x][z]) {
                    fallback[(z shl 4) or x]?.let {
                        grid[x][z] = it
                        found[x][z] = true
                    }
                }
            }
        }

        // Apply biome tint only to the final 256 visible columns. This avoids
        // decoding the complete 24x4096 biome volume for every chunk.
        if (dimension == 0 && biomeBytes != null) {
            val subYForColumn = IntArray(256)
            val localYForColumn = IntArray(256)
            val biomeIds = IntArray(256) { -1 }
            for (z in 0 until 16) for (x in 0 until 16) {
                val column = (z shl 4) or x
                val y = grid[x][z].surfaceY
                val sy = Math.floorDiv(y, 16)
                subYForColumn[column] = sy
                localYForColumn[column] = y - sy * 16
            }
            BiomeMapParser.biomeAtColumnsFast(
                biomeBytes, subYForColumn, localYForColumn, biomeIds
            )
            for (z in 0 until 16) for (x in 0 until 16) {
                val column = (z shl 4) or x
                val info = grid[x][z]
                val biomeId = biomeIds[column].takeIf { it >= 0 }
                if (biomeId != null) {
                    grid[x][z] = info.copy(
                        color = BiomeTint.apply(info.material, info.color, biomeId)
                    )
                }
            }
        }

        if (!hadSuccessfulSubchunk) return null
        return grid
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
