package com.spaimic.nbttool.nbt

import org.json.JSONArray
import org.json.JSONObject

/**
 * Converts NbtTag <-> JSON. Plain JSON only has one numeric type, so every
 * value is wrapped with an explicit "type" field (using the same names
 * NbtTag.typeName() already shows in the tree view — Byte, Short, Int, Long,
 * Float, Double, String, ByteArray, IntArray, LongArray, List, Compound) —
 * this makes round-tripping exact and gives a clear, specific error message
 * when hand-edited JSON doesn't match what's expected, rather than silently
 * guessing a type or importing something subtly wrong.
 *
 * Shape:
 *   {"type": "Compound", "value": {"key": <tag>, ...}}
 *   {"type": "List", "elementType": "Byte", "value": [<tag>, ...]}
 *   {"type": "Byte"|"Short"|"Int"|"Long"|"Float"|"Double", "value": <number>}
 *   {"type": "String", "value": "..."}
 *   {"type": "ByteArray"|"IntArray"|"LongArray", "value": [<number>, ...]}
 */
object NbtJson {

    fun exportCompoundToJsonString(compound: NbtTag.Compound): String =
        toJson(compound).toString(2)

    /** Throws a descriptive exception on any structural problem — nothing is
     * ever partially imported; the caller only replaces data after this fully
     * succeeds. */
    fun importCompoundFromJsonString(json: String): NbtTag.Compound {
        val obj = JSONObject(json)
        val tag = fromJson(obj)
        return tag as? NbtTag.Compound
            ?: throw IllegalArgumentException("Root of the JSON must be a Compound (got ${NbtTag.typeName(tag.id)})")
    }

    fun toJson(tag: NbtTag): JSONObject {
        val out = JSONObject()
        out.put("type", NbtTag.typeName(tag.id))
        when (tag) {
            is NbtTag.Byte -> out.put("value", tag.value.toInt())
            is NbtTag.Short -> out.put("value", tag.value.toInt())
            is NbtTag.Int -> out.put("value", tag.value)
            is NbtTag.Long -> out.put("value", tag.value)
            is NbtTag.Float -> out.put("value", tag.value.toDouble())
            is NbtTag.Double -> out.put("value", tag.value)
            is NbtTag.Str -> out.put("value", tag.value)
            is NbtTag.ByteArray -> out.put("value", JSONArray(tag.value.map { it.toInt() }))
            is NbtTag.IntArray -> out.put("value", JSONArray(tag.value.toList()))
            is NbtTag.LongArray -> out.put("value", JSONArray(tag.value.toList()))
            is NbtTag.ListTag -> {
                out.put("elementType", NbtTag.typeName(tag.elementTypeId))
                out.put("value", JSONArray(tag.items.map { toJson(it) }))
            }
            is NbtTag.Compound -> {
                val obj = JSONObject()
                for ((k, v) in tag.entries) obj.put(k, toJson(v))
                out.put("value", obj)
            }
            NbtTag.End -> {}
        }
        return out
    }

    fun fromJson(obj: JSONObject): NbtTag {
        val typeName = obj.optString("type", null) ?: throw IllegalArgumentException("Missing \"type\" field")
        val typeId = typeIdForName(typeName)
        if (!obj.has("value")) throw IllegalArgumentException("Missing \"value\" field for type \"$typeName\"")
        return when (typeId) {
            1 -> NbtTag.Byte(obj.getInt("value").toByte())
            2 -> NbtTag.Short(obj.getInt("value").toShort())
            3 -> NbtTag.Int(obj.getInt("value"))
            4 -> NbtTag.Long(obj.getLong("value"))
            5 -> NbtTag.Float(obj.getDouble("value").toFloat())
            6 -> NbtTag.Double(obj.getDouble("value"))
            8 -> NbtTag.Str(obj.getString("value"))
            7 -> {
                val arr = obj.getJSONArray("value")
                NbtTag.ByteArray(kotlin.ByteArray(arr.length()) { arr.getInt(it).toByte() })
            }
            11 -> {
                val arr = obj.getJSONArray("value")
                NbtTag.IntArray(IntArray(arr.length()) { arr.getInt(it) })
            }
            12 -> {
                val arr = obj.getJSONArray("value")
                NbtTag.LongArray(LongArray(arr.length()) { arr.getLong(it) })
            }
            9 -> {
                val elementTypeName = obj.optString("elementType", null)
                    ?: throw IllegalArgumentException("List is missing \"elementType\"")
                val elementTypeId = typeIdForName(elementTypeName)
                val arr = obj.getJSONArray("value")
                val items = (0 until arr.length()).map { i ->
                    val itemObj = arr.optJSONObject(i)
                        ?: throw IllegalArgumentException("List item $i is not a tag object")
                    fromJson(itemObj)
                }
                NbtTag.ListTag(elementTypeId, items.toMutableList())
            }
            10 -> {
                val obj2 = obj.getJSONObject("value")
                val entries = LinkedHashMap<String, NbtTag>()
                val keys = obj2.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val childObj = obj2.optJSONObject(k)
                        ?: throw IllegalArgumentException("Key \"$k\" is not a tag object")
                    entries[k] = fromJson(childObj)
                }
                NbtTag.Compound(entries)
            }
            else -> throw IllegalArgumentException("Unsupported type \"$typeName\"")
        }
    }

    private fun typeIdForName(name: String): Int = when (name) {
        "Byte" -> 1; "Short" -> 2; "Int" -> 3; "Long" -> 4; "Float" -> 5; "Double" -> 6
        "ByteArray" -> 7; "String" -> 8; "List" -> 9; "Compound" -> 10; "IntArray" -> 11; "LongArray" -> 12
        else -> throw IllegalArgumentException("Unknown NBT type name \"$name\"")
    }
}
