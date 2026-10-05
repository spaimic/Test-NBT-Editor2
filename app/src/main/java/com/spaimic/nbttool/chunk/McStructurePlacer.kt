package com.spaimic.nbttool.chunk

import com.spaimic.nbttool.nbt.NbtIO
import com.spaimic.nbttool.nbt.NbtTag
import com.spaimic.nbttool.shizuku.LevelDbBridge
import java.util.concurrent.ThreadLocalRandom

/** MCStructure placement. Blocks are written to terrain, structure entities are
 * converted into modern Bedrock actorprefix/digp records, and block-position-data
 * block entities are materialized into destination BlockEntity records. */
object McStructurePlacer {
    private const val MCSTRUCTURE_BATCH_MAX_BYTES = 64 * 1024

    data class Result(
        val blocksPlaced: Int,
        val chunksChanged: Int,
        val subchunksChanged: Int,
        val entitiesPlaced: Int,
        val blockEntitiesPending: Int,
        val writeBatchCount: Long,
        val estimatedWriteBytes: Long,
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

        // Block entities are stored separately from terrain and modern actors.  Build 228
        // only reported block_position_data; Build 229 materializes its block_entity_data
        // into the destination chunk's BlockEntity key.
        val blockEntityWrites = placeBlockEntities(
            bridge, structure, originX, originY, originZ, dimension
        )
        writes.addAll(blockEntityWrites.first)

        // Large MCStructure placements can expand into many actor, digest, terrain, and
        // block-entity records. Keep entries ordered, but commit them in small 64 KiB
        // WriteBatch operations instead of one multi-hundred-KiB/megabyte batch.
        val writeStats = bridge.writeBatchChunked(writes, MCSTRUCTURE_BATCH_MAX_BYTES)

        // Count the actual actor records we queued, not the number of LevelDB keys.
        val entitiesPlaced = entityWrites.count { EntityKeys.isActorPrefixKey(it.first) }
        val blockEntitiesPlaced = blockEntityWrites.second

        // Verify that every actor record and its digest are readable from the same DB
        // immediately after the atomic commit. A successful verification means the app
        // really wrote the expected modern actor records; Minecraft loading is the final
        // external compatibility check.
        verifyEntityWrites(bridge, entityWrites, dimension, entitiesPlaced)

        return Result(
            blocksPlaced = structure.blocks.size,
            chunksChanged = grouped.size,
            subchunksChanged = subchunksChanged,
            entitiesPlaced = entitiesPlaced,
            blockEntitiesPending = blockEntitiesPlaced,
            writeBatchCount = writeStats.batchCount,
            estimatedWriteBytes = writeStats.estimatedBytes,
            backupPath = backupPath
        )
    }

    private fun verifyEntityWrites(
        bridge: LevelDbBridge,
        entityWrites: List<Pair<ByteArray, ByteArray>>,
        dimension: Int,
        expectedEntities: Int
    ) {
        val actorWrites = entityWrites.filter { EntityKeys.isActorPrefixKey(it.first) }
        if (actorWrites.size != expectedEntities) {
            throw IllegalStateException(
                "Entity verification count mismatch: expected $expectedEntities actor keys, found ${actorWrites.size}."
            )
        }

        val actorIdsByChunk = LinkedHashMap<Pair<Int, Int>, MutableList<ByteArray>>()
        for ((key, value) in actorWrites) {
            val stored = bridge.read(key)
                ?: throw IllegalStateException("Entity verification failed: actorprefix record was not readable after write.")
            if (!stored.contentEquals(value)) {
                throw IllegalStateException("Entity verification failed: actorprefix record changed after write.")
            }
            val root = try { NbtIO.readRootCompoundNoHeader(stored) } catch (e: Exception) {
                throw IllegalStateException(
                    "Entity verification failed: actorprefix NBT could not be decoded after write: ${e.message ?: e.javaClass.simpleName}",
                    e
                )
            }
            if (root.entries["UniqueID"] !is NbtTag.Long || root.entries["identifier"] !is NbtTag.Str) {
                throw IllegalStateException("Entity verification failed: actor record is missing UniqueID or identifier.")
            }
            val storage = (((root.entries["internalComponents"] as? NbtTag.Compound)
                ?.entries?.get("EntityStorageKeyComponent") as? NbtTag.Compound)
                ?.entries?.get("StorageKey") as? NbtTag.Str)
            if (storage == null) {
                throw IllegalStateException("Entity verification failed: StorageKey component is missing.")
            }
            // Recover chunk association from the entity position so the digest check
            // is tied to the same placement chunk that Minecraft will load.
            val pos = root.entries["Pos"] as? NbtTag.ListTag
            val px = numeric(pos?.items?.getOrNull(0))
            val pz = numeric(pos?.items?.getOrNull(2))
            if (px == null || pz == null) {
                throw IllegalStateException("Entity verification failed: actor Pos is invalid.")
            }
            val cx = Math.floorDiv(Math.floor(px).toInt(), 16)
            val cz = Math.floorDiv(Math.floor(pz).toInt(), 16)
            actorIdsByChunk.getOrPut(cx to cz) { ArrayList() }.add(EntityKeys.actorIdBytes(key))
        }

        var digestCount = 0
        for ((chunk, actorIds) in actorIdsByChunk) {
            val digest = bridge.read(EntityKeys.digpKey(chunk.first, chunk.second, dimension))
                ?: throw IllegalStateException("Entity verification failed: missing digp for chunk ${chunk.first},${chunk.second}.")
            if (digest.size % 8 != 0) {
                throw IllegalStateException("Entity verification failed: malformed digp for chunk ${chunk.first},${chunk.second}.")
            }
            val digestIds = EntityKeys.splitActorIds(digest)
            val digestHex = digestIds.map { EntityKeys.actorIdBytesHex(it) }
            if (digestHex.size != digestHex.toSet().size) {
                throw IllegalStateException("Entity verification failed: digp for chunk ${chunk.first},${chunk.second} contains duplicate actor IDs.")
            }
            for (id in actorIds) {
                if (EntityKeys.actorIdBytesHex(id) !in digestHex) {
                    throw IllegalStateException("Entity verification failed: actorprefix ID is missing from digp for chunk ${chunk.first},${chunk.second}.")
                }
                digestCount++
            }
        }
        if (digestCount != expectedEntities) {
            throw IllegalStateException("Entity verification failed: digest contains $digestCount placed actors, expected $expectedEntities.")
        }
    }

