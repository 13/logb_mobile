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

    fun plan(items: List<DueItem>, dueWord: String, upcomingWord: (Long) -> String, more: (Int) -> String): DigestNotifications {
        if (items.isEmpty()) return DigestNotifications(null, emptyList())
        val children = items.take(MAX_CHILDREN).map { item ->
            val r = item.view.reminder
            ChildNotification(
                id = idFor(r.uuid), reminderUuid = r.uuid, objectUuid = item.objectUuid,
                title = "${item.objectName}: ${r.title}",
                text = state(item, dueWord, upcomingWord),
                actions = if (r.kind == ReminderRules.KIND_READING) listOf(NotificationAction.LogReading) else listOf(NotificationAction.Done, NotificationAction.Snooze),
            )
        }
        // On Android the group summary sits above its visible children, which already show every
        // item up to MAX_CHILDREN: the summary names only the first and, when some are hidden, how
        // many more -- it never repeats every child the way Digest.text's single-notification body does.
        val summary = items.firstOrNull()?.takeIf { items.size > 1 }?.let { first ->
            val hidden = items.size - MAX_CHILDREN
            val line = "${first.objectName}: ${first.view.reminder.title} ${state(first, dueWord, upcomingWord)}"
            val title = if (hidden > 0) "$line · ${more(hidden)}" else line
            DigestText(title, null)
        }
        return DigestNotifications(summary, children)
    }

    /** Due now, or how soon. `?: 0` guards a directly-constructed view; `DueListModel` always sets `soonestDays` for the upcoming items this reaches. */
    private fun state(item: DueItem, dueWord: String, upcomingWord: (Long) -> String): String =
        if (item.view.due) dueWord else upcomingWord(item.view.soonestDays ?: 0)

    /** Stable per reminder and clear of the summary's id: the whole positive hash space, nudged past 1 rather than folded into a narrow range that would collide often. */
    fun idFor(reminderUuid: String): Int {
        val h = reminderUuid.hashCode() and 0x7FFFFFFF
        return if (h <= SUMMARY_ID) h + 2 else h
    }
}
