package com.spaimic.nbttool.chunk

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Bedrock modern actor storage uses a per-chunk digest (`digp`) containing the
 * 8-byte actor storage IDs, plus one `actorprefix` record per actor. The storage
 * ID is the Bedrock-derived storage key and is serialized consistently in the
 * actorprefix key, digp digest, and actor NBT StorageKey component.
 */
object EntityKeys {
    private val DIGP_PREFIX = "digp".toByteArray(Charsets.US_ASCII)
    private val ACTORPREFIX = "actorprefix".toByteArray(Charsets.US_ASCII)

    fun digpKey(chunkX: Int, chunkZ: Int, dimension: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(DIGP_PREFIX)
        writeI32LE(out, chunkX)
        writeI32LE(out, chunkZ)
        if (dimension != 0) writeI32LE(out, dimension)
        return out.toByteArray()
    }

    fun actorPrefixKey(storageKey: ByteArray): ByteArray {
        require(storageKey.size == 8) { "Actor storage key must be exactly 8 bytes." }
        val out = ByteArrayOutputStream()
        out.write(ACTORPREFIX)
        out.write(storageKey)
        return out.toByteArray()
    }

    /**
     * Converts an ActorUniqueID to Bedrock's modern actor storage-key bytes.
     *
     * Bedrock derives the opaque storage ID as:
     *   (ActorUniqueID XOR 0xFFFFFFFF00000000) + 0x100000000
     * The resulting 64-bit value is stored as two signed 32-bit words in
     * little-endian order. Those exact 8 bytes are the identity used in the
     * `actorprefix` key, the `digp` digest, and the binary NBT
     * `internalComponents.EntityStorageKeyComponent.StorageKey` property.
     *
     * This little-endian representation is important: current Bedrock tooling
     * reads/writes the StorageKey as two Int32LE values.
     */
    fun storageKeyBytes(actorUniqueId: Long): ByteArray {
        val storageId = (actorUniqueId xor -0x1_0000_0000L) + 0x1_0000_0000L
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(storageId).array()
    }

    /** The exact same 8-byte storage-key payload used inside actor NBT. */
    fun storageKeyComponentBytes(actorUniqueId: Long): ByteArray =
        storageKeyBytes(actorUniqueId)

    /** Splits a digp value into the individual opaque 8-byte actor storage keys it lists. */
    fun splitActorIds(digpValue: ByteArray): List<ByteArray> {
        val count = digpValue.size / 8
        return (0 until count).map { i -> digpValue.copyOfRange(i * 8, i * 8 + 8) }
    }

    /** True if this is a digp key for the requested Bedrock dimension. */
    fun isDigpKeyForDimension(key: ByteArray, dimension: Int): Boolean =
        key.size == (if (dimension == 0) 12 else 16) &&
            key[0] == 'd'.code.toByte() && key[1] == 'i'.code.toByte() &&
            key[2] == 'g'.code.toByte() && key[3] == 'p'.code.toByte() &&
            (dimension == 0 || readI32(key, 12) == dimension)

    fun isOverworldDigpKey(key: ByteArray): Boolean = isDigpKeyForDimension(key, 0)

    /** Chunk X/Z from an Overworld digp key — call only after [isOverworldDigpKey] returns true. */
    fun digpChunkXZ(key: ByteArray): Pair<Int, Int> {
        return readI32(key, 4) to readI32(key, 8)
    }

    fun digpDimension(key: ByteArray): Int = if (key.size >= 16) readI32(key, 12) else 0

    /** True if this is an actorprefix key — 11-byte ASCII prefix + exactly 8 bytes of actor ID. */
    fun isActorPrefixKey(key: ByteArray): Boolean {
        if (key.size != 19) return false
        for (i in ACTORPREFIX.indices) if (key[i] != ACTORPREFIX[i]) return false
        return true
    }

    /** The 8-byte actor ID suffix of an actorprefix key, as a hex string (a usable HashMap key). */
    fun actorIdBytes(key: ByteArray): ByteArray = key.copyOfRange(11, 19)

    fun actorIdHex(key: ByteArray): String =
        actorIdBytes(key).joinToString("") { "%02x".format(it) }

    /** Hex identity for an already-extracted 8-byte actor ID. */
    fun actorIdBytesHex(actorId: ByteArray): String =
        actorId.joinToString("") { "%02x".format(it) }

    private fun readI32(key: ByteArray, off: Int): Int =
        (key[off].toInt() and 0xFF) or ((key[off + 1].toInt() and 0xFF) shl 8) or
            ((key[off + 2].toInt() and 0xFF) shl 16) or ((key[off + 3].toInt() and 0xFF) shl 24)

    private fun writeI32LE(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
        out.write((v shr 16) and 0xFF)
        out.write((v shr 24) and 0xFF)
    }
}
