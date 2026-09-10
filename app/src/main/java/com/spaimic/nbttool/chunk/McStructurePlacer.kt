package com.spaimic.nbttool.chunk

import com.spaimic.nbttool.nbt.NbtIO
import com.spaimic.nbttool.nbt.NbtTag
import com.spaimic.nbttool.shizuku.LevelDbBridge
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ThreadLocalRandom

/** MCStructure placement. Blocks are written to terrain and structure entities are
 * converted into modern Bedrock actorprefix/digp records. Block-position-data is
 * still reported but not written yet. */
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
        val entityWrites = placeEntities(bridge, structure.entities, structure.origin, originX, originY, originZ, dimension)
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
        structureOrigin: IntArray?,
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

            // Deep-copy the entity tree so placement never mutates the decoded
            // MCStructure analysis object. Do this in memory rather than serializing
            // and parsing the structure entity again: a malformed list declaration
            // in source data could otherwise turn a perfectly readable structure into
            // an EOFException during this preparation step.
            val root = deepCopyCompound(source)
            val sourceOrigin = structureOrigin ?: intArrayOf(0, 0, 0)
            require(sourceOrigin.size >= 3) { "MCStructure world-origin must contain X/Y/Z." }
            // Bedrock structure entity coordinates are saved in the source world
            // coordinate system. When loading a structure, the game translates them
            // by subtracting structure_world_origin and adding the placement origin.
            val worldX = x - sourceOrigin[0].toDouble() + ox
            val worldY = y - sourceOrigin[1].toDouble() + oy
            val worldZ = z - sourceOrigin[2].toDouble() + oz

            val rawActorId = freshActorId(bridge, generatedStorageKeys)
            val storageKey = EntityKeys.storageKeyBytes(rawActorId)
            val storageHex = EntityKeys.actorIdBytesHex(storageKey)
            if (!generatedStorageKeys.add(storageHex)) {
                throw IllegalStateException("Generated duplicate actor storage key for structure entity #$entityIndex.")
            }

            root.entries["Pos"] = NbtTag.ListTag(
                elementTypeId = 5,
                items = mutableListOf(
                    NbtTag.Float(worldX.toFloat()),
                    NbtTag.Float(worldY.toFloat()),
                    NbtTag.Float(worldZ.toFloat())
                )
            )
            // Keep the raw world actor IDs in the entity payload. The actorprefix key
            // and digp entry use the same storage ID, encoded little-endian.
            root.entries["UniqueID"] = NbtTag.Long(rawActorId)
            root.entries["ActorUniqueID"] = NbtTag.Long(rawActorId)

            // Preserve any internalComponents already present in the structure entity.
            // Structure exports can contain runtime-specific internal state, and replacing
            // the whole compound can silently remove fields Minecraft needs for some entity
            // types. Only replace/create the EntityStorageKeyComponent that identifies the
            // LevelDB actor record.
            val internalComponents = (root.entries["internalComponents"] as? NbtTag.Compound)
                ?: NbtTag.Compound(LinkedHashMap())
            val storageComponent = (internalComponents.entries["EntityStorageKeyComponent"] as? NbtTag.Compound)
                ?: NbtTag.Compound(LinkedHashMap())
            storageComponent.entries["StorageKey"] = NbtTag.Str("")
            internalComponents.entries["EntityStorageKeyComponent"] = storageComponent
            root.entries["internalComponents"] = internalComponents

            val actorKey = EntityKeys.actorPrefixKey(storageKey)
            if (bridge.read(actorKey) != null) {
                throw IllegalStateException("Generated actor storage key already exists for structure entity #$entityIndex.")
            }
            // Modern Bedrock ActorPrefix records are the entity compound itself.
            validateActorEntityInMemory(root, entityIndex)
            val actorNbt = try {
                NbtIO.writeActorPrefixCompound(root, storageKey)
            } catch (e: Exception) {
                throw IllegalStateException(
                    "Entity #$entityIndex could not be serialized: ${e.message ?: e.javaClass.simpleName}",
                    e
                )
            }
            if (actorNbt.isEmpty()) {
                throw IllegalStateException("Entity #$entityIndex produced empty actor-record NBT; placement was aborted.")
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

        // Every generated actor must be referenced exactly once by the digest for the
        // chunk containing its position. This catches cross-chunk bookkeeping mistakes
        // before the LevelDB batch is committed.
        val generatedHex = actorWriteKeys.map { EntityKeys.actorIdBytesHex(it) }.toSet()
        val referencedHex = digestKeys.map { EntityKeys.actorIdBytesHex(it) }
        if (referencedHex.toSet() != generatedHex || referencedHex.size != generatedHex.size) {
            throw IllegalStateException("Actor/digest mapping validation failed; no entity writes were committed.")
        }

        return writes
    }


    private fun validateActorEntityInMemory(root: NbtTag.Compound, entityIndex: Int) {
        val pos = root.entries["Pos"] as? NbtTag.ListTag
            ?: throw IllegalStateException("Entity #$entityIndex actor record is missing Pos; placement was aborted.")
        if (pos.elementTypeId != 5 || pos.items.size != 3 || pos.items.any { it !is NbtTag.Float }) {
            throw IllegalStateException("Entity #$entityIndex Pos must be a 3-element Float list; placement was aborted.")
        }
        if (root.entries["UniqueID"] !is NbtTag.Long || root.entries["ActorUniqueID"] !is NbtTag.Long) {
            throw IllegalStateException("Entity #$entityIndex requires Long UniqueID and ActorUniqueID; placement was aborted.")
        }
    }

    private fun deepCopyCompound(source: NbtTag.Compound): NbtTag.Compound {
        return NbtTag.Compound(LinkedHashMap<String, NbtTag>().also { out ->
            for ((name, tag) in source.entries) out[name] = deepCopyTag(tag)
        })
    }

    private fun deepCopyTag(tag: NbtTag): NbtTag = when (tag) {
        is NbtTag.End -> NbtTag.End
        is NbtTag.Byte -> NbtTag.Byte(tag.value)
        is NbtTag.Short -> NbtTag.Short(tag.value)
        is NbtTag.Int -> NbtTag.Int(tag.value)
        is NbtTag.Long -> NbtTag.Long(tag.value)
        is NbtTag.Float -> NbtTag.Float(tag.value)
        is NbtTag.Double -> NbtTag.Double(tag.value)
        is NbtTag.ByteArray -> NbtTag.ByteArray(tag.value.copyOf())
        is NbtTag.Str -> NbtTag.Str(tag.value)
        is NbtTag.ListTag -> NbtTag.ListTag(tag.elementTypeId, tag.items.mapTo(ArrayList()) { deepCopyTag(it) })
        is NbtTag.Compound -> deepCopyCompound(tag)
        is NbtTag.IntArray -> NbtTag.IntArray(tag.value.copyOf())
        is NbtTag.LongArray -> NbtTag.LongArray(tag.value.copyOf())
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
