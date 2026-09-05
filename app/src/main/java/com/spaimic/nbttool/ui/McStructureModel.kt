package com.spaimic.nbttool.ui

import android.graphics.Color
import com.spaimic.nbttool.chunk.BlockColors
import com.spaimic.nbttool.nbt.NbtTag

/**
 * Build 205: read-only decoder for Bedrock .mcstructure data stored in a
 * structuretemplate_ LevelDB value. It intentionally keeps the raw NBT model
 * intact and builds a small, inspectable representation on top of it.
 */
data class McStructureBlock(
    val x: Int,
    val y: Int,
    val z: Int,
    val paletteIndex: Int,
    val name: String,
    val states: NbtTag.Compound?,
    val version: Long?
)

data class McStructurePaletteEntry(
    val index: Int,
    val name: String,
    val states: NbtTag.Compound?,
    val version: Long?
) {
    val color: Int
        get() = BlockColors.colorFor(name)
}

data class McStructureAnalysis(
    val raw: NbtTag.Compound,
    val sizeX: Int,
    val sizeY: Int,
    val sizeZ: Int,
    val origin: IntArray?,
    val palette: List<McStructurePaletteEntry>,
    val blocks: List<McStructureBlock>,
    val layer0: List<Int>,
    val layer1: List<Int>,
    val blockPositionData: NbtTag.Compound?,
    val entities: NbtTag.ListTag?,
    val unknownRootEntries: List<String>
) {
    val volume: Long get() = sizeX.toLong() * sizeY.toLong() * sizeZ.toLong()
    val nonEmptyBlocks: Int get() = blocks.size

    fun blockAt(x: Int, y: Int, z: Int): McStructureBlock? =
        blocks.firstOrNull { it.x == x && it.y == y && it.z == z }

    fun layer(y: Int): List<McStructureBlock> = blocks.filter { it.y == y }
}

object McStructureDecoder {

    private fun NbtTag.asIntOrNull(): Int? = when (this) {
        is NbtTag.Byte -> value.toInt()
        is NbtTag.Short -> value.toInt()
        is NbtTag.Int -> value
        is NbtTag.Long -> value.toInt()
        else -> null
    }

    private fun NbtTag.asLongOrNull(): Long? = when (this) {
        is NbtTag.Byte -> value.toLong()
        is NbtTag.Short -> value.toLong()
        is NbtTag.Int -> value.toLong()
        is NbtTag.Long -> value
        else -> null
    }

    private fun NbtTag.asIntList(): List<Int>? {
        return when (this) {
            is NbtTag.IntArray -> value.toList()
            is NbtTag.ListTag -> {
                if (elementTypeId != 3 && elementTypeId != 1 && elementTypeId != 2 && elementTypeId != 4) {
                    null
                } else {
                    items.mapNotNull { it.asIntOrNull() }
                }
            }
            else -> null
        }
    }

    private fun NbtTag.asString(): String? = (this as? NbtTag.Str)?.value

    private fun rootCompound(root: NbtTag.Compound, name: String): NbtTag.Compound? =
        (root.entries[name] as? NbtTag.Compound)

    private fun findDefaultPalette(structure: NbtTag.Compound): NbtTag.Compound? {
        val palette = rootCompound(structure, "palette") ?: return null
        return rootCompound(palette, "default") ?: palette
    }

    private fun readPalette(structure: NbtTag.Compound): List<McStructurePaletteEntry> {
        val default = findDefaultPalette(structure) ?: return emptyList()
        val raw = default.entries["block_palette"] ?: return emptyList()
        val list = raw as? NbtTag.ListTag ?: return emptyList()
        return list.items.mapIndexed { index, item ->
            val compound = item as? NbtTag.Compound
            val name = compound?.entries?.get("name")?.asString() ?: "<palette entry $index>"
            val states = compound?.entries?.get("states") as? NbtTag.Compound
            val version = compound?.entries?.get("version")?.asLongOrNull()
            McStructurePaletteEntry(index, name, states, version)
        }
    }

    private fun readIndices(structure: NbtTag.Compound, key: String): List<Int> {
        return structure.entries[key]?.asIntList() ?: emptyList()
    }

    /**
     * Bedrock's structure arrays use ZYX order: Z changes fastest, then Y,
     * then X. For size [X,Y,Z], the flattened index is:
     * index = z + sizeZ * (y + sizeY * x).
     */
    private fun linearIndex(x: Int, y: Int, z: Int, sy: Int, sz: Int): Int =
        z + sz * (y + sy * x)

