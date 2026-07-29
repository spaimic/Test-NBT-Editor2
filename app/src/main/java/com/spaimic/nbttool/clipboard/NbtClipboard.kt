package com.spaimic.nbttool.clipboard

import android.content.Context
import com.spaimic.nbttool.nbt.NbtIO
import com.spaimic.nbttool.nbt.NbtTag
import java.io.File

/**
 * Stores named NBT snippets in the app's own private storage (NOT the device's
 * system clipboard). Each entry is one file: <sanitized name>.nbt containing the
 * serialized tag. Letting entries persist as plain files means they survive app
 * restarts and can be reused across different worlds.
 */
object NbtClipboard {

    private fun dir(context: Context): File =
        File(context.filesDir, "nbt_clipboard").apply { mkdirs() }

    private fun sanitize(name: String): String =
        name.trim().replace(Regex("[^A-Za-z0-9 _-]"), "_").ifBlank { "unnamed" }

    fun save(context: Context, name: String, tag: NbtTag) {
        val file = File(dir(context), "${sanitize(name)}.nbt")
        file.writeBytes(NbtIO.serializeTag(tag))
    }

    fun listNames(context: Context): List<String> =
        dir(context).listFiles { f -> f.extension == "nbt" }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            ?: emptyList()

    fun load(context: Context, name: String): NbtTag? {
        val file = File(dir(context), "${sanitize(name)}.nbt")
        if (!file.exists()) return null
        return NbtIO.deserializeTag(file.readBytes())
    }

    fun delete(context: Context, name: String) {
        File(dir(context), "${sanitize(name)}.nbt").delete()
    }

    /** Deep-copies a tag so pasting the same clipboard entry twice never shares mutable state. */
    fun deepCopy(tag: NbtTag): NbtTag = when (tag) {
        is NbtTag.Compound -> NbtTag.Compound(LinkedHashMap(tag.entries.mapValues { deepCopy(it.value) }))
        is NbtTag.ListTag -> NbtTag.ListTag(tag.elementTypeId, tag.items.map { deepCopy(it) }.toMutableList())
        is NbtTag.ByteArray -> NbtTag.ByteArray(tag.value.copyOf())
        is NbtTag.IntArray -> NbtTag.IntArray(tag.value.copyOf())
        is NbtTag.LongArray -> NbtTag.LongArray(tag.value.copyOf())
        else -> tag // primitives are immutable value types already
    }
}
