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
                McStructureSubChunkPlacer.Placement(localX, localY, localZ, subY, paletteCompound(structure, block.paletteIndex))
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
            val bySubY = placements.groupBy { p -> p.subChunkY }
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
        val generatedStorageKeys = HashSet<String>()
        var entityIndex = 0

        for (tag in list) {
            entityIndex++
            val source = tag as? NbtTag.Compound
                ?: throw IllegalStateException("Structure entity #$entityIndex is not a compound NBT tag.")

            val pos = source.entries["Pos"] as? NbtTag.ListTag
                ?: throw IllegalStateException("Structure entity #$entityIndex is missing a valid Pos list.")
            val x = numeric(pos.items.getOrNull(0))
                ?: throw IllegalStateException("Structure entity #$entityIndex has an invalid Pos X value.")
            val y = numeric(pos.items.getOrNull(1))
                ?: throw IllegalStateException("Structure entity #$entityIndex has an invalid Pos Y value.")
            val z = numeric(pos.items.getOrNull(2))
                ?: throw IllegalStateException("Structure entity #$entityIndex has an invalid Pos Z value.")

            // Round-trip through NBT so the decoded MCStructure entity itself is never mutated.
            val root = NbtIO.readRootCompoundNoHeader(NbtIO.writeRootCompoundNoHeader(source))
            val worldX = x + ox
            val worldY = y + oy
            val worldZ = z + oz

            val rawActorId = freshActorId(bridge, generatedStorageKeys)
            val storageKey = EntityKeys.storageKeyBytes(rawActorId)
            val storageHex = EntityKeys.actorIdBytesHex(storageKey)
            if (!generatedStorageKeys.add(storageHex)) {
                throw IllegalStateException("Generated duplicate actor storage key for structure entity #$entityIndex.")
            }

            root.entries["Pos"] = NbtTag.ListTag(
                elementTypeId = 6,
                items = mutableListOf(
                    NbtTag.Float(worldX.toFloat()),
                    NbtTag.Float(worldY.toFloat()),
                    NbtTag.Float(worldZ.toFloat())
                )
            )
            // These are the raw world ActorUniqueID values. The LevelDB key uses the
            // derived storage key, not these raw bytes.
            root.entries["UniqueID"] = NbtTag.Long(rawActorId)
            root.entries["ActorUniqueID"] = NbtTag.Long(rawActorId)

            val actorKey = EntityKeys.actorPrefixKey(storageKey)
            if (bridge.read(actorKey) != null) {
                throw IllegalStateException("Generated actor storage key already exists for structure entity #$entityIndex.")
            }
            // Modern Bedrock actorprefix values are not the structure entity compound directly.
            // They contain a root compound with the actual actor data under the `Entity` tag.
            // Keeping this wrapper is essential: the structure's `entities` list contains the
            // actor payload, while the LevelDB actor record has one additional storage layer.
            val actorRecord = NbtTag.Compound(
                linkedMapOf("Entity" to root)
            )
            val actorNbt = NbtIO.writeRootCompoundNoHeader(actorRecord)
            if (actorNbt.isEmpty()) {
                throw IllegalStateException("Entity #$entityIndex produced empty actor-record NBT; placement was aborted.")
            }
            // Round-trip the exact bytes before committing anything so a malformed record
            // cannot be introduced into the world database.
            val roundTrip = NbtIO.readRootCompoundNoHeader(actorNbt)
            val roundTripEntity = roundTrip.entries["Entity"] as? NbtTag.Compound
                ?: throw IllegalStateException("Entity #$entityIndex actor record is missing its Entity compound; placement was aborted.")
            if (roundTripEntity.entries["Pos"] !is NbtTag.ListTag ||
                roundTripEntity.entries["UniqueID"] !is NbtTag.Long ||
                roundTripEntity.entries["ActorUniqueID"] !is NbtTag.Long
            ) {
                throw IllegalStateException("Entity #$entityIndex actor record failed required-field validation; placement was aborted.")
            }
            writes += actorKey to actorNbt

            val cx = Math.floorDiv(Math.floor(worldX).toInt(), 16)
            val cz = Math.floorDiv(Math.floor(worldZ).toInt(), 16)
            digpAdds.getOrPut(cx to cz) { ArrayList() }.add(storageKey)
        }

        // Validate every existing digest before changing it. A malformed digest would make
        // Minecraft unable to parse the actor list for that chunk, so never append to one.
        for ((chunk, storageKeys) in digpAdds) {
            val existing = bridge.read(EntityKeys.digpKey(chunk.first, chunk.second, dimension)) ?: ByteArray(0)
            if (existing.size % 8 != 0) {
                throw IllegalStateException("Existing actor digest for chunk (${chunk.first},${chunk.second}) is malformed; placement was aborted.")
            }
            val out = ByteArray(existing.size + storageKeys.size * 8)
            existing.copyInto(out)
            storageKeys.forEachIndexed { i, key -> key.copyInto(out, existing.size + i * 8) }
            writes += EntityKeys.digpKey(chunk.first, chunk.second, dimension) to out

            // Modern Bedrock chunks identify the actor-digest format with tag 0x41.
            // Existing chunks normally already contain it; add version 0 only when it is absent.
            val digestVersionKey = ChunkKeys.actorDigestVersionKey(chunk.first, chunk.second, dimension)
            if (bridge.read(digestVersionKey) == null) {
                writes += digestVersionKey to byteArrayOf(0)
            }
        }

        // Final validation: every actorprefix write must have exactly one matching digest entry.
        val actorWriteKeys = writes.filter { EntityKeys.isActorPrefixKey(it.first) }.map { EntityKeys.actorIdBytes(it.first) }
        val digestKeys = digpAdds.values.flatten()
        if (actorWriteKeys.size != list.size || digestKeys.size != list.size) {
            throw IllegalStateException("Entity placement validation failed; no entity writes were committed.")
        }
        if (actorWriteKeys.map { EntityKeys.actorIdBytesHex(it) }.toSet().size != actorWriteKeys.size) {
            throw IllegalStateException("Duplicate actor storage keys were generated; no entity writes were committed.")
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

    private fun freshActorId(bridge: LevelDbBridge, generatedStorageKeys: Set<String>): Long {
        repeat(128) {
            val rawActorId = ThreadLocalRandom.current().nextLong()
            val storageKey = EntityKeys.storageKeyBytes(rawActorId)
            val storageHex = EntityKeys.actorIdBytesHex(storageKey)
            if (storageHex in generatedStorageKeys) return@repeat
            if (bridge.read(EntityKeys.actorPrefixKey(storageKey)) == null) return rawActorId
        }
        throw IllegalStateException("Couldn't allocate a unique actor storage key for structure entity placement.")
    }
}
