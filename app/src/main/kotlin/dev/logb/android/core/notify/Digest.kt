package dev.logb.android.core.notify

import dev.logb.android.feature.reminders.DueItem
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

/** What the daily notification says. */
data class DigestText(val title: String, val body: String?)

/** The wording of the daily digest, pure so the phrasing is tested without a device. */
object Digest {
    /**
     * "Golf: Oil change due · 2 more": the first item (the list is sorted due first, nearest
     * next), then how many others; the body lists the others one per line. Null when there is
     * nothing to say -- the worker then posts nothing rather than an empty card.
     */
    fun text(items: List<DueItem>, dueWord: String, upcomingWord: (Long) -> String, more: (Int) -> String): DigestText? {
        val first = items.firstOrNull() ?: return null
        fun line(item: DueItem): String {
            val state = if (item.view.due) dueWord else upcomingWord(item.view.soonestDays ?: 0)
            return "${item.objectName}: ${item.view.reminder.title} $state"
        }
        val rest = items.drop(1)
        val title = if (rest.isEmpty()) line(first) else "${line(first)} · ${more(rest.size)}"
        return DigestText(title, rest.takeIf { it.isNotEmpty() }?.joinToString("\n") { line(it) })
    }

    /** The next occurrence of `hour:minute` strictly after `now`: today if still ahead, else tomorrow. */
    fun nextRun(now: LocalDateTime, hour: Int, minute: Int): LocalDateTime {
        val today = now.toLocalDate().atTime(LocalTime.of(hour, minute))
        return if (today.isAfter(now)) today else today.plusDays(1)
    }

    fun delayUntil(now: LocalDateTime, hour: Int, minute: Int): Duration = Duration.between(now, nextRun(now, hour, minute))
}
