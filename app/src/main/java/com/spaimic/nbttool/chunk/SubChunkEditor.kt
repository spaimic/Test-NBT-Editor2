package com.spaimic.nbttool.chunk

import com.spaimic.nbttool.nbt.NbtIO
import com.spaimic.nbttool.nbt.NbtTag
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream

/** Safe-ish block-name editor for Bedrock subchunks. Existing palette compounds are preserved. */
object SubChunkEditor {
    internal data class Layer(
        val palette: MutableList<NbtTag.Compound>,
        val indices: IntArray
    )
    internal data class Decoded(val version: Int, val indexByte: Int?, val layers: MutableList<Layer>)

    fun replaceBlock(bytes: ByteArray, x: Int, z: Int, y: Int, newName: String): ByteArray {
        require(x in 0..15 && z in 0..15 && y in 0..15)
        val decoded = decode(bytes)
        val linear = (x shl 8) or (z shl 4) or y
        // Find the highest layer that currently supplies a non-air block at this position.
        var chosen = 0
        for (i in decoded.layers.indices) {
            val layer = decoded.layers[i]
            val name = layer.palette.getOrNull(layer.indices[linear])?.entries?.get("name")
            if ((name as? NbtTag.Str)?.value != null && name.value != "minecraft:air") chosen = i
        }
        val layer = decoded.layers[chosen]
        val paletteIndex = findOrAddPalette(layer.palette, newName)
        layer.indices[linear] = paletteIndex
        return encode(decoded)
    }



    /** A brand-new all-air subchunk (version 9, one layer), used when editing above existing terrain. */
    fun emptySubChunk(subY: Int): ByteArray {
        val air = NbtTag.Compound(linkedMapOf("name" to NbtTag.Str("minecraft:air")))
        val layer = Layer(arrayListOf(air), IntArray(4096))
        return encode(Decoded(9, subY and 0xFF, arrayListOf(layer)))
    }

    fun clearColumnsToAir(bytes: ByteArray, minX: Int, maxX: Int, minZ: Int, maxZ: Int): ByteArray {
        require(minX in 0..15 && maxX in 0..15 && minX <= maxX)
        require(minZ in 0..15 && maxZ in 0..15 && minZ <= maxZ)
        val decoded = decode(bytes)
        for (layer in decoded.layers) {
            val air = findOrAddPalette(layer.palette, "minecraft:air")
            for (x in minX..maxX) for (z in minZ..maxZ) {
                for (y in 0 until 16) {
                    val linear = (x shl 8) or (z shl 4) or y
                    layer.indices[linear] = air
                }
            }
        }
        return encode(decoded)
    }
    private fun findOrAddPalette(palette: MutableList<NbtTag.Compound>, name: String): Int {
        val existing = palette.indexOfFirst {
            (it.entries["name"] as? NbtTag.Str)?.value == name
        }
        if (existing >= 0) return existing
        palette += NbtTag.Compound(linkedMapOf("name" to NbtTag.Str(name)))
        return palette.lastIndex
    }

    internal fun decode(bytes: ByteArray): Decoded {
        val s = DataInputStream(ByteArrayInputStream(bytes))
        val version = s.readUnsignedByte()
        require(version == 8 || version == 9) { "Unsupported subchunk version $version" }
        val layerCount = s.readUnsignedByte()
        val indexByte = if (version == 9) s.readUnsignedByte() else null
        require(layerCount in 1..8)
        val layers = ArrayList<Layer>(layerCount)
        repeat(layerCount) {
            val flag = s.readUnsignedByte()
            val bits = flag ushr 1
            require(bits <= 32)
            val indices = IntArray(4096)
            if (bits > 0) {
                val perWord = 32 / bits
                val wordCount = (4096 + perWord - 1) / perWord
                for (w in 0 until wordCount) {
                    val word = readIntLE(s)
                    for (i in 0 until perWord) {
                        val n = w * perWord + i
                        if (n >= 4096) break
                        val shift = i * bits
                        val mask = if (bits == 32) -1 else (1 shl bits) - 1
                        indices[n] = (word ushr shift) and mask
                    }
                }
            }
            val palette = if (bits == 0) {
                arrayListOf(NbtIO.readCompoundFromStream(s))
            } else {
                val paletteSize = readIntLE(s)
                require(paletteSize in 1..65536)
                ArrayList<NbtTag.Compound>(paletteSize).also { list ->
                    repeat(paletteSize) { list += NbtIO.readCompoundFromStream(s) }
                }
            }
            layers += Layer(palette, indices)
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
            out.write(bits shl 1)
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
            if (bits > 0) writeIntLE(out, layer.palette.size)
            else require(layer.palette.size == 1) { "bitsPerBlock=0 requires exactly one palette entry" }
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

    private fun readIntLE(s: DataInputStream): Int {
        val a=s.readUnsignedByte(); val b=s.readUnsignedByte(); val c=s.readUnsignedByte(); val d=s.readUnsignedByte()
        return a or (b shl 8) or (c shl 16) or (d shl 24)
    }
    private fun writeIntLE(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 255); out.write((v ushr 8) and 255); out.write((v ushr 16) and 255); out.write((v ushr 24) and 255)
    }
}
