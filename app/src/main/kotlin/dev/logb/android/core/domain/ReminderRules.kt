package dev.logb.android.core.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Port of `src/domain/reminder.rs`, line for line. The server answers `due` with these rules;
 * the phone must answer identically from its mirror, or a reminder would be due on one screen
 * and not the other.
 */
object ReminderRules {
    const val KIND_SERVICE = "service"
    const val KIND_READING = "reading"

    /** The largest `every_n` accepted, in either unit. */
    const val MAX_EVERY = 60L

    data class Repeat(val months: Long? = null, val counter: Long? = null)

    /** How often a reading reminder wants a new reading. */
    sealed interface Every {
        data class Week(val n: Long) : Every
        data class Month(val n: Long) : Every

        /** `date` plus the interval. Calendar months clamp to the end of a shorter month. */
        fun after(date: LocalDate): LocalDate = when (this) {
            is Week -> date.plusWeeks(n)
            is Month -> date.plusMonths(n)
        }

        companion object {
            /** None when either part is missing or out of range; such a row is never due rather than guessed at. */
            fun fromParts(n: Long?, unit: String?): Every? {
                if (n == null || n < 1 || n > MAX_EVERY) return null
                return when (unit) {
                    "week" -> Week(n)
                    "month" -> Month(n)
                    else -> null
                }
            }
        }
    }

    /** One interval after the latest reading, but never before the reminder's own start. */
    fun readingNextDue(start: LocalDate, lastReading: LocalDate?, every: Every): LocalDate {
        val next = lastReading?.let(every::after)
        return if (next != null && next > start) next else start
    }

    /** Whether a reading reminder is due, and the date it next wants a reading. */
    fun readingStatus(today: LocalDate, start: LocalDate?, lastReading: LocalDate?, every: Every?, snoozedUntil: LocalDate?): Pair<Boolean, LocalDate?> {
        if (start == null || every == null) return false to null
        val next = readingNextDue(start, lastReading, every)
        return isDue(today, null, next, null, snoozedUntil) to next
    }

    /** Due when the date has arrived or the counter has been reached, unless a snooze is still in effect. */
    fun isDue(today: LocalDate, currentCounter: Long?, dueDate: LocalDate?, dueCounter: Long?, snoozedUntil: LocalDate?): Boolean {
        if (snoozedUntil != null && snoozedUntil > today) return false
        val byDate = dueDate != null && dueDate <= today
        val byCounter = dueCounter != null && currentCounter != null && currentCounter >= dueCounter
        return byDate || byCounter
    }

    /** Already due, or coming due within `withinDays` (strictly in the future); a live snooze suppresses both. */
    fun isUpcoming(today: LocalDate, due: Boolean, daysUntil: Long?, withinDays: Long, snoozedUntil: LocalDate?): Boolean {
        if (snoozedUntil != null && snoozedUntil > today) return false
        return due || (daysUntil != null && daysUntil > 0 && daysUntil <= withinDays)
    }

    /** The (due date, due counter) of the follow-up reminder, or null when nothing repeats. */
    fun nextDue(baseDate: LocalDate, baseCounter: Long?, dueCounter: Long?, repeat: Repeat): Pair<LocalDate?, Long?>? {
        val date = repeat.months?.let { baseDate.plusMonths(it) }
        val counter = repeat.counter?.let { step -> (baseCounter ?: dueCounter)?.let { it + step } }
        return if (date == null && counter == null) null else date to counter
    }

    /** Where a snoozed reminder lands: `days` after the later of today and its current due date. */
    fun snoozedDate(today: LocalDate, current: LocalDate?, days: Long): LocalDate {
        val base = if (current != null && current > today) current else today
        return base.plusDays(days.coerceAtLeast(0))
    }

    /** Days from today until `due`; negative when it has passed. Null when there is no date. */
    fun daysUntil(today: LocalDate, due: LocalDate?): Long? = due?.let { ChronoUnit.DAYS.between(today, it) }

    /** Counter units still to go before `due`; negative when passed. Null without both readings. */
    fun counterUntil(current: Long?, due: Long?): Long? = if (current != null && due != null) due - current else null

    fun parseDate(s: String?): LocalDate? = s?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}
