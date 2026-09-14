package dev.logb.android.core.sync

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/** Reads a bootstrap row -- the server's columns verbatim, by name -- without guessing at types. */
class RowMapper(private val row: JsonObject) {
    private fun prim(name: String): JsonPrimitive? = (row[name] as? JsonElement)?.takeUnless { it is JsonNull }?.jsonPrimitive

    fun str(name: String): String = prim(name)?.content ?: error("bootstrap row lacks '$name': $row")

    fun strOrNull(name: String): String? = prim(name)?.content

    fun long(name: String): Long = prim(name)?.content?.toLongOrNull() ?: error("bootstrap row lacks integer '$name': $row")

    fun longOrNull(name: String): Long? = prim(name)?.content?.toLongOrNull()

    /** The server stores a boolean-ish column as 0/1; absent means false. */
    fun strOrEmpty(name: String): String = strOrNull(name) ?: ""
}
