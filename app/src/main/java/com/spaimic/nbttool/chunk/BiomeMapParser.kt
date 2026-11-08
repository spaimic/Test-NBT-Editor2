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
    /**
     * The 24 biome layers of a chunk, expanded lazily: only the layers that are actually
     * looked up are turned into 4096-entry arrays (the map normally needs one or two).
     */
    class LazyLayers internal constructor(private val sections: List<Section?>) {
        private val cache = arrayOfNulls<IntArray>(24)
        private val expanded = java.util.IdentityHashMap<Section, IntArray>()
        val indices: IntRange get() = 0 until 24
        operator fun get(layer: Int): IntArray? {
            if (layer !in 0 until 24) return null
            cache[layer]?.let { return it }
            val s = sectionAt(sections, layer) ?: return null
            val arr = expanded.getOrPut(s) { expand(s) }
            cache[layer] = arr
            return arr
        }
    }

    data class Result(val layers: LazyLayers)

    /** One decoded biome storage: bits per entry, packed words and the palette of biome ids. */
    internal class Section(val bits: Int, val words: IntArray, val palette: IntArray)

    /**
     * Decodes every biome storage that follows the 512-byte heightmap.
     *
     * Two details make this easy to get wrong:
     *  - a 0xff header means "identical to the previous section" (it is not an end marker);
     *  - a 0-bit storage (a section with a single biome, which is what nearly every
     *    uniform chunk has) carries NO palette-size field, just the one biome id.
     *    Reading a size there shifted everything after it, so uniform chunks failed
     *    to decode and only chunks near biome boundaries showed a biome.
     * The record is decoded strictly (it must be consumed exactly), trying the
     * no-size layout first and the sized layout as a fallback.
     */
    private fun decodeSections(raw: ByteArray, zeroBitHasCount: Boolean): List<Section?>? {
        return try {
            val input = DataInputStream(ByteArrayInputStream(raw, 512, raw.size - 512))
            val out = ArrayList<Section?>()
            var last: Section? = null
            while (input.available() > 0 && out.size < 64) {
                val flags = input.readUnsignedByte()
                if (flags == 0xff) { out.add(last); continue }
                val bits = flags ushr 1
                if (bits > 32) return null
                val section: Section
                if (bits == 0) {
                    val palette = if (zeroBitHasCount) {
                        val count = readIntLE(input)
                        if (count !in 1..65536) return null
                        IntArray(count) { readIntLE(input) }
                    } else {
                        intArrayOf(readIntLE(input))
                    }
                    section = Section(0, IntArray(0), palette)
                } else {
                    val perWord = 32 / bits
                    val wordCount = (4096 + perWord - 1) / perWord
                    val words = IntArray(wordCount) { readIntLE(input) }
                    val count = readIntLE(input)
                    if (count !in 1..65536) return null
                    val palette = IntArray(count) { readIntLE(input) }
                    section = Section(bits, words, palette)
                }
                out.add(section)
                last = section
            }
            out
        } catch (_: Exception) {
            null
        }
    }

    private fun sectionsFor(raw: ByteArray): List<Section?>? {
        if (raw.size <= 512) return null
        val plain = decodeSections(raw, false)
        if (plain != null && plain.isNotEmpty()) return plain
        return decodeSections(raw, true)?.takeIf { it.isNotEmpty() }
    }

    private fun sectionAt(sections: List<Section?>, layer: Int): Section? =
        sections.getOrNull(layer) ?: sections.lastOrNull()

    private fun expand(s: Section): IntArray {
        val values = IntArray(4096)
        if (s.bits == 0) {
            java.util.Arrays.fill(values, s.palette[0])
            return values
        }
        val perWord = 32 / s.bits
        val mask = if (s.bits == 32) -1 else (1 shl s.bits) - 1
        for (i in 0 until 4096) {
            val word = s.words.getOrElse(i / perWord) { 0 }
            val index = (word ushr ((i % perWord) * s.bits)) and mask
            values[i] = s.palette.getOrElse(index) { s.palette[0] }
        }
        return values
    }

    fun parse(raw: ByteArray): Result? {
        val sections = sectionsFor(raw) ?: return null
        return Result(LazyLayers(sections))
    }

    /**
     * Hot path used by the map renderer: reads only the biome cells of the 256 surface
     * columns. [subChunkYForColumn] / [localYForColumn] are indexed as z*16+x and give
     * the subchunk and local Y of each column's surface block; layer = subChunkY + 4
     * (the Overworld's lowest subchunk is -4).
     */
    fun biomeAtColumnsFast(
        raw: ByteArray?,
        subChunkYForColumn: IntArray,
        localYForColumn: IntArray,
        out: IntArray
    ) {
        if (raw == null || raw.size <= 512 || out.size < 256) return
        try {
            val sections = sectionsFor(raw) ?: return
            for (column in 0 until 256) {
                val layer = subChunkYForColumn[column] + 4
                if (layer < 0) continue
                val section = sectionAt(sections, layer) ?: continue
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
