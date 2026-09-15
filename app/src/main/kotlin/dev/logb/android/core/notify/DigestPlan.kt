package dev.logb.android.core.notify

import dev.logb.android.core.domain.ReminderRules
import dev.logb.android.feature.reminders.DueItem

enum class NotificationAction { Done, Snooze, LogReading }

data class ChildNotification(val id: Int, val reminderUuid: String, val objectUuid: String, val title: String, val text: String, val actions: List<NotificationAction>)

data class DigestNotifications(val summary: DigestText?, val children: List<ChildNotification>)

/** Which notifications the digest posts and what each can do; pure, so the phrasing and grouping are tested without a device. */
object DigestPlan {
    const val MAX_CHILDREN = 5
    const val SUMMARY_ID = 1
    private const val ID_BASE = 1_000

    fun plan(items: List<DueItem>, dueWord: String, upcomingWord: (Long) -> String, more: (Int) -> String): DigestNotifications {
        if (items.isEmpty()) return DigestNotifications(null, emptyList())
        val children = items.take(MAX_CHILDREN).map { item ->
            val r = item.view.reminder
            ChildNotification(
                id = idFor(r.uuid), reminderUuid = r.uuid, objectUuid = item.objectUuid,
                title = "${item.objectName}: ${r.title}",
                text = if (item.view.due) dueWord else upcomingWord(item.view.soonestDays ?: 0),
                actions = if (r.kind == ReminderRules.KIND_READING) listOf(NotificationAction.LogReading) else listOf(NotificationAction.Done, NotificationAction.Snooze),
            )
        }
        val summary = if (items.size > 1) Digest.text(items, dueWord, upcomingWord, more) else null
        return DigestNotifications(summary, children)
    }

    /** Stable per reminder and clear of the summary's id. */
    fun idFor(reminderUuid: String): Int = ID_BASE + (reminderUuid.hashCode() and 0x7FFFFFFF) % 1_000_000
}