    fun decode(root: NbtTag.Compound): McStructureAnalysis {
        val size = root.entries["size"]?.asIntList() ?: emptyList()
        val sx = (size.getOrNull(0) ?: 0).coerceAtLeast(0)
        val sy = (size.getOrNull(1) ?: 0).coerceAtLeast(0)
        val sz = (size.getOrNull(2) ?: 0).coerceAtLeast(0)
        val expected = sx.toLong() * sy.toLong() * sz.toLong()
        require(expected <= 5_000_000L) { "Structure is too large to inspect safely (${expected} positions)." }

        // The important discovery from real .mcstructure files: block_indices,
        // entities and palette are NOT root keys. They live inside the root
        // "structure" compound.
        val structure = root.entries["structure"] as? NbtTag.Compound
            ?: throw IllegalArgumentException("This MCStructure has no structure compound.")

        val layers = (structure.entries["block_indices"] as? NbtTag.ListTag)?.items ?: emptyList()
        val layer0 = layers.getOrNull(0)?.asIntList() ?: emptyList()
        val layer1 = layers.getOrNull(1)?.asIntList() ?: emptyList()

        val palette = readPalette(structure)
        val blocks = ArrayList<McStructureBlock>()
        if (sx > 0 && sy > 0 && sz > 0) {
            for (x in 0 until sx) {
                for (y in 0 until sy) {
                    for (z in 0 until sz) {
                        val i = linearIndex(x, y, z, sy, sz)
                        val p0 = layer0.getOrNull(i) ?: -1
                        val p1 = layer1.getOrNull(i) ?: -1
                        val paletteIndex = when {
                            p1 >= 0 -> p1
                            p0 >= 0 -> p0
                            else -> -1
                        }
                        if (paletteIndex < 0) continue
                        val entry = palette.getOrNull(paletteIndex)
                        val name = entry?.name ?: "<invalid palette index $paletteIndex>"
                        blocks.add(McStructureBlock(x, y, z, paletteIndex, name, entry?.states, entry?.version))
                    }
                }
            }
        }

        val defaultPalette = findDefaultPalette(structure)
        val blockPositionData = defaultPalette?.entries?.get("block_position_data") as? NbtTag.Compound
        val entities = structure.entries["entities"] as? NbtTag.ListTag

        val rootNames = root.entries.keys.toList()
        val known = setOf("format_version", "size", "structure", "structure_world_origin")
        return McStructureAnalysis(
            raw = root,
            sizeX = sx,
            sizeY = sy,
            sizeZ = sz,
            origin = root.entries["structure_world_origin"]?.asIntList()?.toIntArray(),
            palette = palette,
            blocks = blocks,
            layer0 = layer0,
            layer1 = layer1,
            blockPositionData = blockPositionData,
            entities = entities,
            unknownRootEntries = rootNames.filter { it !in known }
        )
    }

    fun blockColor(name: String): Int {
        val clean = name.substringAfter(':', name)
        return when {
            clean == "air" || clean == "cave_air" || clean == "void_air" -> Color.TRANSPARENT
            else -> BlockColors.colorFor(name)
        }
    }

    fun summaryText(a: McStructureAnalysis): String = buildString {
        append("Structure size: ${a.sizeX} × ${a.sizeY} × ${a.sizeZ}\n")
        append("Volume: ${a.volume} positions\n")
        append("Non-empty decoded blocks: ${a.nonEmptyBlocks}\n")
        append("Palette entries: ${a.palette.size}\n")
        append("Entities: ${a.entities?.items?.size ?: 0}\n")
        append("Block position data entries: ${a.blockPositionData?.entries?.size ?: 0}")
    }

    fun valueText(tag: NbtTag?): String = when (tag) {
        null -> "<missing>"
        is NbtTag.Byte -> tag.value.toString()
        is NbtTag.Short -> tag.value.toString()
        is NbtTag.Int -> tag.value.toString()
        is NbtTag.Long -> tag.value.toString()
        is NbtTag.Float -> tag.value.toString()
        is NbtTag.Double -> tag.value.toString()
        is NbtTag.Str -> tag.value
        is NbtTag.ByteArray -> "ByteArray(${tag.value.size})"
        is NbtTag.IntArray -> tag.value.joinToString(prefix = "[", postfix = "]")
        is NbtTag.LongArray -> tag.value.joinToString(prefix = "[", postfix = "]")
        is NbtTag.ListTag -> "List<${NbtTag.typeName(tag.elementTypeId)}>(${tag.items.size})"
        is NbtTag.Compound -> "Compound(${tag.entries.size})"
        is NbtTag.End -> "End"
    }
}
