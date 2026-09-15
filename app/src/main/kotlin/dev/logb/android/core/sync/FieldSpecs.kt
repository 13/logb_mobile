package dev.logb.android.core.sync

/**
 * The whitelist from `src/sync/mod.rs` on the server, with the reference fields typed. A field
 * absent here is not settable over sync, on either side -- the phone ignores it rather than
 * writing a column it does not know.
 */
sealed interface FieldType {
    data object Text : FieldType
    data object Integer : FieldType
    /** An integer on the wire, a uuid in the mirror: translated through the target table's `server_id`. */
    data class Ref(val table: String) : FieldType
}

object FieldSpecs {
    private val specs: Map<String, Map<String, FieldType>> = mapOf(
        "object" to mapOf(
            "name" to FieldType.Text, "type" to FieldType.Text, "counter_unit" to FieldType.Text, "fuel_unit" to FieldType.Text,
            "description" to FieldType.Text, "purchase_date" to FieldType.Text,
            "purchase_price_cents" to FieldType.Integer, "archived_at" to FieldType.Text,
            "cover_attachment_id" to FieldType.Ref("attachments"), "parent_id" to FieldType.Ref("objects"),
            "tags" to FieldType.Text,
        ),
        "activity" to mapOf(
            "date" to FieldType.Text, "category" to FieldType.Text, "title" to FieldType.Text, "notes" to FieldType.Text,
            "counter_value" to FieldType.Integer, "cost_cents" to FieldType.Integer, "quantity_milli" to FieldType.Integer,
            "tags" to FieldType.Text,
        ),
        "reminder" to mapOf(
            "title" to FieldType.Text, "notes" to FieldType.Text, "due_date" to FieldType.Text, "due_counter" to FieldType.Integer,
            "repeat_months" to FieldType.Integer, "repeat_counter" to FieldType.Integer, "done_at" to FieldType.Text,
            "done_activity_id" to FieldType.Ref("activities"), "snoozed_until" to FieldType.Text,
            "every_n" to FieldType.Integer, "every_unit" to FieldType.Text,
        ),
        "attachment" to mapOf("kind" to FieldType.Text, "caption" to FieldType.Text),
        "file" to emptyMap(),
        "object_type" to mapOf(
            "name" to FieldType.Text, "icon" to FieldType.Text, "categories" to FieldType.Text, "counter_unit" to FieldType.Text,
        ),
    )

    fun of(entity: String, field: String): FieldType? = specs[entity]?.get(field)

    val entities: Set<String> get() = specs.keys
}
