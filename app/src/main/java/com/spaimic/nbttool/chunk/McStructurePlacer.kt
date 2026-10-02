package com.spaimic.nbttool.chunk

import com.spaimic.nbttool.nbt.NbtIO
import com.spaimic.nbttool.nbt.NbtTag
import com.spaimic.nbttool.shizuku.LevelDbBridge
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

    /**
     * Places structure entities using Bedrock's legacy per-chunk Entity records.
     *
     * Build 226 intentionally avoids the modern actorprefix/digp path here. The modern
     * format has strict storage-key bookkeeping and the previous builds produced records
     * that were visible to the editor but not loaded by Minecraft. Bedrock tooling documents
     * that current worlds still load the legacy Entity key alongside modern actor storage.
     * Each Entity value is a concatenation of little-endian NBT root compounds.
     */
    private fun placeEntities(
        bridge: LevelDbBridge,
        entities: NbtTag.ListTag?,
        structureOrigin: IntArray?,
        ox: Int, oy: Int, oz: Int,
        dimension: Int
    ): List<Pair<ByteArray, ByteArray>> {
        val list = entities?.items.orEmpty()
        if (list.isEmpty()) return emptyList()

        val sourceEntities = list.mapIndexed { idx, tag ->
            tag as? NbtTag.Compound
                ?: throw IllegalStateException("Structure entity #${idx + 1} is not a compound NBT tag.")
        }
        val generatedUniqueIds = HashSet<Long>()
        val oldToNewUniqueIds = LinkedHashMap<Long, Long>()
        val newUniqueIds = LongArray(sourceEntities.size)

        // Give placed actors fresh world-local IDs and remap references between entities.
        for (i in sourceEntities.indices) {
            val source = sourceEntities[i]
            val raw = source.entries["UniqueID"] as? NbtTag.Long
            if (raw != null && oldToNewUniqueIds.containsKey(raw.value)) {
                throw IllegalStateException(
                    "Structure contains duplicate source UniqueID ${raw.value}; entity references would be ambiguous, so placement was aborted."
                )
            }
            val uniqueId = freshUniqueId(bridge, generatedUniqueIds)
            generatedUniqueIds += uniqueId
            newUniqueIds[i] = uniqueId
            if (raw != null) oldToNewUniqueIds[raw.value] = uniqueId
        }

        val byChunk = LinkedHashMap<Pair<Int, Int>, MutableList<ByteArray>>()
        val sourceOrigin = structureOrigin ?: intArrayOf(0, 0, 0)
        require(sourceOrigin.size >= 3) { "MCStructure world-origin must contain X/Y/Z." }

        for (i in sourceEntities.indices) {
            val entityIndex = i + 1
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
            remapActorReferences(root, oldToNewUniqueIds)

            val worldX = x - sourceOrigin[0].toDouble() + ox
            val worldY = y - sourceOrigin[1].toDouble() + oy
            val worldZ = z - sourceOrigin[2].toDouble() + oz
            root.entries["Pos"] = NbtTag.ListTag(
                elementTypeId = 5,
                items = mutableListOf(
                    NbtTag.Float(worldX.toFloat()),
                    NbtTag.Float(worldY.toFloat()),
                    NbtTag.Float(worldZ.toFloat())
                )
            )
            root.entries["UniqueID"] = NbtTag.Long(newUniqueIds[i])

            // Legacy Entity records do not use the modern actor storage-key component.
            val internal = root.entries["internalComponents"] as? NbtTag.Compound
            internal?.entries?.remove("EntityStorageKeyComponent")
            if (internal != null && internal.entries.isEmpty()) root.entries.remove("internalComponents")

            normalizeActorBaseFields(root, entityIndex)
            validateActorEntityInMemory(root, entityIndex)
            val actorNbt = try {
                NbtIO.writeRootCompoundNoHeader(root)
            } catch (e: Exception) {
                throw IllegalStateException(
                    "Entity #$entityIndex could not be serialized: ${e.message ?: e.javaClass.simpleName}",
                    e
                )
            }
            if (actorNbt.isEmpty()) {
                throw IllegalStateException("Entity #$entityIndex produced empty entity NBT; placement was aborted.")
            }

            val cx = Math.floorDiv(Math.floor(worldX).toInt(), 16)
            val cz = Math.floorDiv(Math.floor(worldZ).toInt(), 16)
            byChunk.getOrPut(cx to cz) { ArrayList() }.add(actorNbt)
        }

        val writes = ArrayList<Pair<ByteArray, ByteArray>>()
        var placedCount = 0
        for ((chunk, actorRecords) in byChunk) {
            val key = ChunkKeys.entityKey(chunk.first, chunk.second, dimension)
            val existing = bridge.read(key) ?: ByteArray(0)
            // Entity records are concatenated root compounds. Preserve existing records and append ours.
            val out = ByteArray(existing.size + actorRecords.sumOf { it.size })
            existing.copyInto(out)
            var offset = existing.size
            for (record in actorRecords) {
                record.copyInto(out, offset)
                offset += record.size
                placedCount++
            }
            writes += key to out
        }

        if (placedCount != sourceEntities.size) {
            throw IllegalStateException(
                "Entity placement validation failed: wrote $placedCount of ${sourceEntities.size} entities."
            )
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

    private fun freshUniqueId(bridge: LevelDbBridge, generatedUniqueIds: Set<Long>): Long {
        repeat(512) {
            // Keep the generated IDs positive and within 31 bits. This makes the
            // actor storage-key derivation unambiguous across current Bedrock tooling.
            val candidate = ThreadLocalRandom.current().nextLong(1L, Int.MAX_VALUE.toLong() + 1L)
            if (candidate in generatedUniqueIds) return@repeat
            val storageKey = EntityKeys.storageKeyBytes(candidate)
            if (bridge.read(EntityKeys.actorPrefixKey(storageKey)) == null) return candidate
        }
        throw IllegalStateException("Couldn't allocate a unique entity UniqueID for structure entity placement.")
    }

}
