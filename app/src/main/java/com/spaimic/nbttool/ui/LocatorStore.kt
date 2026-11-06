package com.spaimic.nbttool.ui

import android.content.Context
import com.spaimic.nbttool.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A saved map bookmark. [dimension] uses the DB dimension ids: 0 Overworld, 1 Nether, 2 The End. */
data class Locator(
    val id: Long,
    val name: String,
    val x: Int,
    val z: Int,
    val dimension: Int,
    val color: Int
)

object Locators {
    val ICONS = intArrayOf(
        R.drawable.locator_1, R.drawable.locator_2, R.drawable.locator_3, R.drawable.locator_4,
        R.drawable.locator_5, R.drawable.locator_6, R.drawable.locator_7, R.drawable.locator_8
    )
    val COLOR_NAMES = arrayOf("Black", "Green", "Cyan", "Yellow", "Blue", "Red", "Orange", "Magenta")

    fun iconFor(color: Int): Int = ICONS[color.coerceIn(0, ICONS.size - 1)]

    private fun fileFor(context: Context, worldKey: String): File {
        val dir = File(context.filesDir, "locators").apply { mkdirs() }
        return File(dir, worldKey.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json")
    }

    fun load(context: Context, worldKey: String): MutableList<Locator> {
        val out = mutableListOf<Locator>()
        try {
            val f = fileFor(context, worldKey)
            if (!f.exists()) return out
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Locator(
                        o.getLong("id"), o.optString("name", "Locator"),
                        o.getInt("x"), o.getInt("z"),
                        o.optInt("dim", 0), o.optInt("color", 0).coerceIn(0, ICONS.size - 1)
                    )
                )
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun save(context: Context, worldKey: String, list: List<Locator>) {
        try {
            val arr = JSONArray()
            for (l in list) {
                arr.put(
                    JSONObject().put("id", l.id).put("name", l.name).put("x", l.x)
                        .put("z", l.z).put("dim", l.dimension).put("color", l.color)
                )
            }
            fileFor(context, worldKey).writeText(arr.toString())
        } catch (_: Exception) {
        }
    }
}
