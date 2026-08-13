package com.spaimic.nbttool.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.spaimic.nbttool.nbt.NbtTag
import java.io.InputStream
import kotlin.math.min
import kotlin.math.pow

/**
 * Build 126: Converts a normal image into the 128x128 color payload used by a
 * Bedrock map_<id> record. This is an independent implementation of the
 * image-to-map idea; it does not copy Nibnya's source.
 *
 * The generated payload is one byte per map pixel (128*128 = 16384 bytes).
 * Transparent source pixels become map color 0; opaque pixels are matched
 * against a Bedrock-style base color palette.
 */
object MapImageImporter {
    const val MAP_SIZE = 128
    const val COLOR_BYTES = MAP_SIZE * MAP_SIZE

    data class Result(val width: Int, val height: Int)

    // Bedrock-style base map colors, ordered by palette slot.
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

    fun importIntoMap(context: Context, uri: Uri, map: NbtTag.Compound): Result {
        val bitmap = context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Couldn't open image." }
            BitmapFactory.decodeStream(input)
        } ?: throw IllegalArgumentException("Unsupported or invalid image.")

        val pixels = ByteArray(COLOR_BYTES)
        val scaled = Bitmap.createBitmap(MAP_SIZE, MAP_SIZE, Bitmap.Config.ARGB_8888)

        try {
            // White canvas + aspect-preserving fit, matching the intended map-image UX.
            val canvas = android.graphics.Canvas(scaled)
            canvas.drawColor(android.graphics.Color.WHITE)
            val scale = min(
                MAP_SIZE.toFloat() / bitmap.width.toFloat(),
                MAP_SIZE.toFloat() / bitmap.height.toFloat()
            )
            val w = (bitmap.width * scale).coerceAtLeast(1f)
            val h = (bitmap.height * scale).coerceAtLeast(1f)
            val left = (MAP_SIZE - w) / 2f
            val top = (MAP_SIZE - h) / 2f
            val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(bitmap, null, android.graphics.RectF(left, top, left + w, top + h), paint)

            val argb = IntArray(COLOR_BYTES)
            scaled.getPixels(argb, 0, MAP_SIZE, 0, 0, MAP_SIZE, MAP_SIZE)
            for (i in argb.indices) {
                val c = argb[i]
                if (android.graphics.Color.alpha(c) < 128) {
                    pixels[i] = 0
                } else {
                    pixels[i] = nearestPaletteSlot(c).toByte()
                }
            }
        } finally {
            scaled.recycle()
            bitmap.recycle()
        }

        val colorsTarget = findByteArray(map, "colors")
            ?: throw IllegalArgumentException("This map record does not contain a byte-array named \"colors\".")

        if (colorsTarget.value.size == COLOR_BYTES) {
            System.arraycopy(pixels, 0, colorsTarget.value, 0, COLOR_BYTES)
        } else {
            // A default map is 128x128. Replace an unusual-sized colors tag with
            // a correctly sized array rather than mutating the immutable NbtTag field.
            replaceByteArray(map, "colors", NbtTag.ByteArray(pixels))
        }

        return Result(MAP_SIZE, MAP_SIZE)
    }

    private fun findByteArray(root: NbtTag.Compound, key: String): NbtTag.ByteArray? {
        root.entries[key]?.let { if (it is NbtTag.ByteArray) return it }
        val data = root.entries["data"]
        if (data is NbtTag.Compound) {
            data.entries[key]?.let { if (it is NbtTag.ByteArray) return it }
        }
        return null
    }

    private fun replaceByteArray(root: NbtTag.Compound, key: String, value: NbtTag.ByteArray) {
        if (root.entries[key] is NbtTag.ByteArray) {
            root.entries[key] = value
            return
        }
        val data = root.entries["data"]
        if (data is NbtTag.Compound && data.entries[key] is NbtTag.ByteArray) {
            data.entries[key] = value
            return
        }
        throw IllegalArgumentException("Could not replace the map colors tag.")
    }

    private fun nearestPaletteSlot(argb: Int): Int {
        val r = android.graphics.Color.red(argb)
        val g = android.graphics.Color.green(argb)
        val b = android.graphics.Color.blue(argb)
        // Minecraft map colors use four brightness shades per base color.
        // These multipliers are the standard map-color brightness factors.
        val shadeMultipliers = intArrayOf(180, 220, 255, 135)

        var bestIndex = 1
        var bestDistance = Double.MAX_VALUE
        for (baseIndex in 1 until palette.size) {
            val base = palette[baseIndex]
            val br = (base shr 16) and 0xff
            val bg = (base shr 8) and 0xff
            val bb = base and 0xff

            for (shade in shadeMultipliers.indices) {
                val factor = shadeMultipliers[shade] / 255.0
                val pr = (br * factor).coerceIn(0.0, 255.0)
                val pg = (bg * factor).coerceIn(0.0, 255.0)
                val pb = (bb * factor).coerceIn(0.0, 255.0)
                val dr = r - pr
                val dg = g - pg
                val db = b - pb
                val distance = dr * dr * 0.30 + dg * dg * 0.59 + db * db * 0.11
                if (distance < bestDistance) {
                    bestDistance = distance
                    // Base color slot * 4 + brightness shade.
                    bestIndex = baseIndex * 4 + shade
                }
            }
        }
        return bestIndex
    }

}
