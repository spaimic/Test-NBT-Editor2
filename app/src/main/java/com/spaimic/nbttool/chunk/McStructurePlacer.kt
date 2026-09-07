package com.spaimic.nbttool.chunk

import com.spaimic.nbttool.nbt.NbtIO
import com.spaimic.nbttool.nbt.NbtTag
import com.spaimic.nbttool.shizuku.LevelDbBridge
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ThreadLocalRandom

/** First real MCStructure placement prototype. Blocks and structure.entities are placed;
 * block_position_data is reported but intentionally not written yet because its mapping to
 * the world's block-entity list needs additional format validation. */
object McStructurePlacer {
    data class Result(
        val blocksPlaced: Int,
        val chunksChanged: Int,
        val subchunksChanged: Int,
        val entitiesPlaced: Int,
        val blockEntitiesPending: Int,
        val backupPath: String
    )

    fun place(
        bridge: LevelDbBridge,
        structure: com.spaimic.nbttool.ui.McStructureAnalysis,
        originX: Int,
        originY: Int,
        originZ: Int,
        dimension: Int,
        backupPath: String
    ): Result {
        require(structure.blocks.isNotEmpty()) { "This structure contains no decoded blocks." }
        val grouped = LinkedHashMap<Pair<Int, Int>, MutableList<McStructureSubChunkPlacer.Placement>>()
        for (block in structure.blocks) {
            val wx = originX + block.x
            val wy = originY + block.y
            val wz = originZ + block.z
            val cx = Math.floorDiv(wx, 16)
            val cz = Math.floorDiv(wz, 16)
            val localX = Math.floorMod(wx, 16)
            val localY = Math.floorMod(wy, 16)
            val localZ = Math.floorMod(wz, 16)
            val subY = Math.floorDiv(wy, 16)
            grouped.getOrPut(cx to cz) { ArrayList() }.add(
                McStructureSubChunkPlacer.Placement(localX, localY, localZ, paletteCompound(structure, block.paletteIndex))
            )
        }

        // Never create an entirely new terrain chunk in the prototype. Requiring an existing
        // chunk prevents accidental creation of a malformed empty terrain chunk.
        for ((chunk, _) in grouped) {
            val (cx, cz) = chunk
            val hasTerrain = bridge.readSubchunks(cx, cz, dimension).isNotEmpty()
            if (!hasTerrain) throw IllegalStateException("Target chunk ($cx,$cz) does not exist in this dimension.")
        }

        val writes = ArrayList<Pair<ByteArray, ByteArray>>()
        var subchunksChanged = 0
        for ((chunk, placements) in grouped) {
            val (cx, cz) = chunk
            val bySubY = placements.groupBy { p -> p.y }
            val existing = bridge.readSubchunks(cx, cz, dimension).associateBy { it.first }
            for ((subY, cells) in bySubY) {
                val old = existing[subY]
                val bytes = if (old != null) {
                    McStructureSubChunkPlacer.apply(old.second, cells)
                } else {
                    McStructureSubChunkPlacer.create(cells)
                }
                writes += ChunkKeys.subChunkKey(cx, cz, subY, dimension) to bytes
                subchunksChanged++
            }
        }

        // Structure entities live in the structure NBT, but real world actor records are separate.
        // Generate fresh world-global actor IDs and append them to the appropriate digp lists.
        val entityWrites = placeEntities(bridge, structure.entities, originX, originY, originZ, dimension)
        writes.addAll(entityWrites)
        bridge.writeBatch(writes)

        return Result(
            blocksPlaced = structure.blocks.size,
            chunksChanged = grouped.size,
            subchunksChanged = subchunksChanged,
            entitiesPlaced = entityWrites.count { EntityKeys.isActorPrefixKey(it.first) },
            blockEntitiesPending = structure.blockPositionData?.entries?.size ?: 0,
            backupPath = backupPath
        )
    }

    private fun paletteCompound(a: com.spaimic.nbttool.ui.McStructureAnalysis, index: Int): NbtTag.Compound =
        a.palette.getOrNull(index)?.rawTag
            ?: throw IllegalStateException("Palette index $index is missing from the MCStructure.")

    private fun placeEntities(
        bridge: LevelDbBridge,
        entities: NbtTag.ListTag?,
        ox: Int, oy: Int, oz: Int,
        dimension: Int
    ): List<Pair<ByteArray, ByteArray>> {
        val list = entities?.items.orEmpty()
        if (list.isEmpty()) return emptyList()
        val writes = ArrayList<Pair<ByteArray, ByteArray>>()
        val digpAdds = LinkedHashMap<Pair<Int, Int>, MutableList<ByteArray>>()
        for (tag in list) {
            val root = tag as? NbtTag.Compound ?: continue
            val pos = root.entries["Pos"] as? NbtTag.ListTag ?: continue
            val x = numeric(pos.items.getOrNull(0)) ?: continue
            val y = numeric(pos.items.getOrNull(1)) ?: continue
            val z = numeric(pos.items.getOrNull(2)) ?: continue
            val worldX = x + ox; val worldY = y + oy; val worldZ = z + oz
            val newId = freshActorId(bridge)
            root.entries["Pos"] = NbtTag.ListTag(
                elementTypeId = 6,
                items = mutableListOf(NbtTag.Float(worldX.toFloat()), NbtTag.Float(worldY.toFloat()), NbtTag.Float(worldZ.toFloat()))
            )
            val unique = bytesToLong(newId)
            root.entries["UniqueID"] = NbtTag.Long(unique)
            if (root.entries["ActorUniqueID"] is NbtTag.Long) root.entries["ActorUniqueID"] = NbtTag.Long(unique)
            writes += EntityKeys.actorPrefixKey(newId) to NbtIO.writeRootCompoundNoHeader(root)
            val cx = Math.floorDiv(Math.floor(worldX).toInt(), 16)
            val cz = Math.floorDiv(Math.floor(worldZ).toInt(), 16)
            digpAdds.getOrPut(cx to cz) { ArrayList() }.add(newId)
        }

        for ((chunk, ids) in digpAdds) {
            val existing = bridge.read(EntityKeys.digpKey(chunk.first, chunk.second, dimension)) ?: ByteArray(0)
            val out = ByteArray(existing.size + ids.size * 8)
            existing.copyInto(out)
            ids.forEachIndexed { i, id -> id.copyInto(out, existing.size + i * 8) }
            writes += EntityKeys.digpKey(chunk.first, chunk.second, dimension) to out
        }
        return writes
    }

    private fun numeric(tag: NbtTag?): Double? = when (tag) {
        is NbtTag.Byte -> tag.value.toDouble()
        is NbtTag.Short -> tag.value.toDouble()
        is NbtTag.Int -> tag.value.toDouble()
        is NbtTag.Long -> tag.value.toDouble()
        is NbtTag.Float -> tag.value.toDouble()
        is NbtTag.Double -> tag.value
        else -> null
    }

    private fun freshActorId(bridge: LevelDbBridge): ByteArray {
        repeat(64) {
            val id = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(ThreadLocalRandom.current().nextLong()).array()
            if (bridge.read(EntityKeys.actorPrefixKey(id)) == null) return id
        }
        throw IllegalStateException("Couldn't allocate a unique actor ID for structure entity placement.")
    }

    private fun bytesToLong(id: ByteArray): Long =
        ByteBuffer.wrap(id).order(ByteOrder.LITTLE_ENDIAN).long
}
