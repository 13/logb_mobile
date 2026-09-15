package dev.logb.android.core.domain

import dev.logb.android.core.network.LogbJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

data class TypeInput(val name: String, val icon: String, val categories: List<String>, val counterUnit: String?)

sealed interface TypeCheck {
    data class Valid(val input: TypeInput) : TypeCheck
    /** [code] is the server's stable code: name_invalid, icon_invalid, categories_invalid, unit_invalid. */
    data class Invalid(val code: String) : TypeCheck
}

/** Port of `logb/src/domain/custom_type.rs`: what a valid own type is. */
object CustomTypes {
    const val MAX_NAME_CHARS = 40
    const val PREFIX = "custom:"
    val ICONS = listOf("document", "camera", "car", "e-bike", "bike", "motorcycle", "home", "appliance", "tool", "body", "object")
    val UNITS = listOf("km", "mi", "h")

    fun normalize(input: TypeInput): TypeCheck {
        val name = input.name.trim()
        if (name.isEmpty() || name.codePointCount(0, name.length) > MAX_NAME_CHARS) return TypeCheck.Invalid("name_invalid")
        if (input.icon !in ICONS) return TypeCheck.Invalid("icon_invalid")
        val categories = normalizeCategories(input.categories) ?: return TypeCheck.Invalid("categories_invalid")
        if (input.counterUnit != null && input.counterUnit !in UNITS) return TypeCheck.Invalid("unit_invalid")
        return TypeCheck.Valid(TypeInput(name, input.icon, categories, input.counterUnit))
    }

    fun normalizeCategories(input: List<String>): List<String>? {
        if (input.isEmpty() || input.any { it !in ObjectTypes.CATEGORIES }) return null
        val out = input.distinct().toMutableList()
        if ("other" !in out) out += "other"
        return out
    }

    fun key(uuid: String): String = PREFIX + uuid

    fun uuidOf(typeKey: String): String? = typeKey.removePrefix(PREFIX).takeIf { typeKey.startsWith(PREFIX) && it.isNotEmpty() }

    private val listSerializer = ListSerializer(String.serializer())
    fun categoriesToJson(list: List<String>): String = LogbJson.encodeToString(listSerializer, list)
    fun categoriesFromJson(text: String): List<String> = runCatching { LogbJson.decodeFromString(listSerializer, text) }.getOrDefault(emptyList())
}
