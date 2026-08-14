package com.spaimic.nbttool.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import com.spaimic.nbttool.nbt.NbtTag
import com.spaimic.nbttool.shizuku.LevelDbSession
import kotlin.math.min

/**
 * Build 127: image -> real Bedrock filled_map.
 *
 * Build 126 only changed an existing map_<id> colors array. Build 127 creates
 * a new map_<id> record and a minecraft:filled_map item pointing at it via
 * map_uuid, then inserts that item into the local player's Inventory.
 *
 * The implementation is independent of Nibnya's source. It follows the
 * documented Bedrock map/filled_map data relationship.
 */
object MapImageImporter {
    const val MAP_SIZE = 128
    const val COLOR_BYTES = MAP_SIZE * MAP_SIZE

    data class Result(
        val mapId: Long,
        val inventorySlot: Int,
        val width: Int,
        val height: Int
    )

    private val palette = intArrayOf(
        0x000000, 0x7F7C78, 0xF7E9A3, 0xA7A7A7, 0xFF0000, 0xA0A0FF,
        0xA7A7A7, 0x007C00, 0xFFFFFF, 0xA4A8B8, 0x976D4D, 0x707070,
        0x4040FF, 0x8F7748, 0xFFFCF5, 0xD87F33, 0xB24CD8, 0x6699D8,
        0xE5E533, 0x7FCC19, 0xF27FA5, 0x4C4C4C, 0x999999, 0x4C7F99,
        0x7F3FB2, 0x334CB2, 0x664C33, 0x667F33, 0x993333, 0x191919,
        0xFAEE4D, 0x5CDB95, 0x4A80FF, 0x00D93A, 0x815631, 0x700200,
        0xD1B1A1, 0x9F5224, 0x95576C, 0x706C8A, 0xBA8524, 0x677535,
        0xA04D4E, 0x392923, 0x876B62, 0x575C5C, 0x7A4958, 0x4C3E5C,
        0x4C3223, 0x4C522A, 0x8E3C2E, 0x251610, 0xBD3031, 0x943F61,
        0x5C191D, 0x167E86, 0x3A8E8C, 0x562C3E, 0x14B485, 0x646464,
        0xD8AF93, 0x7FA796
    )

    fun importIntoMap(context: Context, uri: Uri, templateMap: NbtTag.Compound): Result {
        val bitmap = context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Couldn't open image." }
            BitmapFactory.decodeStream(input)
        } ?: throw IllegalArgumentException("Unsupported or invalid image.")

        val pixels = ByteArray(COLOR_BYTES)
        val scaled = Bitmap.createBitmap(MAP_SIZE, MAP_SIZE, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(scaled)
            canvas.drawColor(Color.WHITE)
            val scale = min(
                MAP_SIZE.toFloat() / bitmap.width.toFloat(),
                MAP_SIZE.toFloat() / bitmap.height.toFloat()
            )
            val w = (bitmap.width * scale).coerceAtLeast(1f)
            val h = (bitmap.height * scale).coerceAtLeast(1f)
            val left = (MAP_SIZE - w) / 2f
            val top = (MAP_SIZE - h) / 2f
            canvas.drawBitmap(
                bitmap, null,
                RectF(left, top, left + w, top + h),
                Paint(Paint.FILTER_BITMAP_FLAG)
            )

            val argb = IntArray(COLOR_BYTES)
            scaled.getPixels(argb, 0, MAP_SIZE, 0, 0, MAP_SIZE, MAP_SIZE)
            for (i in argb.indices) {
                val c = argb[i]
                pixels[i] = if (Color.alpha(c) < 128) {
                    0
                } else {
                    nearestPaletteSlot(c).toByte()
                }
            }
        } finally {
            scaled.recycle()
            bitmap.recycle()
        }

        val session = LevelDbSession
        val bridge = session.bridge ?: throw IllegalStateException("World database isn't open.")

        // Find a genuinely unused numeric map_<id> key.
        val existingMapIds = bridge.listKeysByPrefix("map_")
            .mapNotNull { parseMapId(it.displayName) }
            .toHashSet()

        var mapId = (existingMapIds.maxOrNull() ?: 0L) + 1L
        while (mapId in existingMapIds) mapId++

