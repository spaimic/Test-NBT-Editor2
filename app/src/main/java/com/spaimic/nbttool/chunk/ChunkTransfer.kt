package com.spaimic.nbttool.chunk

import com.spaimic.nbttool.nbt.NbtIO
import com.spaimic.nbttool.nbt.NbtTag
import com.spaimic.nbttool.shizuku.LevelDbBridge
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ThreadLocalRandom

/**
 * Reads and overwrites a literal Bedrock chunk by copying its raw LevelDB data.
 *
 * Build 87 makes the transfer dimension-aware. Nether/End chunk keys include the
 * dimension id, and entity (digp) keys do too. Older code accidentally defaulted
 * map copy/paste to the Overworld, which could write terrain records into the
 * wrong dimension and leave Minecraft with an invalid chunk.
 *
 * Actor IDs are world-global. A literal actor copy would duplicate IDs at the
 * destination, so copied entities are assigned fresh IDs and their common
 * UniqueID/ActorUniqueID NBT fields are rewritten. Block entities and terrain
 * records remain byte-for-byte copies.
 */
object ChunkTransfer {

    data class CopiedChunk(
        val chunkX: Int,
        val chunkZ: Int,
        val dimension: Int,
        val records: List<ChunkClipboard.Record>
    )

    private data class ActorCopy(val newId: ByteArray, val value: ByteArray)

    fun copy(bridge: LevelDbBridge, chunkX: Int, chunkZ: Int, dimension: Int = 0): CopiedChunk {
        val records = ArrayList<ChunkClipboard.Record>()
        val actorIds = LinkedHashMap<String, ByteArray>()

        bridge.forEachRawEntry { key, value ->
            val info = ChunkKeys.parse(key)
            if (info != null && info.chunkX == chunkX && info.chunkZ == chunkZ && info.dimension == dimension) {
                records += ChunkClipboard.Record(key.copyOf(), value.copyOf())
            } else if (EntityKeys.isDigpKeyForDimension(key, dimension)) {
                val (kx, kz) = EntityKeys.digpChunkXZ(key)
                if (kx == chunkX && kz == chunkZ) {
                    records += ChunkClipboard.Record(key.copyOf(), value.copyOf())
                    for (id in EntityKeys.splitActorIds(value)) {
                        actorIds[EntityKeys.actorIdHex(id)] = id
                    }
                }
            }
        }

        // Actor records are global. Capture only actors referenced by this chunk.
        for (id in actorIds.values) {
            val value = bridge.read(EntityKeys.actorPrefixKey(id)) ?: continue
            records += ChunkClipboard.Record(EntityKeys.actorPrefixKey(id), value.copyOf())
        }

        if (records.isEmpty()) {
            throw IllegalStateException("No chunk data found at ($chunkX, $chunkZ) in ${dimensionName(dimension)}")
        }

        return CopiedChunk(chunkX, chunkZ, dimension, records)
    }

