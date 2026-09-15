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

        // Allocate all new actor IDs first and remember the source UniqueID -> new ID mapping.
        // MCStructure files containing many related entities can contain references between
        // actors (RiderID/RideID/OwnerNew/etc.). Those references must be rewritten when the
        // actors receive fresh world IDs, otherwise Minecraft can encounter references to actors
        // that do not exist in the destination world.
        val sourceEntities = list.mapIndexed { idx, tag ->
            val source = tag as? NbtTag.Compound
                ?: throw IllegalStateException("Structure entity #${idx + 1} is not a compound NBT tag.")
            source
        }
        val newActorIds = LongArray(sourceEntities.size)
        val oldToNewActorIds = LinkedHashMap<Long, Long>()
        for (i in sourceEntities.indices) {
            val source = sourceEntities[i]
            val raw = source.entries["UniqueID"] as? NbtTag.Long
            if (raw != null && oldToNewActorIds.containsKey(raw.value)) {
                throw IllegalStateException("Structure contains duplicate source UniqueID ${raw.value}; entity references would be ambiguous, so placement was aborted.")
            }
            val fresh = freshActorId(bridge, generatedStorageKeys)
            newActorIds[i] = fresh
            // Record the newly allocated storage key immediately. Build 219 accidentally
            // passed the bookkeeping set as read-only and never inserted the generated key,
            // causing every entity to fail the later consistency check even though the key
            // itself was valid and collision-free.
            generatedStorageKeys += EntityKeys.actorIdBytesHex(EntityKeys.storageKeyBytes(fresh))
            if (raw != null) oldToNewActorIds[raw.value] = fresh
        }

        for (i in sourceEntities.indices) {
            entityIndex = i + 1
            val source = sourceEntities[i]
            val pos = source.entries["Pos"] as? NbtTag.ListTag
                ?: throw IllegalStateException("Structure entity #$entityIndex is missing a valid Pos list.")
            val x = numeric(pos.items.getOrNull(0))
                ?: throw IllegalStateException("Structure entity #$entityIndex has an invalid Pos X value.")
            val y = numeric(pos.items.getOrNull(1))
                ?: throw IllegalStateException("Structure entity #$entityIndex has an invalid Pos Y value.")
            val z = numeric(pos.items.getOrNull(2))
                ?: throw IllegalStateException("Structure entity #$entityIndex has an invalid Pos Z value.")

            val root = deepCopyCompound(source)
            // Rewrite any entity-to-entity Long references before setting this actor's own ID.
            // This intentionally remaps only values that exactly match a source entity UniqueID,
            // leaving unrelated numeric NBT fields unchanged.
            remapActorReferences(root, oldToNewActorIds)

            val sourceOrigin = structureOrigin ?: intArrayOf(0, 0, 0)
            require(sourceOrigin.size >= 3) { "MCStructure world-origin must contain X/Y/Z." }
            val worldX = x - sourceOrigin[0].toDouble() + ox
            val worldY = y - sourceOrigin[1].toDouble() + oy
            val worldZ = z - sourceOrigin[2].toDouble() + oz

            val rawActorId = newActorIds[i]
            val storageKey = EntityKeys.storageKeyBytes(rawActorId)
            val storageHex = EntityKeys.actorIdBytesHex(storageKey)
            if (!generatedStorageKeys.contains(storageHex)) {
                throw IllegalStateException("Generated actor storage key bookkeeping failed for structure entity #$entityIndex.")
            }

            root.entries["Pos"] = NbtTag.ListTag(
                elementTypeId = 5,
                items = mutableListOf(
                    NbtTag.Float(worldX.toFloat()),
                    NbtTag.Float(worldY.toFloat()),
                    NbtTag.Float(worldZ.toFloat())
                )
            )
            root.entries["UniqueID"] = NbtTag.Long(rawActorId)
            // ActorUniqueID is an actor-storage implementation concept represented by the
            // actorprefix key/storage key in modern worlds. Do not invent a duplicate NBT field;
            // normal actor records inspected from worlds do not require it.
            root.entries.remove("ActorUniqueID")

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

            normalizeActorBaseFields(root, entityIndex)
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
            val existingHex = EntityKeys.splitActorIds(existing).map { EntityKeys.actorIdBytesHex(it) }
            val newHex = storageKeys.map { EntityKeys.actorIdBytesHex(it) }
            if (newHex.size != newHex.toSet().size) {
                throw IllegalStateException("Duplicate actor IDs were generated for chunk (${chunk.first},${chunk.second}); placement was aborted.")
            }
            if (newHex.any { it in existingHex.toSet() }) {
                throw IllegalStateException("Generated actor ID collides with an existing actor digest entry; placement was aborted.")
            }
            val out = ByteArray(existing.size + storageKeys.size * 8)
            existing.copyInto(out)
            storageKeys.forEachIndexed { i, key -> key.copyInto(out, existing.size + i * 8) }
            writes += EntityKeys.digpKey(chunk.first, chunk.second, dimension) to out

            val digestVersionKey = ChunkKeys.actorDigestVersionKey(chunk.first, chunk.second, dimension)
            if (bridge.read(digestVersionKey) == null) {
                writes += digestVersionKey to byteArrayOf(0)
            }
        }

        val actorWriteKeys = writes.filter { EntityKeys.isActorPrefixKey(it.first) }.map { EntityKeys.actorIdBytes(it.first) }
        val digestKeys = digpAdds.values.flatten()
        if (actorWriteKeys.size != list.size || digestKeys.size != list.size) {
            throw IllegalStateException("Entity placement validation failed; no entity writes were committed.")
        }
        if (actorWriteKeys.map { EntityKeys.actorIdBytesHex(it) }.toSet().size != actorWriteKeys.size) {
            throw IllegalStateException("Duplicate actor storage keys were generated; no entity writes were committed.")
        }
        val generatedHex = actorWriteKeys.map { EntityKeys.actorIdBytesHex(it) }.toSet()
        val referencedHex = digestKeys.map { EntityKeys.actorIdBytesHex(it) }
        if (referencedHex.toSet() != generatedHex || referencedHex.size != generatedHex.size) {
            throw IllegalStateException("Actor/digest mapping validation failed; no entity writes were committed.")
        }

        return writes
    }

    /** Recursively rewrites Long/LongArray values that exactly match source entity IDs. */
    private fun remapActorReferences(tag: NbtTag, mapping: Map<Long, Long>) {
        when (tag) {
            is NbtTag.Compound -> tag.entries.values.forEach { remapActorReferences(it, mapping) }
            is NbtTag.ListTag -> tag.items.forEach { remapActorReferences(it, mapping) }
            is NbtTag.Long -> Unit
            is NbtTag.LongArray -> {
                for (i in tag.value.indices) mapping[tag.value[i]]?.let { tag.value[i] = it }
            }
            else -> Unit
        }
    }

    private fun normalizeActorBaseFields(root: NbtTag.Compound, entityIndex: Int) {
        require(root.entries["identifier"] is NbtTag.Str) {
            "Entity #$entityIndex is missing the required identifier; placement was aborted."
        }

        fun byteField(name: String) {
            if (root.entries[name] == null) root.entries[name] = NbtTag.Byte(0)
            require(root.entries[name] is NbtTag.Byte) {
                "Entity #$entityIndex has an invalid $name field; placement was aborted."
            }
        }
        fun intField(name: String) {
            if (root.entries[name] == null) root.entries[name] = NbtTag.Int(0)
            require(root.entries[name] is NbtTag.Int) {
                "Entity #$entityIndex has an invalid $name field; placement was aborted."
            }
        }
        fun floatField(name: String) {
            if (root.entries[name] == null) root.entries[name] = NbtTag.Float(0f)
            require(root.entries[name] is NbtTag.Float) {
                "Entity #$entityIndex has an invalid $name field; placement was aborted."
            }
        }

        // Required base ActorPrefix fields documented by current Bedrock tooling.
        listOf(
            "Chested", "Color", "Color2", "Invulnerable", "IsAngry",
            "IsAutonomous", "IsBaby", "IsEating", "IsGliding", "IsGlobal",
            "IsIllagerCaptain", "IsOrphaned", "IsOutOfControl", "IsRoaring",
            "IsScared", "IsStunned", "IsSwimming", "IsTamed", "IsTrusting",
            "LootDropped", "OnGround", "Saddled", "Sheared", "ShowBottom",
            "Sitting"
        ).forEach(::byteField)

        floatField("FallDistance")
        intField("MarkVariant")
        intField("PortalCooldown")
        intField("SkinID")
        intField("Strength")
        intField("StrengthMax")
        intField("Variant")

        if (root.entries["OwnerNew"] == null) root.entries["OwnerNew"] = NbtTag.Long(-1L)
        require(root.entries["OwnerNew"] is NbtTag.Long) {
            "Entity #$entityIndex has an invalid OwnerNew field; placement was aborted."
        }

        if (root.entries["Rotation"] == null) {
            root.entries["Rotation"] = NbtTag.ListTag(
                elementTypeId = 5,
                items = mutableListOf(NbtTag.Float(0f), NbtTag.Float(0f))
            )
        }
        val rotation = root.entries["Rotation"] as? NbtTag.ListTag
            ?: throw IllegalStateException("Entity #$entityIndex has an invalid Rotation field; placement was aborted.")
        require(rotation.elementTypeId == 5 && rotation.items.size == 2 && rotation.items.all { it is NbtTag.Float }) {
            "Entity #$entityIndex Rotation must be a 2-element Float list; placement was aborted."
        }
    }

    private fun validateActorEntityInMemory(root: NbtTag.Compound, entityIndex: Int) {
        val pos = root.entries["Pos"] as? NbtTag.ListTag
            ?: throw IllegalStateException("Entity #$entityIndex actor record is missing Pos; placement was aborted.")
        if (pos.elementTypeId != 5 || pos.items.size != 3 || pos.items.any { it !is NbtTag.Float }) {
            throw IllegalStateException("Entity #$entityIndex Pos must be a 3-element Float list; placement was aborted.")
        }
        if (root.entries["UniqueID"] !is NbtTag.Long) {
            throw IllegalStateException("Entity #$entityIndex requires a Long UniqueID; placement was aborted.")
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
