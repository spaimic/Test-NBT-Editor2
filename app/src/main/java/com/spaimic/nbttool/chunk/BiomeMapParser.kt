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
            val input = DataInputStream(ByteArrayInputStream(raw, 512, raw.size - 512))
            val requestedLayer = IntArray(24) { -1 }
            for (column in 0 until 256) {
                val layer = subChunkYForColumn[column] + 4
                if (layer in requestedLayer.indices) requestedLayer[layer] = 1
            }

            for (layerIndex in 0 until 24) {
                if (input.available() <= 0) break
                val flags = input.readUnsignedByte()
                if (flags == 0xff) break
                val bits = flags ushr 1
                if (bits > 32) return
                val perWord = if (bits == 0) 0 else 32 / bits
                val wordCount = if (bits == 0) 0 else (4096 + perWord - 1) / perWord

                // Only capture packed indices for the 256 cells that actually
                // belong to this biome layer. Everything else is discarded while
                // streaming through the record.
                val requestedByWord = if (requestedLayer[layerIndex] >= 0 && bits > 0) {
                    arrayOfNulls<MutableList<Pair<Int, Int>>>(wordCount).also { buckets ->
                        for (column in 0 until 256) {
                            val targetLayer = subChunkYForColumn[column] + 4
                            if (targetLayer != layerIndex) continue
                            val localY = localYForColumn[column].coerceIn(0, 15)
                            val x = column and 15
                            val z = column ushr 4
                            val linear = (x shl 8) or (z shl 4) or localY
                            val word = linear / perWord
                            buckets[word] = (buckets[word] ?: ArrayList()).also { it.add(column to (linear % perWord)) }
                        }
                    }
                } else null

                val paletteIndices = IntArray(256) { -1 }
                for (wordIndex in 0 until wordCount) {
                    val word = readIntLE(input)
                    if (bits == 0) continue
                    val bucket = requestedByWord?.getOrNull(wordIndex) ?: continue
                    val mask = if (bits == 32) -1 else (1 shl bits) - 1
                    for ((column, local) in bucket) {
                        paletteIndices[column] = (word ushr (local * bits)) and mask
                    }
                }

                val paletteCount = readIntLE(input)
                if (paletteCount <= 0 || paletteCount > 65536) return
                val palette = IntArray(paletteCount)
                for (i in 0 until paletteCount) palette[i] = readIntLE(input)

                if (bits == 0) {
                    for (column in 0 until 256) {
                        if (subChunkYForColumn[column] + 4 == layerIndex) out[column] = palette[0]
                    }
                } else {
                    for (column in 0 until 256) {
                        val index = paletteIndices[column]
                        if (index >= 0 && index < palette.size) out[column] = palette[index]
                    }
                }
            }
        } catch (_: Exception) {
            // Keep the renderer's existing null/default biome behavior on bad data.
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
