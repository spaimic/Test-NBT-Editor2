package com.spaimic.nbttool.nbt

import com.spaimic.nbttool.R

/** Maps each NBT tag type to its icon drawable, for the tree view. */
object NbtIcons {
    fun iconFor(tag: NbtTag): Int = when (tag) {
        is NbtTag.Byte -> R.drawable.nbt_byte
        is NbtTag.Short -> R.drawable.nbt_short
        is NbtTag.Int -> R.drawable.nbt_int
        is NbtTag.Long -> R.drawable.nbt_long
        is NbtTag.Float -> R.drawable.nbt_float
        is NbtTag.Double -> R.drawable.nbt_double
        is NbtTag.Str -> R.drawable.nbt_string
        is NbtTag.ByteArray -> R.drawable.nbt_byte_arr
        is NbtTag.IntArray -> R.drawable.nbt_int_arr
        is NbtTag.LongArray -> R.drawable.nbt_long // no dedicated long-array icon supplied — closest match
        is NbtTag.ListTag -> R.drawable.nbt_list
        is NbtTag.Compound -> R.drawable.nbt_compound
        NbtTag.End -> R.drawable.nbt_byte // never actually rendered as a row
    }
}
