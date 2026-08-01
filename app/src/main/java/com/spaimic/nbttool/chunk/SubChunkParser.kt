package com.spaimic.nbttool.chunk

import com.spaimic.nbttool.nbt.NbtIO
import com.spaimic.nbttool.nbt.NbtTag
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException

/**
 * Decodes ONE subchunk's block storage — the paletted format used by Bedrock
 * worlds since roughly 1.2 ("subchunk format version 8/9"). This is
 * reverse-engineered, community-documented, unverified-by-me format, and the
 * exact version byte(s) real modern worlds use may have moved on since — see
 * [ParseResult.Failure] which reports WHY a subchunk failed (including the
 * actual version byte seen) rather than just returning null, specifically so
 * that can be diagnosed with real data instead of guesswork.
 */
object SubChunkParser {

    sealed class ParseResult {
        data class Success(val blocks: Array<Array<Array<String>>>) : ParseResult()
        data class Failure(val reason: String) : ParseResult()
    }

    fun parse(value: ByteArray): ParseResult {
        return try {
            val stream = DataInputStream(ByteArrayInputStream(value))
            val version = stream.readUnsignedByte()
            if (version != 8 && version != 9) {
                return ParseResult.Failure("unsupported subchunk version=$version (size=${value.size} bytes)")
            }
            if (version == 9) stream.readByte() // embedded Y index — already known from the key, unused here
            val layerCount = stream.readUnsignedByte()
            if (layerCount < 1) {
                return ParseResult.Failure("layerCount=$layerCount")
            }
            val layer0 = decodeLayer(stream)
            for (i in 1 until layerCount) decodeLayer(stream) // skip extra layers (e.g. waterlogging)
            ParseResult.Success(layer0)
        } catch (e: Exception) {
            ParseResult.Failure("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun decodeLayer(stream: DataInputStream): Array<Array<Array<String>>> {
        val flag = stream.readUnsignedByte()
        val bitsPerBlock = flag ushr 1
        if (bitsPerBlock > 32) throw IOException("implausible bitsPerBlock=$bitsPerBlock (flag=$flag)")
        val indices = IntArray(4096)

        if (bitsPerBlock > 0) {
            val blocksPerWord = 32 / bitsPerBlock
            val wordCount = (4096 + blocksPerWord - 1) / blocksPerWord
            val mask = (1 shl bitsPerBlock) - 1
            var blockIndex = 0
            for (w in 0 until wordCount) {
                val word = readIntLE(stream)
                var shift = 0
                var i = 0
                while (i < blocksPerWord && blockIndex < 4096) {
                    indices[blockIndex] = (word ushr shift) and mask
                    shift += bitsPerBlock
                    blockIndex++
                    i++
                }
            }
        }
        // bitsPerBlock == 0 means every index stays 0 — the whole layer is palette[0]

        val paletteSize = readIntLE(stream)
        if (paletteSize < 0 || paletteSize > 65536) {
            throw IOException("implausible paletteSize=$paletteSize (bitsPerBlock=$bitsPerBlock)")
        }
        val palette = ArrayList<String>(paletteSize)
        repeat(paletteSize) {
            val compound = NbtIO.readCompoundFromStream(stream)
            palette.add((compound.entries["name"] as? NbtTag.Str)?.value ?: "minecraft:unknown")
        }

        val out = Array(16) { Array(16) { Array(16) { "minecraft:air" } } }
        // Bedrock's persistent block index order: index = (x << 8) | (z << 4) | y
        for (x in 0 until 16) {
            for (z in 0 until 16) {
                for (y in 0 until 16) {
                    val idx = indices[(x shl 8) or (z shl 4) or y]
                    out[x][z][y] = palette.getOrElse(idx) { "minecraft:unknown" }
                }
            }
        }
        return out
    }

    private fun readIntLE(stream: DataInputStream): Int {
        val a = stream.readUnsignedByte(); val b = stream.readUnsignedByte()
        val c = stream.readUnsignedByte(); val d = stream.readUnsignedByte()
        return (d shl 24) or (c shl 16) or (b shl 8) or a
    }
}
