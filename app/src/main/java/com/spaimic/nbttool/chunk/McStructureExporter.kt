package com.spaimic.nbttool.chunk

import com.spaimic.nbttool.nbt.NbtIO
import com.spaimic.nbttool.nbt.NbtTag
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Source of raw LevelDB values (so the exporter can be tested without a database). */
fun interface KeyReader {
    fun read(key: ByteArray): ByteArray?
}

/**
 * Exports a rectangular world area to a Bedrock `.mcstructure` file.
 *
 * The format limits a structure to 64 x 384 x 64 blocks (X x Y x Z), exactly like the
 * in-game structure block, so larger areas are rejected rather than split.
 *
 * File layout (little-endian NBT, unnamed root compound):
 *   format_version: 1
 *   size: [x, y, z]
 *   structure:
 *     block_indices: [ layer0[], layer1[] ]   index = z + sizeZ * (y + sizeY * x), -1 = none
 *     entities: []
 *     palette.default: { block_palette: [...], block_position_data: { "<index>": { block_entity_data } } }
 *   structure_world_origin: [x, y, z]
 */
object McStructureExporter {
    const val MAX_X = 64
    const val MAX_Y = 384
    const val MAX_Z = 64
    private const val UNSET = -2
    private const val FALLBACK_BLOCK_VERSION = 18153475

    class ExportResult(
        val bytes: ByteArray,
        val sizeX: Int,
        val sizeY: Int,
        val sizeZ: Int,
        val nonAirBlocks: Int,
        val paletteSize: Int,
        val blockEntities: Int,
        val skippedSubchunks: Int
    )

    private fun isAir(name: String?): Boolean =
        name == "minecraft:air" || name == "air" || name == "minecraft:cave_air" || name == "minecraft:void_air"

    private fun nameOf(c: NbtTag.Compound): String? = (c.entries["name"] as? NbtTag.Str)?.value

    private fun intOf(tag: NbtTag?): Int? = when (tag) {
        is NbtTag.Int -> tag.value
        is NbtTag.Short -> tag.value.toInt()
        is NbtTag.Byte -> tag.value.toInt()
        is NbtTag.Long -> tag.value.toInt()
        else -> null
    }

