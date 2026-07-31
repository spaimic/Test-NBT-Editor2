package com.spaimic.nbttool.chunk

import com.spaimic.nbttool.nbt.NbtIO
import com.spaimic.nbttool.nbt.NbtTag
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException

/**
 * Decodes ONE subchunk's block storage — the paletted format used by every
 * Bedrock world saved since roughly 1.2 ("subchunk format version 8/9"). Older
 * pre-1.0 worlds used a different flat format and are NOT handled here; such a
 * subchunk simply fails to parse (returns null) and the caller treats that
 * chunk as loaded-but-unknown rather than crashing.
 *
 * Layout of one subchunk value:
 *   byte version            (8 or 9)
 *   [byte subChunkIndexY]   (only present if version == 9 — we already know this
 *                            from the LevelDB key itself, so it's just skipped)
 *   byte storageLayerCount  (normally 1; 2 when a waterlogged block occupies
 *                            the same voxel — layers beyond 0 are skipped, not
 *                            needed for a surface-color map)
 *   for each layer:
 *     byte flag             (bitsPerBlock = flag ushr 1)
 *     if bitsPerBlock == 0: the whole layer is one block, no packed array at all
 *     else: packed indices — 4096 blocks, bitsPerBlock each, into 32-bit LE words
 *     int32 LE paletteSize
 *     paletteSize × NBT compound {name: string, states: compound, version: int}
 *       (this is a normal little-endian NBT compound, same shape our NbtIO
 *       already reads elsewhere — see NbtIO.readCompoundFromStream)
 */
object SubChunkParser {

    /** blocks[x][z][y] = block name (e.g. "minecraft:grass"). Null if this subchunk's format isn't recognized. */
    fun parse(value: ByteArray): Array<Array<Array<String>>>? = try {
        val stream = DataInputStream(ByteArrayInputStream(value))
        val version = stream.readUnsignedByte()
        if (version != 8 && version != 9) {
            null // legacy/unsupported subchunk format
        } else {
            if (version == 9) stream.readByte() // embedded Y index — already known from the key, unused here
            val layerCount = stream.readUnsignedByte()
            if (layerCount < 1) {
                null
            } else {
                val layer0 = decodeLayer(stream)
                for (i in 1 until layerCount) decodeLayer(stream) // skip extra layers (e.g. waterlogging)
                layer0
            }
        }
    } catch (e: Exception) {
        null
    }

    private fun decodeLayer(stream: DataInputStream): Array<Array<Array<String>>> {
        val flag = stream.readUnsignedByte()
        val bitsPerBlock = flag ushr 1
        // A real persistent subchunk never has more than a few bits per block (values
        // above ~16 mean we're not actually looking at a subchunk — likely a chunk key
        // that was misidentified as one — so bail out cleanly rather than divide by
        // zero or try to allocate an implausibly large array below.
        if (bitsPerBlock > 32) throw IOException("Implausible bitsPerBlock=$bitsPerBlock — not a real subchunk")
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
        // A real palette is at most a few hundred entries — anything wildly bigger means
        // we've drifted out of sync with the actual data (or this was never a subchunk to
        // begin with). Reject it instead of asking ArrayList to allocate gigabytes.
        if (paletteSize < 0 || paletteSize > 65536) {
            throw IOException("Implausible palette size $paletteSize — not a real subchunk")
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