    private fun placeBlockEntities(
        bridge: LevelDbBridge,
        structure: com.spaimic.nbttool.ui.McStructureAnalysis,
        ox: Int, oy: Int, oz: Int,
        dimension: Int
    ): Pair<List<Pair<ByteArray, ByteArray>>, Int> {
        val data = structure.blockPositionData ?: return emptyList<Pair<ByteArray, ByteArray>>() to 0
        if (data.entries.isEmpty()) return emptyList<Pair<ByteArray, ByteArray>>() to 0

        val sx = structure.sizeX
        val sy = structure.sizeY
        val sz = structure.sizeZ
        require(sx > 0 && sy > 0 && sz > 0) { "MCStructure dimensions are invalid for block-entity placement." }
        val sourceOrigin = structure.origin ?: intArrayOf(0, 0, 0)
        require(sourceOrigin.size >= 3) { "MCStructure world-origin must contain X/Y/Z." }

        val byChunk = LinkedHashMap<Pair<Int, Int>, MutableList<NbtTag.Compound>>()
        var placed = 0
        for ((indexText, rawData) in data.entries) {
            val index = indexText.toIntOrNull()
                ?: throw IllegalStateException("Invalid block_position_data index '$indexText'.")
            if (index < 0 || index >= sx * sy * sz) {
                throw IllegalStateException("block_position_data index $index is outside the structure volume.")
            }
            val wrapper = rawData as? NbtTag.Compound
                ?: throw IllegalStateException("block_position_data[$indexText] is not a compound.")
            val sourceEntity = wrapper.entries["block_entity_data"] as? NbtTag.Compound
                ?: continue

            val x = index / (sy * sz)
            val rem = index % (sy * sz)
            val y = rem / sz
            val z = rem % sz
            val worldX = ox + x - sourceOrigin[0]
            val worldY = oy + y - sourceOrigin[1]
            val worldZ = oz + z - sourceOrigin[2]
            val chunk = Math.floorDiv(worldX, 16) to Math.floorDiv(worldZ, 16)

            val entity = deepCopyCompound(sourceEntity)
            entity.entries["x"] = NbtTag.Int(worldX)
            entity.entries["y"] = NbtTag.Int(worldY)
            entity.entries["z"] = NbtTag.Int(worldZ)
            byChunk.getOrPut(chunk) { ArrayList() }.add(entity)
            placed++
        }

        if (byChunk.isEmpty()) return emptyList<Pair<ByteArray, ByteArray>>() to 0
        val writes = ArrayList<Pair<ByteArray, ByteArray>>()
        for ((chunk, additions) in byChunk) {
            val key = ChunkKeys.blockEntityKey(chunk.first, chunk.second, dimension)
            val existingBytes = bridge.read(key)
            val existing = if (existingBytes == null || existingBytes.isEmpty()) {
                ArrayList()
            } else {
                try {
                    ArrayList(NbtIO.readConcatenatedCompounds(existingBytes))
                } catch (e: Throwable) {
                    throw IllegalStateException(
                        "Existing BlockEntity key for chunk (${chunk.first},${chunk.second}) is malformed; placement was aborted.",
                        e
                    )
                }
            }

            // Replace an existing block entity at the same absolute coordinate rather
            // than creating duplicate block actors for one block.
            for (addition in additions) {
                val ax = (addition.entries["x"] as? NbtTag.Int)?.value
                val ay = (addition.entries["y"] as? NbtTag.Int)?.value
                val az = (addition.entries["z"] as? NbtTag.Int)?.value
                existing.removeAll { old ->
                    (old.entries["x"] as? NbtTag.Int)?.value == ax &&
                        (old.entries["y"] as? NbtTag.Int)?.value == ay &&
                        (old.entries["z"] as? NbtTag.Int)?.value == az
                }
                existing.add(addition)
            }
            writes += key to NbtIO.writeConcatenatedCompounds(existing)
        }
        return writes to placed
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
        val generatedUniqueIds = HashSet<Long>()
        var entityIndex = 0

        // Bedrock's modern actor storage keeps a world-global UniqueID for each actor.
        // Build 224 derives one Bedrock storage key from each fresh world actor UID.
        // The exact same 8-byte storage-key bytes are used for actorprefix, digp, and
        // EntityStorageKeyComponent.StorageKey so the three identities cannot drift apart.
        val sourceEntities = list.mapIndexed { idx, tag ->
            val source = tag as? NbtTag.Compound
                ?: throw IllegalStateException("Structure entity #${idx + 1} is not a compound NBT tag.")
            source
        }
        val newUniqueIds = LongArray(sourceEntities.size)
        val oldToNewUniqueIds = LinkedHashMap<Long, Long>()
        for (i in sourceEntities.indices) {
            val source = sourceEntities[i]
            val raw = source.entries["UniqueID"] as? NbtTag.Long
            if (raw != null && oldToNewUniqueIds.containsKey(raw.value)) {
                throw IllegalStateException("Structure contains duplicate source UniqueID ${raw.value}; entity references would be ambiguous, so placement was aborted.")
            }

            val uniqueId = freshUniqueId(bridge, generatedUniqueIds)
            newUniqueIds[i] = uniqueId
            generatedUniqueIds += uniqueId

            val storageKey = EntityKeys.storageKeyBytes(uniqueId)
            generatedStorageKeys += EntityKeys.actorIdBytesHex(storageKey)

            if (raw != null) oldToNewUniqueIds[raw.value] = uniqueId
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
            remapActorReferences(root, oldToNewUniqueIds)

            val sourceOrigin = structureOrigin ?: intArrayOf(0, 0, 0)
            require(sourceOrigin.size >= 3) { "MCStructure world-origin must contain X/Y/Z." }
            val worldX = x - sourceOrigin[0].toDouble() + ox
            val worldY = y - sourceOrigin[1].toDouble() + oy
            val worldZ = z - sourceOrigin[2].toDouble() + oz

            val uniqueId = newUniqueIds[i]
            val storageKey = EntityKeys.storageKeyBytes(uniqueId)
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
            root.entries["UniqueID"] = NbtTag.Long(uniqueId)
            // ActorUniqueID is an actor-storage implementation concept represented by the
            // actorprefix key/storage key in modern worlds. Do not invent a duplicate NBT field;
            // normal actor records inspected from worlds do not require it.
            root.entries.remove("ActorUniqueID")

            val internalComponents = (root.entries["internalComponents"] as? NbtTag.Compound)
                ?: NbtTag.Compound(LinkedHashMap())
            val storageComponent = (internalComponents.entries["EntityStorageKeyComponent"] as? NbtTag.Compound)
                ?: NbtTag.Compound(LinkedHashMap())
            // NbtIO replaces this placeholder String with the exact binary StorageKey payload.
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
                NbtIO.writeActorPrefixCompound(root, EntityKeys.storageKeyComponentBytes(uniqueId))
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
        val actorUniqueIds = newUniqueIds.toSet()
        if (actorUniqueIds.size != newUniqueIds.size || actorUniqueIds.contains(-1L)) {
            throw IllegalStateException("Entity UniqueID validation failed; no entity writes were committed.")
        }
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
            is NbtTag.Compound -> {
                val names = tag.entries.keys.toList()
                for (name in names) {
                    val child = tag.entries[name] ?: continue
                    val replacement = when (child) {
                        is NbtTag.Long -> mapping[child.value]?.let(NbtTag::Long)
                        else -> null
                    }
                    if (replacement != null) {
                        tag.entries[name] = replacement
                    } else {
                        remapActorReferences(child, mapping)
                    }
                }
            }
            is NbtTag.ListTag -> {
                for (i in tag.items.indices) {
                    val child = tag.items[i]
                    val replacement = when (child) {
                        is NbtTag.Long -> mapping[child.value]?.let(NbtTag::Long)
                        else -> null
                    }
                    if (replacement != null) {
                        tag.items[i] = replacement
                    } else {
                        remapActorReferences(child, mapping)
                    }
                }
            }
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
            // Positive IDs are sufficient here; storageKeyBytes() performs the exact
            // Bedrock storage-key derivation and keeps all representations consistent.
            val candidate = ThreadLocalRandom.current().nextLong(1L, Int.MAX_VALUE.toLong() + 1L)
            if (candidate in generatedUniqueIds) return@repeat
            val storageKey = EntityKeys.storageKeyBytes(candidate)
            if (bridge.read(EntityKeys.actorPrefixKey(storageKey)) == null) return candidate
        }
        throw IllegalStateException("Couldn't allocate a unique entity UniqueID for structure entity placement.")
    }

}