    fun export(
        reader: KeyReader,
        dimension: Int,
        minX: Int, maxX: Int,
        minZ: Int, maxZ: Int,
        minY: Int, maxY: Int,
        includeBlockEntities: Boolean,
        progress: ((done: Int, total: Int) -> Unit)? = null
    ): ExportResult {
        val sizeX = maxX - minX + 1
        val sizeY = maxY - minY + 1
        val sizeZ = maxZ - minZ + 1
        require(sizeX in 1..MAX_X && sizeZ in 1..MAX_Z) {
            "A .mcstructure holds at most $MAX_X x $MAX_Y x $MAX_Z blocks (X x Y x Z); the area is $sizeX x $sizeY x $sizeZ."
        }
        require(sizeY in 1..MAX_Y) { "A .mcstructure can be at most $MAX_Y blocks tall; the area is $sizeY." }
        val total = sizeX * sizeY * sizeZ

        val layer0 = IntArray(total) { UNSET }
        val layer1 = IntArray(total) { -1 }
        val palette = ArrayList<NbtTag.Compound>()
        val paletteIndex = HashMap<NbtTag.Compound, Int>()
        var blockVersion: Int? = null
        fun globalIndex(c: NbtTag.Compound): Int {
            if (blockVersion == null) blockVersion = intOf(c.entries["version"])
            return paletteIndex.getOrPut(c) { palette.add(c); palette.size - 1 }
        }
        fun linear(x: Int, y: Int, z: Int): Int = (z - minZ) + sizeZ * ((y - minY) + sizeY * (x - minX))

        val minCx = Math.floorDiv(minX, 16)
        val maxCx = Math.floorDiv(maxX, 16)
        val minCz = Math.floorDiv(minZ, 16)
        val maxCz = Math.floorDiv(maxZ, 16)
        val minSy = Math.floorDiv(minY, 16)
        val maxSy = Math.floorDiv(maxY, 16)
        val chunkCount = (maxCx - minCx + 1) * (maxCz - minCz + 1)
        var skipped = 0
        var blockEntityCount = 0
        val positionData = LinkedHashMap<String, NbtTag>()
        var chunksDone = 0

        for (cx in minCx..maxCx) for (cz in minCz..maxCz) {
            val x0 = maxOf(minX, cx * 16)
            val x1 = minOf(maxX, cx * 16 + 15)
            val z0 = maxOf(minZ, cz * 16)
            val z1 = minOf(maxZ, cz * 16 + 15)

            for (sy in minSy..maxSy) {
                val bytes = reader.read(ChunkKeys.subChunkKey(cx, cz, sy, dimension)) ?: continue
                val decoded = try {
                    SubChunkEditor.decode(bytes)
                } catch (_: Throwable) {
                    skipped++
                    continue
                }
                if (decoded.layers.isEmpty()) continue
                val l0 = decoded.layers[0]
                val map0 = IntArray(l0.palette.size) { globalIndex(l0.palette[it]) }
                val l1 = decoded.layers.getOrNull(1)
                // Second layer = waterlogging etc.; air there means "nothing" (-1).
                val map1 = l1?.let { l ->
                    IntArray(l.palette.size) { i ->
                        val c = l.palette[i]
                        if (isAir(nameOf(c))) -1 else globalIndex(c)
                    }
                }
                val y0 = maxOf(minY, sy * 16)
                val y1 = minOf(maxY, sy * 16 + 15)
                for (x in x0..x1) for (z in z0..z1) for (y in y0..y1) {
                    val li = ((x - cx * 16) shl 8) or ((z - cz * 16) shl 4) or (y - sy * 16)
                    val i = linear(x, y, z)
                    layer0[i] = map0.getOrElse(l0.indices[li]) { UNSET }
                    if (l1 != null && map1 != null) {
                        layer1[i] = map1.getOrElse(l1.indices[li]) { -1 }
                    }
                }
            }

            if (includeBlockEntities) {
                val raw = reader.read(ChunkKeys.blockEntityKey(cx, cz, dimension))
                if (raw != null) {
                    val compounds = try { NbtIO.readConcatenatedCompounds(raw) } catch (_: Throwable) { emptyList() }
                    for (c in compounds) {
                        val bx = intOf(c.entries["x"]) ?: continue
                        val by = intOf(c.entries["y"]) ?: continue
                        val bz = intOf(c.entries["z"]) ?: continue
                        if (bx !in minX..maxX || by !in minY..maxY || bz !in minZ..maxZ) continue
                        val data = LinkedHashMap<String, NbtTag>(c.entries)
                        data.remove("x"); data.remove("y"); data.remove("z")
                        positionData[linear(bx, by, bz).toString()] =
                            NbtTag.Compound(linkedMapOf("block_entity_data" to NbtTag.Compound(data)))
                        blockEntityCount++
                    }
                }
            }
            chunksDone++
            progress?.invoke(chunksDone, chunkCount)
        }

        // Anything never written (missing subchunks / void) is air.
        var airIndex = palette.indexOfFirst { nameOf(it) == "minecraft:air" }
        if (airIndex < 0) {
            palette.add(
                NbtTag.Compound(
                    linkedMapOf(
                        "name" to NbtTag.Str("minecraft:air"),
                        "states" to NbtTag.Compound(linkedMapOf()),
                        "version" to NbtTag.Int(blockVersion ?: FALLBACK_BLOCK_VERSION)
                    )
                )
            )
            airIndex = palette.size - 1
        }
        var nonAir = 0
        for (i in 0 until total) {
            if (layer0[i] == UNSET) layer0[i] = airIndex
            if (layer0[i] != airIndex || layer1[i] >= 0) nonAir++
        }

        val bytes = write(
            sizeX, sizeY, sizeZ, minX, minY, minZ, layer0, layer1, palette, positionData
        )
        return ExportResult(bytes, sizeX, sizeY, sizeZ, nonAir, palette.size, blockEntityCount, skipped)
    }

    private fun write(
        sizeX: Int, sizeY: Int, sizeZ: Int,
        originX: Int, originY: Int, originZ: Int,
        layer0: IntArray, layer1: IntArray,
        palette: List<NbtTag.Compound>,
        positionData: Map<String, NbtTag>
    ): ByteArray {
        val total = layer0.size
        val out = ByteArrayOutputStream(total * 8 + 8192)
        fun u8(v: Int) = out.write(v and 0xFF)
        fun i32(v: Int) {
            out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF)
        }
        fun name(s: String) {
            val b = s.toByteArray(Charsets.UTF_8)
            out.write(b.size and 0xFF); out.write((b.size shr 8) and 0xFF)
            out.write(b)
        }
        fun intArray(values: IntArray) {
            val bb = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            bb.asIntBuffer().put(values)
            out.write(bb.array())
        }

        u8(10); name("")                                    // root compound
        u8(3); name("format_version"); i32(1)
        u8(9); name("size"); u8(3); i32(3); i32(sizeX); i32(sizeY); i32(sizeZ)
        u8(10); name("structure")
        u8(9); name("block_indices"); u8(9); i32(2)         // list of two lists
        u8(3); i32(total); intArray(layer0)
        u8(3); i32(total); intArray(layer1)
        u8(9); name("entities"); u8(10); i32(0)             // entities are not exported
        val paletteTag = NbtTag.Compound(
            linkedMapOf(
                "default" to NbtTag.Compound(
                    linkedMapOf(
                        "block_palette" to NbtTag.ListTag(10, ArrayList<NbtTag>(palette)),
                        "block_position_data" to NbtTag.Compound(LinkedHashMap(positionData))
                    )
                )
            )
        )
        val pb = NbtIO.serializeTag(paletteTag)             // type id + payload (no name)
        u8(pb[0].toInt()); name("palette"); out.write(pb, 1, pb.size - 1)
        u8(0)                                               // end of "structure"
        u8(9); name("structure_world_origin"); u8(3); i32(3); i32(originX); i32(originY); i32(originZ)
        u8(0)                                               // end of root
        return out.toByteArray()
    }
}
