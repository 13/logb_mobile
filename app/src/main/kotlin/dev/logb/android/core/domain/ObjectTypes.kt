package dev.logb.android.core.domain

/** Port of `frontend/src/lib/object-types.ts`: what each type is, and what its entries can be. */
object ObjectTypes {
    val ALL = listOf("car", "e_bike", "bike", "motorcycle", "home", "appliance", "tool", "body", "other")

    val CATEGORIES = listOf(
        "maintenance", "repair", "purchase", "inspection", "modification", "fuel", "other",
        "symptom", "treatment", "appointment", "medication", "reading",
    )

    private val vehicle = listOf("maintenance", "repair", "inspection", "fuel", "reading", "modification", "purchase", "other")
    private val table: Map<String, List<String>> = mapOf(
        "car" to vehicle,
        "e_bike" to vehicle,
        "bike" to listOf("maintenance", "repair", "inspection", "reading", "modification", "purchase", "other"),
        "motorcycle" to vehicle,
        "home" to listOf("maintenance", "repair", "inspection", "modification", "purchase", "other"),
        "appliance" to listOf("maintenance", "repair", "inspection", "reading", "modification", "purchase", "other"),
        "tool" to listOf("maintenance", "repair", "inspection", "reading", "modification", "purchase", "other"),
        "body" to listOf("symptom", "treatment", "appointment", "medication", "other"),
        "other" to CATEGORIES,
    )

    /**
     * What the category picker offers for this type -- plus `current`, always. Filtering is
     * presentation only: an entry logged before its object was re-typed keeps its category.
     */
    fun categoriesFor(type: String, current: String? = null): List<String> {
        val list = table[type] ?: CATEGORIES
        return if (current != null && current !in list) list + current else list
    }

    fun hasFuel(type: String): Boolean = "fuel" in (table[type] ?: emptyList())
}