        val mapKey = "map_$mapId"
        val mapRecord = deepCopyCompound(templateMap)

        setMapId(mapRecord, mapId)
        setMapDimensions(mapRecord)
        setColors(mapRecord, pixels)

        // Create/load ~local_player because the new filled_map must exist in
        // the player's inventory, not merely as an orphaned map_<id> record.
        val playerKey = "~local_player"
        val playerRawKey = playerKey.toByteArray(Charsets.UTF_8)
        val player = session.loadedEntries[playerKey] ?: run {
            val bytes = bridge.read(playerRawKey)
                ?: throw IllegalStateException("~local_player does not exist in this world.")
            NbtTag.Compound(
                java.util.LinkedHashMap(
                    com.spaimic.nbttool.nbt.NbtIO.readRootCompoundNoHeader(bytes).entries
                )
            ).also {
                session.loadedEntries[playerKey] = it
                session.keyBytesByName[playerKey] = playerRawKey
            }
        }

        val inventory = findInventory(player)
            ?: throw IllegalStateException("Couldn't find the player's Inventory list.")

        val free = findFreeInventorySlot(inventory)
            ?: throw IllegalStateException("Player inventory is full (slots 0–35).")

        // Bedrock player Inventory is commonly a fixed 36-entry list. Do NOT
        // append a 37th compound: Minecraft can interpret the list position
        // independently of Slot and this can duplicate Slot values and corrupt
        // existing inventory entries. Reuse the existing empty entry when it
        // exists, preserving the list's exact 36-entry structure.
        val mapItem = createFilledMapItem(mapId, free.slot)
        if (free.listIndex in inventory.items.indices) {
            inventory.items[free.listIndex] = mapItem
        } else {
            inventory.items.add(mapItem)
        }

        session.keyBytesByName[mapKey] = mapKey.toByteArray(Charsets.UTF_8)
        session.loadedEntries[mapKey] = mapRecord
        // Only these two records are changed by this operation. The editor's Save
        // action will therefore never rewrite unrelated cached world records.
        session.markDirty(mapKey)
        session.markDirty(playerKey)

