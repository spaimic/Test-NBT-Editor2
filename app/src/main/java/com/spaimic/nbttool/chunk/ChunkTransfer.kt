package com.spaimic.nbttool.chunk

import com.spaimic.nbttool.shizuku.LevelDbBridge

/**
 * Reads and overwrites a literal Bedrock chunk by copying its raw LevelDB data.
 *
 * This intentionally does not reconstruct NBT or terrain. The chunk's original
 * bytes are preserved, making the operation suitable for moving unusual or
 * unsupported block data that the map renderer doesn't understand.
 */
object ChunkTransfer {

    data class CopiedChunk(
        val chunkX: Int,
        val chunkZ: Int,
        val records: List<ChunkClipboard.Record>
    )

    fun copy(bridge: LevelDbBridge, chunkX: Int, chunkZ: Int, dimension: Int = 0): CopiedChunk {
        val records = ArrayList<ChunkClipboard.Record>()
        val actorIds = LinkedHashSet<String>()
        val actorIdBytes = LinkedHashMap<String, ByteArray>()

        bridge.forEachRawEntry { key, value ->
            val info = ChunkKeys.parse(key)
            if (info != null &&
                info.chunkX == chunkX &&
                info.chunkZ == chunkZ &&
                info.dimension == dimension
            ) {
                records += ChunkClipboard.Record(key.copyOf(), value.copyOf())
            } else if (dimension == 0 && EntityKeys.isOverworldDigpKey(key)) {
                val (kx, kz) = EntityKeys.digpChunkXZ(key)
                if (kx == chunkX && kz == chunkZ) {
                    records += ChunkClipboard.Record(key.copyOf(), value.copyOf())
                    for (id in EntityKeys.splitActorIds(value)) {
                        val hex = id.joinToString("") { "%02x".format(it) }
                        actorIds += hex
                        actorIdBytes[hex] = id
                    }
                }
            }
        }

        // Actor records are global keys, so collect only the actors listed by this
        // chunk's digp record.
        for (id in actorIdBytes.values) {
            val key = EntityKeys.actorPrefixKey(id)
            val value = bridge.read(key) ?: continue
            records += ChunkClipboard.Record(key, value.copyOf())
        }

        if (records.isEmpty()) {
            throw IllegalStateException("No chunk data found at ($chunkX, $chunkZ)")
        }

        return CopiedChunk(chunkX, chunkZ, records)
    }

    fun paste(
        bridge: LevelDbBridge,
        source: ChunkClipboard.Entry,
        targetX: Int,
        targetZ: Int,
        dimension: Int = 0
    ): Int {
        // Remove all existing raw chunk records at the destination first.
        val destinationActorIds = LinkedHashMap<String, ByteArray>()
        val destinationKeys = ArrayList<ByteArray>()

        bridge.forEachRawEntry { key, value ->
            val info = ChunkKeys.parse(key)
            if (info != null &&
                info.chunkX == targetX &&
                info.chunkZ == targetZ &&
                info.dimension == dimension
            ) {
                destinationKeys += key.copyOf()
            } else if (dimension == 0 && EntityKeys.isOverworldDigpKey(key)) {
                val (kx, kz) = EntityKeys.digpChunkXZ(key)
                if (kx == targetX && kz == targetZ) {
                    destinationKeys += key.copyOf()
                    for (id in EntityKeys.splitActorIds(value)) {
                        destinationActorIds[id.joinToString("") { "%02x".format(it) }] = id
                    }
                }
            }
        }

        for (key in destinationKeys) bridge.delete(key)
        for (id in destinationActorIds.values) bridge.delete(EntityKeys.actorPrefixKey(id))

        // Re-key chunk-coordinate records to the destination coordinates.
        var written = 0
        for (record in source.records) {
            val sourceInfo = ChunkKeys.parse(record.key)
            if (sourceInfo != null &&
                sourceInfo.chunkX == source.chunkX &&
                sourceInfo.chunkZ == source.chunkZ &&
                sourceInfo.dimension == dimension
            ) {
                val newKey = when {
                    sourceInfo.tag == ChunkKeys.TAG_SUBCHUNK_PREFIX && sourceInfo.subChunkY != null ->
                        ChunkKeys.subChunkKey(targetX, targetZ, sourceInfo.subChunkY, dimension)
                    sourceInfo.tag == ChunkKeys.TAG_BLOCK_ENTITY ->
                        ChunkKeys.blockEntityKey(targetX, targetZ, dimension)
                    else ->
                        rewriteChunkKey(record.key, sourceInfo, targetX, targetZ)
                }
                bridge.write(newKey, record.value)
                written++
            } else if (dimension == 0 && EntityKeys.isOverworldDigpKey(record.key)) {
                // Rebuild digp for the destination coordinates while keeping the
                // source actor IDs exactly as they were in the literal copy.
                bridge.write(EntityKeys.digpKey(targetX, targetZ), record.value)
                written++
            } else if (dimension == 0 && EntityKeys.isActorPrefixKey(record.key)) {
                // Actor IDs are global and are preserved literally.
                bridge.write(record.key, record.value)
                written++
            }
        }

        return written
    }

    private fun rewriteChunkKey(
        key: ByteArray,
        info: ChunkKeys.Info,
        targetX: Int,
        targetZ: Int
    ): ByteArray {
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
}
