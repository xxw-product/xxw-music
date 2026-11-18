package com.musicdl.core.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Port of `musicdl/modules/utils/misc.py` JSON helpers:
 * `resp2json`, `safeextractfromdict`, `isvalidresp`.
 */
object JsonUtil {

    val lenient = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    /** Parse a body that may or may not be strict JSON. Returns null instead of throwing. */
    fun parse(text: String?): JsonElement? {
        if (text.isNullOrBlank()) return null
        return runCatching { lenient.parseToJsonElement(text) }.getOrNull()
    }

    fun parseObject(text: String?): JsonObject? = parse(text) as? JsonObject

    /**
     * Port of `safeextractfromdict(data, progressive_keys, default)`.
     * Supports negative list indexes, like the Python original (`['imgItems', -1, 'img']`).
     */
    fun extract(root: Any?, path: List<Any>, default: String? = null): String? {
        var node: Any? = root
        for (key in path) {
            node = when {
                key is Int && node is JsonArray -> node.getOrNull(if (key < 0) node.size + key else key)
                key is Int && node is List<*> -> node.getOrNull(if (key < 0) node.size + key else key)
                node is JsonObject && key is String -> node[key]
                node is Map<*, *> && key is String -> node[key]
                else -> return default
            } ?: return default
        }
        return stringify(node) ?: default
    }

    fun extractElement(root: Any?, path: List<Any>): JsonElement? {
        var node: Any? = root
        for (key in path) {
            node = when {
                key is Int && node is JsonArray -> node.getOrNull(if (key < 0) node.size + key else key)
                key is Int && node is List<*> -> node.getOrNull(if (key < 0) node.size + key else key)
                node is JsonObject && key is String -> node[key]
                node is Map<*, *> && key is String -> node[key]
                else -> return null
            } ?: return null
        }
        return node as? JsonElement
    }

    /** String view of any JSON value, matching Python's `str()` behaviour closely enough for our use. */
    fun stringify(value: Any?): String? = when (value) {
        null, is JsonNull -> null
        is JsonPrimitive -> if (value.isString) value.content else value.content
        is JsonArray, is JsonObject -> value.toString()
        is String -> value
        else -> value.toString()
    }

    fun str(obj: JsonObject?, key: String): String? = stringify(obj?.get(key))

    fun long(obj: JsonObject?, key: String): Long? =
        (obj?.get(key) as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() ?: it.content.toLongOrNull() }

    fun double(obj: JsonObject?, key: String): Double? =
        (obj?.get(key) as? JsonPrimitive)?.let { it.doubleOrNull ?: it.longOrNull?.toDouble() ?: it.content.toDoubleOrNull() }

    fun int(obj: JsonObject?, key: String): Int? = long(obj, key)?.toInt()

    fun bool(obj: JsonObject?, key: String): Boolean? =
        (obj?.get(key) as? JsonPrimitive)?.let { it.booleanOrNull }

    fun array(obj: JsonObject?, key: String): JsonArray? = obj?.get(key) as? JsonArray

    fun obj(obj: JsonObject?, key: String): JsonObject? = obj?.get(key) as? JsonObject

    /** Port of `dedupkeeporder` over an arbitrary key selector. */
    fun <T, K> dedupKeepOrder(items: Iterable<T>, key: (T) -> K?): List<T> {
        val seen = LinkedHashSet<K>()
        val out = ArrayList<T>()
        for (item in items) {
            val k = key(item) ?: continue
            if (seen.add(k)) out.add(item)
        }
        return out
    }
}
