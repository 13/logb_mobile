package dev.logb.android.core.domain

import dev.logb.android.core.db.entity.ReminderEntity
import dev.logb.android.core.domain.ReminderRules.Every
import dev.logb.android.core.domain.ReminderRules.KIND_READING
import dev.logb.android.core.domain.ReminderRules.parseDate
import java.time.LocalDate

/** A reminder as a screen shows it: the row plus what `api::reminders::ReminderOut::build` derives. */
data class ReminderView(
    val reminder: ReminderEntity,
    val due: Boolean,
    val daysUntil: Long?,
    val counterUntil: Long?,
    val nextDueDate: LocalDate?,
    /** When the object's recent usage says the counter will reach the target. Never makes a reminder due. */
    val estimatedDueDate: LocalDate? = null,
    /** The nearer of the real due date and the usage estimate, in days from today: what the lookahead measures. */
    val soonestDays: Long? = null,
)

object ReminderPresenter {
    /**
     * `currentCounter` and `lastReadingDate` are the object's, as `ObjectDao.stats` reports
     * them. `lastReadingDate` should already exclude readings dated after tomorrow, which the
     * server's `reading_horizon` does; the DAO's `MAX(date)` does not, so the caller clamps.
     */
    fun present(reminder: ReminderEntity, currentCounter: Long?, lastReadingDate: String?, today: LocalDate, usage: Insights.Usage? = null): ReminderView {
        val snoozed = parseDate(reminder.snoozedUntil)
        if (reminder.kind == KIND_READING) {
            val (due, next) = ReminderRules.readingStatus(
                today, parseDate(reminder.dueDate), parseDate(lastReadingDate),
                Every.fromParts(reminder.everyN, reminder.everyUnit), snoozed,
            )
            val days = ReminderRules.daysUntil(today, next)
            return ReminderView(reminder, due = reminder.doneAt == null && due, daysUntil = days, counterUntil = null, nextDueDate = next, soonestDays = days)
        }
        val date = parseDate(reminder.dueDate)
        val due = reminder.doneAt == null && ReminderRules.isDue(today, currentCounter, date, reminder.dueCounter, snoozed)
        // `api::reminders::ReminderOut::build`: the estimate is only ever added, never makes anything due.
        val estimated = if (!due && reminder.dueCounter != null && usage != null) Insights.estimatedDate(usage.last, usage.rateMilli, reminder.dueCounter) else null
        val daysUntil = ReminderRules.daysUntil(today, date)
        val estimatedDays = ReminderRules.daysUntil(today, estimated)
        return ReminderView(
            reminder, due = due,
            daysUntil = daysUntil,
            counterUntil = ReminderRules.counterUntil(currentCounter, reminder.dueCounter),
            nextDueDate = date,
            estimatedDueDate = estimated,
            soonestDays = listOfNotNull(daysUntil, estimatedDays).minOrNull(),
        )
    }

    /** The server ignores a reading dated past tomorrow as the "last reading"; so does this. */
    fun clampLastReading(lastReadingDate: String?, today: LocalDate): String? {
        val d = parseDate(lastReadingDate) ?: return null
        return if (d <= today.plusDays(1)) lastReadingDate else null
    }
}
