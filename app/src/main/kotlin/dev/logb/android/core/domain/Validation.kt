package dev.logb.android.core.domain

import dev.logb.android.core.db.entity.ObjectEntity
import java.time.LocalDate

data class ObjectDraft(
    val name: String = "",
    val type: String = "car",
    val counterUnit: String? = "km",
    val fuelUnit: String? = null,
    val description: String = "",
    val purchaseDate: String? = null,
    val purchasePriceCents: Long? = null,
    val parentUuid: String? = null,
    val tags: List<String>? = null,
)

data class ActivityDraft(
    val date: String,
    val category: String = "maintenance",
    val title: String = "",
    val notes: String = "",
    val counterValue: Long? = null,
    val costCents: Long? = null,
    val quantityMilli: Long? = null,
    val tags: List<String>? = null,
)

data class ReminderDraft(
    val title: String = "",
    val notes: String = "",
    val dueDate: String? = null,
    val dueCounter: Long? = null,
    val repeatMonths: Long? = null,
    val repeatCounter: Long? = null,
    val kind: String = ReminderRules.KIND_SERVICE,
    val everyN: Long? = null,
    val everyUnit: String? = null,
)

/**
 * The server's `validate()` rules, so an offline write is never one the server would refuse
 * when it finally arrives. Errors are keyed by field; the value is a string-resource key.
 */
object Validation {
    val COUNTER_UNITS = listOf("km", "mi", "h")
    val FUEL_UNITS = listOf("l", "gal", "kwh")

    fun isDate(s: String?): Boolean = s != null && runCatching { LocalDate.parse(s) }.isSuccess

    fun objectDraft(d: ObjectDraft): Map<String, String> = buildMap {
        if (d.name.isBlank()) put("name", "error_required")
        // A local registry lookup would wrongly reject an own type deleted on the web: the
        // object itself must stay editable, so only the key shape is checked here.
        if (d.type !in ObjectTypes.ALL && CustomTypes.uuidOf(d.type) == null) put("type", "error_invalid")
        if (d.counterUnit != null && d.counterUnit !in COUNTER_UNITS) put("counterUnit", "error_invalid")
        if (d.fuelUnit != null && d.fuelUnit !in FUEL_UNITS) put("fuelUnit", "error_invalid")
        if (d.purchaseDate != null && !isDate(d.purchaseDate)) put("purchaseDate", "error_date")
        if (d.purchasePriceCents != null && d.purchasePriceCents < 0) put("purchasePriceCents", "error_negative")
    }

    fun activityDraft(d: ActivityDraft, obj: ObjectEntity): Map<String, String> = buildMap {
        if (!isDate(d.date)) put("date", "error_date")
        // The server checks the category against the global list; the per-type lists are presentation only.
        if (d.category !in ObjectTypes.CATEGORIES) put("category", "error_invalid")
        if (d.title.isBlank()) put("title", "error_required")
        if (d.counterValue != null) {
            if (obj.counterUnit == null) put("counterValue", "error_no_counter")
            else if (d.counterValue < 0) put("counterValue", "error_negative")
        }
        if (d.category == "reading" && d.counterValue == null) put("counterValue", "error_reading_needs_counter")
        if (d.costCents != null && d.costCents < 0) put("costCents", "error_negative")
        if (d.quantityMilli != null) {
            if (d.quantityMilli < 0) put("quantityMilli", "error_negative")
            else if (obj.counterUnit == null) put("quantityMilli", "error_no_counter")
        }
    }

    fun reminderDraft(d: ReminderDraft, obj: ObjectEntity): Map<String, String> = buildMap {
        if (d.title.isBlank()) put("title", "error_required")
        if (d.dueDate != null && !isDate(d.dueDate)) put("dueDate", "error_date")
        when (d.kind) {
            ReminderRules.KIND_READING -> {
                if (obj.counterUnit == null) put("kind", "error_no_counter")
                if (ReminderRules.Every.fromParts(d.everyN, d.everyUnit) == null) put("everyN", "error_every")
            }
            ReminderRules.KIND_SERVICE -> {
                if (d.dueDate == null && d.dueCounter == null) put("dueDate", "error_due_required")
                if ((d.dueCounter != null || d.repeatCounter != null) && obj.counterUnit == null) put("dueCounter", "error_no_counter")
                if (d.dueCounter != null && d.dueCounter < 0) put("dueCounter", "error_negative")
                if (d.repeatMonths != null && d.repeatMonths <= 0) put("repeatMonths", "error_positive")
                if (d.repeatCounter != null && d.repeatCounter <= 0) put("repeatCounter", "error_positive")
            }
            else -> put("kind", "error_invalid")
        }
    }
}
