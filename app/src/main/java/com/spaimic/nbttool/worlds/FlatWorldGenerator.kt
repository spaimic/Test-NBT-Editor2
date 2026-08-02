package com.spaimic.nbttool.worlds

import org.json.JSONArray
import org.json.JSONObject

/** One layer of a custom flat world, bottom-to-top order (index 0 = touches bedrock). */
data class FlatLayer(var blockName: String, var count: Int)

/**
 * Bedrock stores flat-world layers as a JSON string (not nested NBT) inside
 * level.dat's "FlatWorldLayers" tag — this predates any in-game UI for
 * customizing it; hand-editing this exact JSON via an NBT editor is the
 * long-standing community technique. Setting it (plus `Generator`=2) only
 * affects how NEWLY generated chunks are created — it can't retroactively
 * reshape terrain that's already been explored/generated.
 */
object FlatWorldGenerator {

    const val GENERATOR_FLAT = 2

    fun toJson(
        layers: List<FlatLayer>,
        biomeId: Int = 1,
        presetId: String = "Overworld",
        worldVersion: String = "version.post_1_18",
        encodingVersion: Int = 6
    ): String {
        val layersArray = JSONArray()
        for (l in layers) {
            layersArray.put(JSONObject().apply {
                put("block_name", l.blockName)
                put("count", l.count)
            })
        }
        return JSONObject().apply {
            put("biome_id", biomeId)
            put("block_layers", layersArray)
            put("encoding_version", encodingVersion)
            put("preset_id", presetId)
            put("world_version", worldVersion)
        }.toString()
    }

    /** Parses an existing FlatWorldLayers JSON string back into editable layers, if present/valid. */
    fun fromJson(json: String): List<FlatLayer>? = try {
        val root = JSONObject(json)
        val arr = root.getJSONArray("block_layers")
        (0 until arr.length()).map { i ->
            val entry = arr.getJSONObject(i)
            FlatLayer(entry.getString("block_name"), entry.getInt("count"))
        }
    } catch (e: Exception) {
        null
    }
}
