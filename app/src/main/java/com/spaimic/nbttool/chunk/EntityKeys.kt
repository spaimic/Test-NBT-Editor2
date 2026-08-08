package com.spaimic.nbttool.chunk

import java.io.ByteArrayOutputStream

/**
 * Bedrock stores entities ("actors") separately from terrain, using two key
 * families with ASCII string prefixes (not the numeric tag bytes chunk terrain
 * keys use, so these never collide with [ChunkKeys]'s detection):
 *
 *   "digp" + chunkX(4 LE) + chunkZ(4 LE) [+ dimension(4 LE) if not Overworld]
 *     -> a value that's just N × 8-byte actor unique IDs concatenated — the
 *        list of every entity currently in that chunk.
 *
 *   "actorprefix" + <8-byte actor unique ID>
 *     -> that entity's full NBT compound (same header-less shape as level.dat
 *        entries — see NbtIO.readRootCompoundNoHeader), including an
 *        "identifier" string (e.g. "minecraft:cow") and a "Pos" float list.
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

    fun actorPrefixKey(actorId: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(ACTORPREFIX)
        out.write(actorId)
        return out.toByteArray()
    }

    /** Splits a digp value into the individual 8-byte actor IDs it lists. */
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
