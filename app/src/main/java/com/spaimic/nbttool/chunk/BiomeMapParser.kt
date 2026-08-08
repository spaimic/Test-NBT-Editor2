package com.spaimic.nbttool.chunk

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException

/**
 * Reads the per-chunk Data3D biome record (tag 0x2B).
 * Modern Bedrock stores a 512-byte height map followed by paletted biome
 * subchunks. The map renderer only needs the biome at the surface column, so
 * this parser keeps the decoded data deliberately small.
 */
object BiomeMapParser {
    data class Result(val layers: Array<IntArray?>)

    fun parse(raw: ByteArray): Result? {
        if (raw.size <= 512) return null
        return try {
            val input = DataInputStream(ByteArrayInputStream(raw, 512, raw.size - 512))
            val layers = arrayOfNulls<IntArray>(24)
            for (layer in 0 until 24) {
                if (input.available() <= 0) break
                val flags = input.readUnsignedByte()
                // 0xff is the empty-storage sentinel used by Bedrock's paletted storage.
                if (flags == 0xff) break
                val bits = flags ushr 1
                val values = IntArray(4096)
                if (bits == 0) {
                    // palette is still present; all 4096 entries use palette[0]
                } else {
                    if (bits > 32) throw IOException("invalid biome bits=$bits")
                    val perWord = 32 / bits
                    val wordCount = (4096 + perWord - 1) / perWord
                    val mask = if (bits == 32) -1 else (1 shl bits) - 1
                    var at = 0
                    repeat(wordCount) {
                        val word = readIntLE(input)
                        repeat(perWord) {
                            if (at < 4096) {
                                values[at++] = (word ushr (it * bits)) and mask
                            }
                        }
                    }
                }
                val paletteCount = readIntLE(input)
                if (paletteCount <= 0 || paletteCount > 65536) throw IOException("invalid biome palette=$paletteCount")
                val palette = IntArray(paletteCount) { readIntLE(input) }
                for (i in values.indices) values[i] = palette.getOrElse(values[i]) { palette[0] }
                layers[layer] = values
            }
            Result(layers)
        } catch (_: Exception) {
            null
        }
    }

    fun biomeAt(result: Result?, surfaceY: Int, x: Int, z: Int, subChunkY: Int): Int? {
        if (result == null) return null
        val layer = subChunkY + 4 // modern world minimum subchunk Y is -4
        if (layer !in result.layers.indices) return null
        val data = result.layers[layer] ?: return null
        val localY = (surfaceY - subChunkY * 16).coerceIn(0, 15)
        val index = (x shl 8) or (z shl 4) or localY
        return data.getOrNull(index)
    }

    private fun readIntLE(input: DataInputStream): Int {
        val a = input.readUnsignedByte()
        val b = input.readUnsignedByte()
        val c = input.readUnsignedByte()
        val d = input.readUnsignedByte()
        return a or (b shl 8) or (c shl 16) or (d shl 24)
    }
}
