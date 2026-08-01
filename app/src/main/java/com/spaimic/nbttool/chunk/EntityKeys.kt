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

    private fun writeI32LE(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
        out.write((v shr 16) and 0xFF)
        out.write((v shr 24) and 0xFF)
    }
}
