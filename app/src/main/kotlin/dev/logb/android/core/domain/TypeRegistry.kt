package dev.logb.android.core.domain

import dev.logb.android.core.db.entity.ObjectTypeEntity

/** Port of `frontend/src/lib/type-registry.ts`: what a type key means, built-in or own. */
class TypeRegistry(val custom: List<ObjectTypeEntity>) {
    private val byKey = custom.associateBy { CustomTypes.key(it.uuid) }

    fun isBuiltin(key: String): Boolean = key in ObjectTypes.ALL

    fun find(key: String): ObjectTypeEntity? = byKey[key]

    fun icon(key: String): String = when {
        isBuiltin(key) -> BUILTIN_ICONS.getValue(key)
        else -> find(key)?.icon ?: "object"
    }

    fun categoriesFor(key: String, current: String? = null): List<String> {
        val list = if (isBuiltin(key)) ObjectTypes.categoriesFor(key) else find(key)?.let { CustomTypes.categoriesFromJson(it.categories) } ?: ObjectTypes.CATEGORIES
        return if (current != null && current !in list) list + current else list
    }

    fun counterUnit(key: String): String? = find(key)?.counterUnit

    companion object {
        val EMPTY = TypeRegistry(emptyList())
        private val BUILTIN_ICONS = mapOf(
            "car" to "car", "e_bike" to "e-bike", "bike" to "bike", "motorcycle" to "motorcycle", "home" to "home",
            "appliance" to "appliance", "tool" to "tool", "body" to "body", "other" to "object",
        )
    }
}
