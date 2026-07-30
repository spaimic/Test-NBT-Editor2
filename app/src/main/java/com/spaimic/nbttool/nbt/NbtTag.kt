package com.spaimic.nbttool.nbt

/**
 * In-memory model of a Bedrock Edition NBT tag.
 * Bedrock uses the same tag-type IDs as Java Edition NBT, but every
 * multi-byte numeric field (shorts, ints, longs, floats, doubles, and
 * the 2-byte string-length prefix) is little-endian instead of big-endian.
 */
sealed class NbtTag(val id: kotlin.Int) {

    object End : NbtTag(0)
    data class Byte(val value: kotlin.Byte) : NbtTag(1)
    data class Short(val value: kotlin.Short) : NbtTag(2)
    data class Int(val value: kotlin.Int) : NbtTag(3)
    data class Long(val value: kotlin.Long) : NbtTag(4)
    data class Float(val value: kotlin.Float) : NbtTag(5)
    data class Double(val value: kotlin.Double) : NbtTag(6)
    data class ByteArray(val value: kotlin.ByteArray) : NbtTag(7) {
        override fun equals(other: Any?) = other is ByteArray && value.contentEquals(other.value)
        override fun hashCode() = value.contentHashCode()
    }
    data class Str(val value: String) : NbtTag(8)

    /** All elements in an NBT list share one element type id. */
    data class ListTag(val elementTypeId: kotlin.Int, val items: MutableList<NbtTag>) : NbtTag(9)

    /** name -> tag, insertion order preserved so we don't scramble level.dat on save. */
    data class Compound(val entries: LinkedHashMap<String, NbtTag>) : NbtTag(10)

    data class IntArray(val value: kotlin.IntArray) : NbtTag(11) {
        override fun equals(other: Any?) = other is IntArray && value.contentEquals(other.value)
        override fun hashCode() = value.contentHashCode()
    }
    data class LongArray(val value: kotlin.LongArray) : NbtTag(12) {
        override fun equals(other: Any?) = other is LongArray && value.contentEquals(other.value)
        override fun hashCode() = value.contentHashCode()
    }

    companion object {
        fun typeName(id: kotlin.Int): String = when (id) {
            0 -> "End"; 1 -> "Byte"; 2 -> "Short"; 3 -> "Int"; 4 -> "Long"
            5 -> "Float"; 6 -> "Double"; 7 -> "ByteArray"; 8 -> "String"
            9 -> "List"; 10 -> "Compound"; 11 -> "IntArray"; 12 -> "LongArray"
            else -> "Unknown($id)"
        }
    }
}
