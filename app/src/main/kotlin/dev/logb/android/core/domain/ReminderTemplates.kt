package dev.logb.android.core.domain

import java.time.LocalDate

/** Port of `frontend/src/lib/reminder-templates.ts`: reminders a new object can start with. Offered, never created unasked. */
data class ReminderTemplate(
    val id: String,
    /** Resource-key suffix of the title: `template_oil` and so on; `reading` for the reading reminder. */
    val titleKey: String,
    val months: Long? = null,
    val counter: Map<String, Long> = emptyMap(),
    val reading: Boolean = false,
)

object ReminderTemplates {
    private val READING = ReminderTemplate("reading", "template_reading", reading = true)

    private val table: Map<String, List<ReminderTemplate>> = mapOf(
        "car" to listOf(
            ReminderTemplate("oil", "template_oil", months = 12, counter = mapOf("km" to 15_000, "mi" to 10_000)),
            ReminderTemplate("inspection", "template_inspection", months = 24),
            ReminderTemplate("tyres", "template_tyres", months = 6),
            READING,
        ),
        "motorcycle" to listOf(
            ReminderTemplate("service", "template_service", months = 12, counter = mapOf("km" to 6_000, "mi" to 4_000)),
            ReminderTemplate("chain", "template_chain", counter = mapOf("km" to 1_000, "mi" to 600)),
            ReminderTemplate("inspection", "template_inspection", months = 24),
            READING,
        ),
        "e_bike" to listOf(
            ReminderTemplate("service", "template_service", months = 12, counter = mapOf("km" to 2_000, "mi" to 1_250)),
            ReminderTemplate("chain", "template_chain", months = 3),
            READING,
        ),
        "bike" to listOf(
            ReminderTemplate("service", "template_service", months = 12),
            ReminderTemplate("chain", "template_chain", months = 3),
        ),
        "home" to listOf(
            ReminderTemplate("smoke", "template_smoke", months = 12),
            ReminderTemplate("heating", "template_heating", months = 12),
        ),
        "appliance" to listOf(
            ReminderTemplate("descale", "template_descale", months = 3),
            ReminderTemplate("filter", "template_filter", months = 6),
        ),
        "tool" to listOf(
            ReminderTemplate("service", "template_service", months = 12, counter = mapOf("h" to 50)),
            READING,
        ),
        "body" to listOf(
            ReminderTemplate("checkup", "template_checkup", months = 12),
            ReminderTemplate("dentist", "template_dentist", months = 6),
        ),
        "other" to emptyList(),
    )

    fun counterStep(t: ReminderTemplate, unit: String?): Long? = unit?.let { t.counter[it] }

    /** A template that only makes sense with a counter -- a reading, or one due by distance alone -- needs the object to have one. */
    fun templatesFor(type: String, unit: String?): List<ReminderTemplate> = (table[type] ?: emptyList()).filter { t ->
        if (t.reading) unit != null else t.months != null || counterStep(t, unit) != null
    }

    /**
     * The reminder a ticked template becomes, or null when it cannot be made right: a distance
     * step without a known current reading, or a reading reminder without a counter.
     */
    fun toDraft(t: ReminderTemplate, title: String, unit: String?, currentReading: Long?, today: LocalDate): ReminderDraft? {
        if (t.reading) return if (unit != null) ReminderDraft(title = title, kind = ReminderRules.KIND_READING, dueDate = today.plusMonths(1).toString(), everyN = 1, everyUnit = "month") else null
        val step = counterStep(t, unit)
        val dueCounter = if (step != null && currentReading != null) currentReading + step else null
        val dueDate = t.months?.let { today.plusMonths(it).toString() }
        if (dueDate == null && dueCounter == null) return null
        return ReminderDraft(title = title, dueDate = dueDate, repeatMonths = t.months, dueCounter = dueCounter, repeatCounter = if (dueCounter != null) step else null)
    }
}