        return Result(mapId, free.slot, MAP_SIZE, MAP_SIZE)
    }

    private fun parseMapId(name: String): Long? =
        if (name.startsWith("map_")) name.substring(4).toLongOrNull() else null

    private fun createFilledMapItem(mapId: Long, slot: Int): NbtTag.Compound {
        val tag = NbtTag.Compound(linkedMapOf(
            "map_display_players" to NbtTag.Byte(1),
            "map_is_init" to NbtTag.Byte(1),
            "map_is_scaling" to NbtTag.Byte(0),
            "map_name_index" to NbtTag.Int(0),
            "map_scale" to NbtTag.Int(0),
            "map_uuid" to NbtTag.Long(mapId)
        ))

        return NbtTag.Compound(linkedMapOf(
            "Count" to NbtTag.Byte(1),
            "Name" to NbtTag.Str("minecraft:filled_map"),
            "Slot" to NbtTag.Byte(slot.toByte()),
            "tag" to tag
        ))
    }

    private fun findInventory(player: NbtTag.Compound): NbtTag.ListTag? {
        val direct = player.entries["Inventory"]
        if (direct is NbtTag.ListTag) return direct

        // Some Bedrock player records place inventory under a nested compound.
        fun search(tag: NbtTag): NbtTag.ListTag? = when (tag) {
            is NbtTag.Compound -> {
                tag.entries["Inventory"] as? NbtTag.ListTag
                    ?: tag.entries.values.asSequence().mapNotNull(::search).firstOrNull()
            }
            is NbtTag.ListTag -> tag.items.asSequence().mapNotNull(::search).firstOrNull()
            else -> null
        }
        return player.entries.values.asSequence().mapNotNull(::search).firstOrNull()
    }

    /**
     * Build 128: determine occupied inventory slots using both Slot and Name.
     *
     * Bedrock's Inventory list is sparse: an absent entry does not mean the
     * slot is occupied, and the list itself should never be assumed to contain
     * 36 entries. The Slot byte identifies the physical slot while Name is the
     * authoritative indication that an entry actually contains an item.
     *
     * Missing/empty Name => the slot is treated as empty.
     * Non-empty Name => the slot is occupied.
     */
    private data class FreeSlot(val slot: Int, val listIndex: Int)

    private fun findFreeInventorySlot(inventory: NbtTag.ListTag): FreeSlot? {
        val occupied = BooleanArray(36)
        val emptyIndexBySlot = IntArray(36) { -1 }

        for ((index, item) in inventory.items.withIndex()) {
            val compound = item as? NbtTag.Compound ?: continue
            val slot = (compound.entries["Slot"] as? NbtTag.Byte)?.value?.toInt()
                ?: continue
            if (slot !in 0..35) continue

            val name = (compound.entries["Name"] as? NbtTag.Str)?.value ?: ""
            if (name.isNotEmpty()) {
                occupied[slot] = true
                emptyIndexBySlot[slot] = -1
            } else if (emptyIndexBySlot[slot] == -1) {
                // Reuse the existing empty compound instead of appending another
                // compound with the same Slot value.
                emptyIndexBySlot[slot] = index
            }
        }

        for (slot in 0..35) {
            if (!occupied[slot]) {
                val index = emptyIndexBySlot[slot]
                if (index >= 0) return FreeSlot(slot, index)
            }
        }

        // Sparse inventories may genuinely omit a slot. Append only in that
        // case; fixed 36-entry inventories never take this path.
        return (0..35).firstOrNull { !occupied[it] }?.let { FreeSlot(it, inventory.items.size) }
    }

    private fun setMapId(root: NbtTag.Compound, mapId: Long) {
        val keyCandidates = listOf("mapId", "map_id", "map_uuid")
        for (key in keyCandidates) {
            val old = root.entries[key]
            if (old != null) {
                root.entries[key] = when (old) {
                    is NbtTag.Long -> NbtTag.Long(mapId)
                    is NbtTag.Int -> NbtTag.Int(mapId.toInt())
                    else -> NbtTag.Long(mapId)
                }
                return
            }
        }
        root.entries["mapId"] = NbtTag.Long(mapId)
    }

    private fun setMapDimensions(root: NbtTag.Compound) {
        root.entries["width"] = NbtTag.Int(MAP_SIZE)
        root.entries["height"] = NbtTag.Int(MAP_SIZE)
        root.entries["scale"] = NbtTag.Byte(0)
        root.entries["fullyExplored"] = NbtTag.Byte(1)
    }

    private fun setColors(root: NbtTag.Compound, pixels: ByteArray) {
        if (root.entries["colors"] is NbtTag.ByteArray) {
            root.entries["colors"] = NbtTag.ByteArray(pixels)
            return
        }
        val data = root.entries["data"]
        if (data is NbtTag.Compound) {
            data.entries["colors"] = NbtTag.ByteArray(pixels)
            return
        }
        root.entries["colors"] = NbtTag.ByteArray(pixels)
    }

    private fun deepCopyCompound(root: NbtTag.Compound): NbtTag.Compound =
        NbtTag.Compound(
            java.util.LinkedHashMap(
                root.entries.mapValues {
                    com.spaimic.nbttool.clipboard.NbtClipboard.deepCopy(it.value)
                }
            )
        )

    private fun nearestPaletteSlot(argb: Int): Int {
        val r = Color.red(argb)
        val g = Color.green(argb)
        val b = Color.blue(argb)
        // Bedrock's map palette used by this app stores the Bedrock base color
        // IDs directly (0..61). Do NOT use Java's baseColor*4+shade encoding
        // here. That encoding was the reason Build 129 produced scrambled/random
        // colors. Nibnya's Bedrock palette uses these base IDs directly as well.
        var bestIndex = 1
        var bestDistance = Double.MAX_VALUE
        for (baseIndex in 1 until palette.size) {
            val base = palette[baseIndex]
            val br = (base shr 16) and 0xff
            val bg = (base shr 8) and 0xff
            val bb = base and 0xff
            val dr = r - br
            val dg = g - bg
            val db = b - bb
            val distance = dr * dr * 0.30 + dg * dg * 0.59 + db * db * 0.11
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = baseIndex
            }
        }
        return bestIndex
    }
}
