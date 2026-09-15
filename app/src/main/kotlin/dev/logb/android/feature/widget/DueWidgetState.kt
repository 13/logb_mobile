package dev.logb.android.feature.widget

import dev.logb.android.core.domain.ReminderRules
import dev.logb.android.feature.reminders.DueItem

/** One row the widget can draw: never built while locked or signed out. */
data class WidgetRow(
    val reminderUuid: String,
    val objectUuid: String,
    val objectName: String,
    val title: String,
    val whenText: String,
    val canMarkDone: Boolean,
)

data class DueWidgetState(val count: Int, val rows: List<WidgetRow>, val locked: Boolean, val signedIn: Boolean)

/**
 * The widget's own privacy gate. Every reminder's name and title lives only in [rows], so a
 * locked or signed-out state that omits them (rather than trusting the composable to hide what
 * it was handed) is the one place that guarantee can be tested without a device.
 */
object DueWidgetStates {
    /** Same cap as `DigestPlan`: enough for a glance, not a scroll. */
    const val MAX_ROWS = 5

    fun from(items: List<DueItem>, locked: Boolean, signedIn: Boolean, dueWord: String, upcomingWord: (Long) -> String): DueWidgetState {
        if (!signedIn) return DueWidgetState(0, emptyList(), locked = false, signedIn = false)
        if (locked) return DueWidgetState(items.size, emptyList(), locked = true, signedIn = true)
        val rows = items.take(MAX_ROWS).map { item ->
            val r = item.view.reminder
            WidgetRow(
                reminderUuid = r.uuid,
                objectUuid = item.objectUuid,
                objectName = item.objectName,
                title = r.title,
                whenText = if (item.view.due) dueWord else upcomingWord(item.view.soonestDays ?: 0),
                canMarkDone = r.kind != ReminderRules.KIND_READING,
            )
        }
        return DueWidgetState(items.size, rows, locked = false, signedIn = true)
    }

    /** "Cannot be read yet" (the preference still loading, or the read failing) counts as locked: privacy defaults closed, never open. */
    fun resolveLocked(setting: Boolean?): Boolean = setting ?: true
}
