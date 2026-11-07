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
                if (input.available() <= 0) {
                    // Record ended early: the remaining sections repeat the last one.
                    layers[layer] = if (layer > 0) layers[layer - 1] else null
                    continue
                }
                val flags = input.readUnsignedByte()
                // 0xff means "identical to the previous section" (Bedrock omits repeated
                // biome storages). It is NOT an end marker: sections above it still apply.
                if (flags == 0xff) {
                    layers[layer] = if (layer > 0) layers[layer - 1] else null
                    continue
                }
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

    /**
     * Build-180 hot path. Reads only the biome cells requested by a renderer.
     * The previous parse() expanded every biome subchunk to 4096 Ints even
     * though the map renderer ultimately needs at most 256 biome values.
     *
     * [subChunkYForColumn] is indexed as z*16+x and identifies the subchunk
     * containing the selected surface block for that column. [localYForColumn]
     * is that block's Y within the subchunk.
     */
    fun biomeAtColumnsFast(
        raw: ByteArray?,
        subChunkYForColumn: IntArray,
        localYForColumn: IntArray,
        out: IntArray
    ) {
        if (raw == null || raw.size <= 512 || out.size < 256) return
        try {
            var maxLayer = -1
            for (column in 0 until 256) {
                maxLayer = maxOf(maxLayer, subChunkYForColumn[column] + 4)
            }
            if (maxLayer < 0) return
            val layerCount = minOf(maxLayer + 1, 64)
            val input = DataInputStream(ByteArrayInputStream(raw, 512, raw.size - 512))

            // Packed storage of each section: bits per entry, packed words, palette.
            // A 0xff header means "same as the previous section", so those layers share
            // the previous section object instead of ending the scan (the old code
            // stopped at the first 0xff, which left most columns without a biome).
            class Section(val bits: Int, val words: IntArray, val palette: IntArray)
            val sections = arrayOfNulls<Section>(layerCount)
            var last: Section? = null
            for (layer in 0 until layerCount) {
                if (input.available() <= 0) { sections[layer] = last; continue }
                val flags = input.readUnsignedByte()
                if (flags == 0xff) { sections[layer] = last; continue }
                val bits = flags ushr 1
                if (bits > 32) return
                val perWord = if (bits == 0) 0 else 32 / bits
                val wordCount = if (bits == 0) 0 else (4096 + perWord - 1) / perWord
                val words = IntArray(wordCount)
                for (i in 0 until wordCount) words[i] = readIntLE(input)
                val paletteCount = readIntLE(input)
                if (paletteCount <= 0 || paletteCount > 65536) return
                val palette = IntArray(paletteCount) { readIntLE(input) }
                val section = Section(bits, words, palette)
                sections[layer] = section
                last = section
            }

            for (column in 0 until 256) {
                val layer = subChunkYForColumn[column] + 4
                if (layer !in sections.indices) continue
                val section = sections[layer] ?: continue
                if (section.bits == 0) {
                    out[column] = section.palette[0]
                    continue
                }
                val x = column and 15
                val z = column ushr 4
                val linear = (x shl 8) or (z shl 4) or localYForColumn[column].coerceIn(0, 15)
                val perWord = 32 / section.bits
                val word = section.words.getOrElse(linear / perWord) { 0 }
                val mask = if (section.bits == 32) -1 else (1 shl section.bits) - 1
                val index = (word ushr ((linear % perWord) * section.bits)) and mask
                out[column] = section.palette.getOrElse(index) { section.palette[0] }
            }
        } catch (_: Exception) {
            // Keep the renderer's existing null/default biome behavior on bad data.
        }
    }

    /**
     * Reads Bedrock's 16x16 DATA_3D heightmap without decoding any biome palettes.
     * The stored value is big-endian on disk and represents (surfaceY + 64).
     * A decoded value of -64 is Bedrock's unknown/sentinel value.
     *
     * Returns one Y value per column, where Y is the first air block above the
     * highest surface block (matching BedrockChunk.getHeightMapValue()).
     */
    fun heightMapFast(raw: ByteArray?, out: IntArray): Boolean {
        if (raw == null || raw.size < 512 || out.size < 256) return false
        try {
            for (column in 0 until 256) {
                val offset = column shl 1
                // Bedrock stores each short byte-swapped relative to Java's
                // big-endian DataInputStream representation.
                val stored = (raw[offset].toInt() and 0xFF) or
                    ((raw[offset + 1].toInt() and 0xFF) shl 8)
                val signed = stored.toShort().toInt()
                val height = signed - 64
                out[column] = height
            }
            return true
        } catch (_: Exception) {
            return false
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
