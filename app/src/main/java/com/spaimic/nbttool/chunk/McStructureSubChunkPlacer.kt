package com.spaimic.nbttool.chunk

import com.spaimic.nbttool.nbt.NbtIO
import com.spaimic.nbttool.nbt.NbtTag
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream

/**
 * Exact palette/state-aware writer used by the first MCStructure placement prototype.
 * It deliberately writes one complete palette layer and preserves all existing palette
 * compounds. The caller decides which chunk/subchunk records to write.
 */
object McStructureSubChunkPlacer {
    data class Placement(val x: Int, val localY: Int, val z: Int, val subChunkY: Int, val palette: NbtTag.Compound)

    private data class Layer(
        val palette: MutableList<NbtTag.Compound>,
        val indices: IntArray,
        val isRuntimePalette: Boolean
    )
    private data class Decoded(val version: Int, val indexByte: Int?, val layers: MutableList<Layer>)

    fun apply(bytes: ByteArray, placements: List<Placement>): ByteArray {
        require(placements.all { it.x in 0..15 && it.localY in 0..15 && it.z in 0..15 })
        val decoded = decode(bytes)
        val layer = decoded.layers.first()
        for (p in placements) {
            val paletteIndex = findOrAddExact(layer.palette, p.palette)
            layer.indices[(p.x shl 8) or (p.z shl 4) or p.localY] = paletteIndex
        }
        return encode(decoded)
    }

    fun create(placements: List<Placement>): ByteArray {
        val palette = ArrayList<NbtTag.Compound>()
        val indices = IntArray(4096)
        val air = NbtTag.Compound(linkedMapOf("name" to NbtTag.Str("minecraft:air")))
        palette += air
        for (p in placements) {
            require(p.x in 0..15 && p.localY in 0..15 && p.z in 0..15)
            indices[(p.x shl 8) or (p.z shl 4) or p.localY] = findOrAddExact(palette, p.palette)
        }
        return encode(Decoded(8, null, mutableListOf(Layer(palette, indices, false))))
    }

    private fun findOrAddExact(palette: MutableList<NbtTag.Compound>, wanted: NbtTag.Compound): Int {
        val wantedBytes = NbtIO.writeRootCompoundNoHeader(wanted)
        val existing = palette.indexOfFirst { NbtIO.writeRootCompoundNoHeader(it).contentEquals(wantedBytes) }
        if (existing >= 0) return existing
        palette += deepCopy(wanted)
        return palette.lastIndex
    }

    private fun deepCopy(compound: NbtTag.Compound): NbtTag.Compound =
        NbtIO.readRootCompoundNoHeader(NbtIO.writeRootCompoundNoHeader(compound))

    private fun decode(bytes: ByteArray): Decoded {
        val s = DataInputStream(ByteArrayInputStream(bytes))
        val version = s.readUnsignedByte()
        require(version == 8 || version == 9) { "Unsupported subchunk version $version" }
        val layerCount = s.readUnsignedByte()
        val indexByte = if (version == 9) s.readUnsignedByte() else null
        require(layerCount in 1..8) { "Invalid subchunk layer count $layerCount" }
        val layers = ArrayList<Layer>(layerCount)
        repeat(layerCount) {
            val flag = s.readUnsignedByte()
            val isRuntimePalette = (flag and 1) != 0
            val bits = flag ushr 1
            require(bits <= 32)
            val indices = IntArray(4096)
            if (bits > 0) {
                val perWord = 32 / bits
                val wordCount = (4096 + perWord - 1) / perWord
                val mask = if (bits == 32) -1 else (1 shl bits) - 1
                for (w in 0 until wordCount) {
                    val word = readIntLE(s)
                    for (i in 0 until perWord) {
                        val n = w * perWord + i
                        if (n >= 4096) break
                        indices[n] = (word ushr (i * bits)) and mask
                    }
                }
            }
            val paletteSize = if (isRuntimePalette) readZigZagVarInt(s) else readIntLE(s)
            require(paletteSize in 1..65536) { "Invalid palette size $paletteSize" }
            val palette = ArrayList<NbtTag.Compound>(paletteSize)
            repeat(paletteSize) { palette += NbtIO.readCompoundFromStream(s) }
            layers += Layer(palette, indices, isRuntimePalette)
        }
        return Decoded(version, indexByte, layers)
    }

    private fun encode(decoded: Decoded): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(decoded.version)
        out.write(decoded.layers.size)
        if (decoded.version == 9) out.write(decoded.indexByte ?: 0)
        for (layer in decoded.layers) {
            val bits = bitsForPalette(layer.palette.size)
            // Preserve the original storage type. Runtime palettes use a ZigZag
            // signed-varint palette count; persistent palettes use int32 LE.
            out.write((bits shl 1) or if (layer.isRuntimePalette) 1 else 0)
            if (bits > 0) {
                val perWord = 32 / bits
                val wordCount = (4096 + perWord - 1) / perWord
                val mask = if (bits == 32) -1 else (1 shl bits) - 1
                for (w in 0 until wordCount) {
                    var word = 0
                    for (i in 0 until perWord) {
                        val n = w * perWord + i
                        if (n >= 4096) break
                        word = word or ((layer.indices[n] and mask) shl (i * bits))
                    }
                    writeIntLE(out, word)
                }
            }
            if (layer.isRuntimePalette) writeZigZagVarInt(out, layer.palette.size)
            else writeIntLE(out, layer.palette.size)
            for (compound in layer.palette) out.write(NbtIO.writeRootCompoundNoHeader(compound))
        }
        return out.toByteArray()
    }

    private fun bitsForPalette(size: Int): Int = when {
        size <= 1 -> 0
        size <= 2 -> 1
        size <= 4 -> 2
        size <= 8 -> 3
        size <= 16 -> 4
        size <= 32 -> 5
        size <= 64 -> 6
        size <= 256 -> 8
        size <= 65536 -> 16
        else -> error("Palette too large: $size")
    }

    private fun readIntLE(s: DataInputStream): Int =
        s.readUnsignedByte() or (s.readUnsignedByte() shl 8) or
            (s.readUnsignedByte() shl 16) or (s.readUnsignedByte() shl 24)

    private fun readZigZagVarInt(s: DataInputStream): Int {
        var result = 0
        var shift = 0
        while (shift < 35) {
            val b = s.readUnsignedByte()
            result = result or ((b and 0x7f) shl shift)
            if ((b and 0x80) == 0) {
                return (result ushr 1) xor -(result and 1)
            }
            shift += 7
        }
        throw IllegalArgumentException("Invalid palette count varint")
    }

    private fun writeZigZagVarInt(out: ByteArrayOutputStream, value: Int) {
        var v = (value shl 1) xor (value shr 31)
        while ((v and 0xFFFFFF80.toInt()) != 0) {
            out.write((v and 0x7f) or 0x80)
            v = v ushr 7
        }
        out.write(v and 0x7f)
    }

    private fun writeIntLE(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 255); out.write((v ushr 8) and 255)
        out.write((v ushr 16) and 255); out.write((v ushr 24) and 255)
    }
}
