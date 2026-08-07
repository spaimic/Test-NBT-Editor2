package com.spaimic.nbttool.chunk

import com.spaimic.nbttool.nbt.NbtIO
import com.spaimic.nbttool.nbt.NbtTag
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException

/**
 * Lightweight Bedrock subchunk decoder used by the map viewer.
 *
 * The old implementation expanded every subchunk into a 16x16x16 array of
 * Strings. That is extremely expensive for large worlds. The map only needs
 * the highest non-air block in each X/Z column, so this parser fills those
 * columns directly and never creates the 4096-string 3-D structure.
 */
object SubChunkParser {

    sealed class ParseResult {
        data class Success(val newlyFilled: Int) : ParseResult()
        data class Failure(val reason: String, val hexSample: String) : ParseResult()
    }

    /**
     * Fills unresolved X/Z columns from this subchunk. The caller supplies the
     * arrays so no per-subchunk 16x16x16 object graph is allocated.
     *
     * [filled] marks columns that already have a higher non-air block. Subchunks
     * must therefore be passed from highest Y to lowest Y.
     */
    fun fillTopSurface(
        value: ByteArray,
        subChunkY: Int,
        colors: IntArray,
        surfaceY: IntArray,
        filled: BooleanArray,
        unknownBlockNames: MutableSet<String>
    ): ParseResult {
        fun fail(reason: String): ParseResult.Failure {
            val n = minOf(24, value.size)
            val hex = (0 until n).joinToString(" ") { "%02x".format(value[it]) }
            return ParseResult.Failure(reason, "size=${value.size} bytes: $hex")
        }

        return try {
            val stream = DataInputStream(ByteArrayInputStream(value))
            val version = stream.readUnsignedByte()
            if (version != 8 && version != 9) {
                return fail("unsupported subchunk version=$version")
            }
            val layerCount = stream.readUnsignedByte()
            if (version == 9) stream.readByte()
            if (layerCount < 1) return fail("layerCount=$layerCount (version=$version)")

            val indices = decodeLayerIndices(stream)
            val paletteSize = readIntLE(stream)
            if (paletteSize < 0 || paletteSize > 65536) {
                throw IOException("implausible paletteSize=$paletteSize")
            }
            val palette = Array(paletteSize) { "minecraft:unknown" }
            repeat(paletteSize) { i ->
                val compound = NbtIO.readCompoundFromStream(stream)
                palette[i] = (compound.entries["name"] as? NbtTag.Str)?.value ?: "minecraft:unknown"
            }

            // Only layer 0 represents the normal block storage. Extra layers
            // (for example waterlogging) are deliberately skipped for the map.
            // We don't need to decode their palettes at all: they are below the
            // layer-0 bytes and cannot change the solid top surface we render.
            // If the format contains additional layers, leaving them unread is
            // harmless because this parser only consumes one DB value.

            var newlyFilled = 0
            for (x in 0 until 16) {
                for (z in 0 until 16) {
                    val column = (x shl 8) or (z shl 4)
                    for (y in 15 downTo 0) {
                        val idx = indices[column or y]
                        val name = palette.getOrElse(idx) { "minecraft:unknown" }
                        if (name != "minecraft:air") {
                            val out = x * 16 + z
                            if (!filled[out]) {
                                colors[out] = BlockColors.colorFor(name)
                                surfaceY[out] = subChunkY * 16 + y
                                filled[out] = true
                                newlyFilled++
                                if (!BlockColors.isKnown(name)) unknownBlockNames.add(name)
                            }
                            break
                        }
                    }
                }
            }
            ParseResult.Success(newlyFilled)
        } catch (e: Exception) {
            fail("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun decodeLayerIndices(stream: DataInputStream): IntArray {
        val flag = stream.readUnsignedByte()
        val bitsPerBlock = flag ushr 1
        if (bitsPerBlock > 32) throw IOException("implausible bitsPerBlock=$bitsPerBlock (flag=$flag)")
        val indices = IntArray(4096)
        if (bitsPerBlock == 0) return indices

        val blocksPerWord = 32 / bitsPerBlock
        if (blocksPerWord <= 0) throw IOException("invalid bitsPerBlock=$bitsPerBlock")
        val wordCount = (4096 + blocksPerWord - 1) / blocksPerWord
        val mask = if (bitsPerBlock == 32) -1 else (1 shl bitsPerBlock) - 1
        var blockIndex = 0
        for (w in 0 until wordCount) {
            val word = readIntLE(stream)
            var shift = 0
            var i = 0
            while (i < blocksPerWord && blockIndex < 4096) {
                indices[blockIndex] = if (bitsPerBlock == 32) word else (word ushr shift) and mask
                shift += bitsPerBlock
                blockIndex++
                i++
            }
        }
        return indices
    }

    private fun readIntLE(stream: DataInputStream): Int {
        val a = stream.readUnsignedByte(); val b = stream.readUnsignedByte()
        val c = stream.readUnsignedByte(); val d = stream.readUnsignedByte()
        return (d shl 24) or (c shl 16) or (b shl 8) or a
    }
}
