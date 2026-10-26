package com.salla.push.internal.payload

import com.salla.push.internal.util.MAX_JSON_DEPTH
import com.salla.push.internal.util.keyList
import org.json.JSONArray
import org.json.JSONObject

/**
 * Converts org.json values into plain Kotlin values (Map, List, String, Number, Boolean, null), keeping JSON types.
 * Conversion stops at [MAX_JSON_DEPTH]: deeper subtrees are dropped.
 */
internal object JsonConvert {

    fun toMap(json: JSONObject): Map<String, Any?> = convertObject(json, 1)

    private fun convertObject(json: JSONObject, depth: Int): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        for (key in json.keyList()) {
            val converted = convertValue(json.opt(key), depth)
            if (converted !== Dropped) result[key] = converted
        }
        return result
    }

    private fun convertArray(json: JSONArray, depth: Int): List<Any?> {
        val result = ArrayList<Any?>(json.length())
        for (index in 0 until json.length()) {
            val converted = convertValue(json.opt(index), depth)
            if (converted !== Dropped) result.add(converted)
        }
        return result
    }

    private fun convertValue(value: Any?, depth: Int): Any? = when (value) {
        null -> null
        JSONObject.NULL -> null
        is JSONObject -> if (depth >= MAX_JSON_DEPTH) Dropped else convertObject(value, depth + 1)
        is JSONArray -> if (depth >= MAX_JSON_DEPTH) Dropped else convertArray(value, depth + 1)
        is String, is Boolean -> value
        is Number -> value
        else -> value.toString()
    }

    /** Marks a value that was cut by the depth limit. */
    private object Dropped
}
