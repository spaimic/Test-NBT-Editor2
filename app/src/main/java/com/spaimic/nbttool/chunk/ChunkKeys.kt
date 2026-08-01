package com.spaimic.nbttool.chunk

/**
 * Bedrock's LevelDB chunk keys are raw binary, not readable strings — this
 * decodes them. Format (widely documented by community Bedrock tooling, not an
 * official Mojang spec):
 *   Overworld:      int32LE chunkX, int32LE chunkZ, byte tag, [byte subChunkY]
 *   Nether/End:     int32LE chunkX, int32LE chunkZ, int32LE dimension, byte tag, [byte subChunkY]
 * `subChunkY` is only present when tag == SUBCHUNK_PREFIX.
 */
object ChunkKeys {
    const val TAG_DATA_2D = 0x2D          // legacy heightmap + biome array
    const val TAG_DATA_3D = 0x2B          // newer per-subchunk biomes
    const val TAG_SUBCHUNK_PREFIX = 0x2F  // one 16x16x16 slice of block data
    const val TAG_LEGACY_TERRAIN = 0x30   // pre-1.0 flat format — NOT decoded by SubChunkParser
    const val TAG_BLOCK_ENTITY = 0x31     // chests, shulker boxes, signs, furnaces, etc.

    data class Info(val chunkX: Int, val chunkZ: Int, val dimension: Int, val tag: Int, val subChunkY: Int?)

    // These are the tag bytes any chunk-data key can plausibly carry — used as a sanity
    // check so we don't misparse an unrelated short binary key as a chunk key.
    private val KNOWN_TAGS = setOf(0x2B, 0x2C, 0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x39, 0x3A, 0x76, 0x77, 0x78)

    fun parse(key: ByteArray): Info? {
        // overworld: 9 bytes (whole-chunk tag) or 10 bytes (subchunk tag + Y)
        if (key.size == 9 || key.size == 10) {
            val x = readI32(key, 0); val z = readI32(key, 4)
            val tag = key[8].toInt() and 0xFF
            if (tag in KNOWN_TAGS) {
                val subY = if (key.size == 10) key[9].toInt() else null
                if (tag == TAG_SUBCHUNK_PREFIX && subY == null) return null
                return Info(x, z, 0, tag, subY)
            }
        }
        // nether/end: 13 bytes or 14 bytes (with a dimension field)
        if (key.size == 13 || key.size == 14) {
            val x = readI32(key, 0); val z = readI32(key, 4); val dim = readI32(key, 8)
            val tag = key[12].toInt() and 0xFF
            if (tag in KNOWN_TAGS && dim in 1..2) {
                val subY = if (key.size == 14) key[13].toInt() else null
                if (tag == TAG_SUBCHUNK_PREFIX && subY == null) return null
                return Info(x, z, dim, tag, subY)
            }
        }
        return null
    }

    fun blockEntityKey(chunkX: Int, chunkZ: Int, dimension: Int = 0): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun writeI32LE(v: Int) {
            out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF)
        }
        writeI32LE(chunkX)
        writeI32LE(chunkZ)
        if (dimension != 0) writeI32LE(dimension)
        out.write(TAG_BLOCK_ENTITY)
        return out.toByteArray()
    }

    private fun readI32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)
}