    fun paste(
        bridge: LevelDbBridge,
        source: ChunkClipboard.Entry,
        targetX: Int,
        targetZ: Int,
        dimension: Int = source.dimension
    ): Int {
        require(source.dimension == dimension) {
            "Cannot paste a ${dimensionName(source.dimension)} chunk into ${dimensionName(dimension)}."
        }

        val destinationActorIds = LinkedHashMap<String, ByteArray>()
        val destinationKeys = ArrayList<ByteArray>()

        // Remove every chunk-local record in the selected dimension. Also remove
        // actor records referenced by the destination digp list.
        bridge.forEachRawEntry { key, value ->
            val info = ChunkKeys.parse(key)
            if (info != null && info.chunkX == targetX && info.chunkZ == targetZ && info.dimension == dimension) {
                destinationKeys += key.copyOf()
            } else if (EntityKeys.isDigpKeyForDimension(key, dimension)) {
                val (kx, kz) = EntityKeys.digpChunkXZ(key)
                if (kx == targetX && kz == targetZ) {
                    destinationKeys += key.copyOf()
                    for (id in EntityKeys.splitActorIds(value)) {
                        destinationActorIds[EntityKeys.actorIdHex(id)] = id
                    }
                }
            }
        }

        for (key in destinationKeys) bridge.delete(key)
        for (id in destinationActorIds.values) bridge.delete(EntityKeys.actorPrefixKey(id))

        // First write chunk terrain/block-entity records. These are copied literally,
        // but their first X/Z fields are rewritten to the destination.
        var written = 0
        val actorCopies = ArrayList<ActorCopy>()
        var sourceDigp: ByteArray? = null

        for (record in source.records) {
            val sourceInfo = ChunkKeys.parse(record.key)
            when {
                sourceInfo != null &&
                    sourceInfo.chunkX == source.chunkX &&
                    sourceInfo.chunkZ == source.chunkZ &&
                    sourceInfo.dimension == dimension -> {
                    val newKey = rewriteChunkKey(record.key, sourceInfo, targetX, targetZ)
                    bridge.write(newKey, record.value.copyOf())
                    written++
                }
                EntityKeys.isDigpKeyForDimension(record.key, dimension) -> {
                    sourceDigp = record.value.copyOf()
                }
                EntityKeys.isActorPrefixKey(record.key) -> {
                    val oldId = EntityKeys.actorIdBytes(record.key)
                    val newId = freshActorId(bridge)
                    val newValue = rewriteActorIds(record.value, oldId, newId) ?: continue
                    actorCopies += ActorCopy(newId, newValue)
                }
            }
        }

        // Rewrite the destination digp list to point at fresh actor IDs. This keeps
        // source entities alive while preventing duplicate global actor IDs.
        if (sourceDigp != null && actorCopies.isNotEmpty()) {
            val newDigp = ByteArray(actorCopies.size * 8)
            actorCopies.forEachIndexed { i, copy ->
                System.arraycopy(copy.newId, 0, newDigp, i * 8, 8)
                bridge.write(EntityKeys.actorPrefixKey(copy.newId), copy.value)
                written++
            }
            bridge.write(EntityKeys.digpKey(targetX, targetZ, dimension), newDigp)
            written++
        } else if (sourceDigp != null) {
            // Preserve an empty digp record if the source had one.
            bridge.write(EntityKeys.digpKey(targetX, targetZ, dimension), ByteArray(0))
            written++
        }

        return written
    }

    private fun rewriteActorIds(value: ByteArray, oldId: ByteArray, newId: ByteArray): ByteArray? {
        return try {
            val root = NbtIO.readRootCompoundNoHeader(value)
            rewriteActorIdTags(root, oldId, newId)
            NbtIO.writeRootCompoundNoHeader(root)
        } catch (_: Exception) {
            // Never create an actor record whose LevelDB key and NBT identity disagree.
            // Unparseable actors are omitted from the pasted chunk instead.
            null
        }
    }

    private fun rewriteActorIdTags(tag: NbtTag, oldId: ByteArray, newId: ByteArray) {
        when (tag) {
            is NbtTag.Compound -> {
                for ((name, child) in tag.entries) {
                    if ((name == "UniqueID" || name == "ActorUniqueID") && child is NbtTag.Long) {
                        tag.entries[name] = NbtTag.Long(bytesToLongLE(newId))
                    } else {
                        rewriteActorIdTags(child, oldId, newId)
                    }
                }
            }
            is NbtTag.ListTag -> tag.items.forEach { rewriteActorIdTags(it, oldId, newId) }
            else -> Unit
        }
    }

    private fun freshActorId(bridge: LevelDbBridge): ByteArray {
        repeat(32) {
            val value = ThreadLocalRandom.current().nextLong()
            val id = longToBytesLE(value)
            if (bridge.read(EntityKeys.actorPrefixKey(id)) == null) return id
        }
        throw IllegalStateException("Could not allocate a unique entity ID for the pasted chunk")
    }

    private fun longToBytesLE(value: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()

    private fun bytesToLongLE(value: ByteArray): Long =
        ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN).long

    private fun rewriteChunkKey(key: ByteArray, info: ChunkKeys.Info, targetX: Int, targetZ: Int): ByteArray {
        val out = key.copyOf()
        writeI32LE(out, 0, targetX)
        writeI32LE(out, 4, targetZ)
        return out
    }

    private fun writeI32LE(out: ByteArray, off: Int, value: Int) {
        out[off] = (value and 0xFF).toByte()
        out[off + 1] = ((value ushr 8) and 0xFF).toByte()
        out[off + 2] = ((value ushr 16) and 0xFF).toByte()
        out[off + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun dimensionName(dimension: Int) = when (dimension) {
        1 -> "Nether"
        2 -> "The End"
        else -> "Overworld"
    }
}
