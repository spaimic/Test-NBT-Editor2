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
        data class Failure(val reason: String, val hexSample: String) : ParseResult()
    }

    /**
     * Allocation-light representation used by the whole-map exporter.  Unlike
     * ParseResult.Success this keeps the packed storage words and palette names
     * instead of expanding 4096 cells into a 3-D String array.
     */
    data class CompactLayer(
        val bitsPerBlock: Int,
        val words: IntArray,
        val palette: Array<String>
    ) {
        fun indexAt(linearIndex: Int): Int {
            if (bitsPerBlock == 0) return 0
            val perWord = 32 / bitsPerBlock
            val word = words[linearIndex / perWord]
            val shift = (linearIndex % perWord) * bitsPerBlock
            return if (bitsPerBlock == 32) word else (word ushr shift) and ((1 shl bitsPerBlock) - 1)
        }

        fun nameAt(linearIndex: Int): String {
            val paletteIndex = indexAt(linearIndex)
            return palette.getOrNull(paletteIndex) ?: "minecraft:unknown"
        }
    }

    data class CompactSubChunk(val layers: List<CompactLayer>)

    /** Fast whole-map path: parse packed storages without expanding them. */
    fun parseCompact(value: ByteArray): CompactSubChunk? {
        return try {
            val stream = DataInputStream(ByteArrayInputStream(value))
            val version = stream.readUnsignedByte()
            if (version != 8 && version != 9) return null
            val layerCount = stream.readUnsignedByte()
            if (version == 9) stream.readUnsignedByte()
            if (layerCount !in 1..8) return null
            val layers = ArrayList<CompactLayer>(layerCount)
            for (i in 0 until layerCount) {
                val layer = decodeCompactLayerStrict(stream)
                if (layer == null) {
                    // Match the normal parser's resilience: a bad later overlay
                    // must not discard a valid base storage.
                    if (i == 0) return null
                    break
                }
                layers.add(layer)
            }
            if (layers.isEmpty()) null else CompactSubChunk(layers)
        } catch (_: Exception) {
            null
        }
    }

    fun linearIndex(x: Int, z: Int, y: Int): Int = (x shl 8) or (z shl 4) or y

    /** Returns the effective block name at one packed cell, respecting storage-layer precedence. */
    fun effectiveName(compact: CompactSubChunk, linearIndex: Int): String {
        for (i in compact.layers.indices.reversed()) {
            val name = compact.layers[i].nameAt(linearIndex)
            if (!isAirName(name)) return name
        }
        return "minecraft:air"
    }

    fun parse(value: ByteArray): ParseResult {
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
            // Confirmed against real hex dumps: version 9's extra "subchunk index" byte comes
            // AFTER the storage count, not right after the version byte.
            if (version == 9) stream.readByte()
            if (layerCount < 1) {
                return fail("layerCount=$layerCount (version=$version)")
            }
            val merged = decodeLayer(stream)
            for (i in 1 until layerCount) {
                val overlay = try {
                    decodeLayer(stream)
                } catch (_: Exception) {
                    // If a later storage layer is malformed, keep the base layer we
                    // already decoded rather than throwing away the whole subchunk.
                    return ParseResult.Success(merged)
                }
                mergeLayerInto(merged, overlay)
            }
            ParseResult.Success(merged)
        } catch (e: Exception) {
            fail("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun decodeLayer(stream: DataInputStream): Array<Array<Array<String>>> {
        val flag = stream.readUnsignedByte()
        val isRuntimePalette = (flag and 1) != 0
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

        val paletteSize = if (isRuntimePalette) readZigZagVarInt(stream) else readIntLE(stream)
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


    private fun mergeLayerInto(
        base: Array<Array<Array<String>>>,
        overlay: Array<Array<Array<String>>>
    ) {
        // Later block-storage layers can override earlier base data. For the map
        // renderer, any non-air block from the later layer is more useful than
        // silently dropping it.
        for (x in 0 until 16) {
            for (z in 0 until 16) {
                for (y in 0 until 16) {
                    val name = overlay[x][z][y]
                    if (name != "minecraft:air") {
                        base[x][z][y] = name
                    }
                }
            }
        }
    }


    /**
     * Lightweight metadata scan: decodes indices/palette but does not allocate a
     * 16x16x16 String array. Updates highest non-air Y for all 256 X/Z columns.
     */
    fun scanHighestColumns(value: ByteArray, highest: IntArray, subY: Int) {
        require(highest.size >= 256)
        val stream = DataInputStream(ByteArrayInputStream(value))
        val version = stream.readUnsignedByte()
        if (version != 8 && version != 9) return
        val layerCount = stream.readUnsignedByte()
        if (version == 9) stream.readUnsignedByte()
        if (layerCount !in 1..8) return

        // We only need the effective top block. For each layer, keep the palette
        // names and 4096 compact indices, not 4096 String references.
        val effective = IntArray(4096)
        val effectiveNames = ArrayList<String>()
        var haveBase = false
        repeat(layerCount) { layerIndex ->
            val decoded = decodeCompactLayer(stream) ?: return@repeat
            if (!haveBase) {
                effective.indices.forEach { effective[it] = decoded.indices[it] }
                effectiveNames.clear(); effectiveNames.addAll(decoded.palette)
                haveBase = true
            } else {
                // Preserve the layer-local palette by resolving only non-air cells.
                for (i in 0 until 4096) {
                    val name = decoded.palette.getOrNull(decoded.indices[i]) ?: "minecraft:air"
                    if (!isAirName(name)) {
                        val idx = effectiveNames.indexOf(name)
                        if (idx >= 0) effective[i] = idx
                        else { effectiveNames.add(name); effective[i] = effectiveNames.lastIndex }
                    }
                }
            }
        }
        if (!haveBase) return
        for (x in 0 until 16) for (z in 0 until 16) {
            val column = z * 16 + x
            var top = Int.MIN_VALUE
            for (y in 15 downTo 0) {
                val idx = effective[(x shl 8) or (z shl 4) or y]
                val name = effectiveNames.getOrNull(idx) ?: "minecraft:air"
                if (!isAirName(name)) { top = subY * 16 + y; break }
            }
            if (top > highest[column]) highest[column] = top
        }
    }

    /** Decode only one X/Z column. No 16x16x16 String cube is allocated. */
    fun readColumn(value: ByteArray, x: Int, z: Int, subY: Int): Map<Int, String> {
        val result = HashMap<Int, String>()
        val stream = DataInputStream(ByteArrayInputStream(value))
        val version = stream.readUnsignedByte()
        if (version != 8 && version != 9) return result
        val layerCount = stream.readUnsignedByte()
        if (version == 9) stream.readUnsignedByte()
        if (layerCount !in 1..8) return result
        val layers = ArrayList<LegacyCompactLayer>(layerCount)
        repeat(layerCount) { decodeCompactLayer(stream)?.let { layers.add(it) } }
        for (y in 0..15) {
            var name = "minecraft:air"
            for (layer in layers) {
                val idx = layer.indices[(x shl 8) or (z shl 4) or y]
                val n = layer.palette.getOrNull(idx) ?: "minecraft:air"
                if (!isAirName(n)) name = n
            }
            result[subY * 16 + y] = name
        }
        return result
    }

    private fun decodeCompactLayer(stream: DataInputStream): LegacyCompactLayer? {
        val flag = stream.readUnsignedByte()
        val isRuntimePalette = (flag and 1) != 0
        val bitsPerBlock = flag ushr 1
        if (bitsPerBlock > 32) return null
        val indices = IntArray(4096)
        if (bitsPerBlock > 0) {
            val blocksPerWord = 32 / bitsPerBlock
            val wordCount = (4096 + blocksPerWord - 1) / blocksPerWord
            val mask = if (bitsPerBlock == 32) -1 else (1 shl bitsPerBlock) - 1
            var blockIndex = 0
            for (w in 0 until wordCount) {
                val word = readIntLE(stream)
                for (i in 0 until blocksPerWord) {
                    if (blockIndex >= 4096) break
                    indices[blockIndex++] = (word ushr (i * bitsPerBlock)) and mask
                }
            }
        }
        val paletteSize = if (isRuntimePalette) readZigZagVarInt(stream) else readIntLE(stream)
        if (paletteSize !in 1..65536) return null
        val palette = ArrayList<String>(paletteSize)
        repeat(paletteSize) {
            val compound = NbtIO.readCompoundFromStream(stream)
            palette.add((compound.entries["name"] as? NbtTag.Str)?.value ?: "minecraft:unknown")
        }
        return LegacyCompactLayer(indices, palette)
    }

    private data class LegacyCompactLayer(val indices: IntArray, val palette: List<String>)

    private fun decodeCompactLayerStrict(stream: DataInputStream): CompactLayer? {
        val flag = stream.readUnsignedByte()
        val isRuntimePalette = (flag and 1) != 0
        val bitsPerBlock = flag ushr 1
        if (bitsPerBlock > 32) return null
        val perWord = if (bitsPerBlock == 0) 0 else 32 / bitsPerBlock
        val wordCount = if (bitsPerBlock == 0) 0 else (4096 + perWord - 1) / perWord
        val words = IntArray(wordCount)
        for (i in 0 until wordCount) words[i] = readIntLE(stream)
        val paletteSize = if (isRuntimePalette) readZigZagVarInt(stream) else readIntLE(stream)
        if (paletteSize !in 1..65536) return null
        val palette = Array(paletteSize) {
            val compound = NbtIO.readCompoundFromStream(stream)
            (compound.entries["name"] as? NbtTag.Str)?.value ?: "minecraft:unknown"
        }
        return CompactLayer(bitsPerBlock, words, palette)
    }

    fun isAirForSearch(name: String): Boolean = name == "minecraft:air" || name == "air"

    private fun isAirName(name: String): Boolean = isAirForSearch(name)

    private fun readZigZagVarInt(stream: DataInputStream): Int {
        var result = 0
        var shift = 0
        while (shift < 35) {
            val b = stream.readUnsignedByte()
            result = result or ((b and 0x7f) shl shift)
            if ((b and 0x80) == 0) {
                return (result ushr 1) xor -(result and 1)
            }
            shift += 7
        }
        throw IOException("Invalid palette count varint")
    }

    private fun readIntLE(stream: DataInputStream): Int {
        val a = stream.readUnsignedByte(); val b = stream.readUnsignedByte()
        val c = stream.readUnsignedByte(); val d = stream.readUnsignedByte()
        return (d shl 24) or (c shl 16) or (b shl 8) or a
    }
}
