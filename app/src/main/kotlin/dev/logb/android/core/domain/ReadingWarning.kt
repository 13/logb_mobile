package dev.logb.android.core.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Port of `frontend/src/lib/reading.ts`: the quick reading form's checks. Neither blocks -- an
 * odometer really can be replaced and a road trip really can add a lot -- but a missing or extra
 * digit is by far the likelier cause, and a wrong reading quietly skews every rate and estimate.
 */
enum class ReadingWarning { Lower, Implausible }

object ReadingChecks {
    /** How many times the recent daily rate a new reading may imply before it is questioned. */
    const val IMPLAUSIBLE_FACTOR = 5L

    /** Below this many units a jump is never questioned, whatever the rate says: 300 km in a day is an ordinary long drive. */
    const val ALWAYS_PLAUSIBLE = 300L

    fun readingWarning(value: Long, date: LocalDate, lastCounter: Long?, lastDate: LocalDate?, ratePerDayMilli: Long?): ReadingWarning? {
        if (lastCounter == null) return null
        if (value < lastCounter) return ReadingWarning.Lower
        if (ratePerDayMilli == null || ratePerDayMilli <= 0 || lastDate == null) return null
        val delta = value - lastCounter
        if (delta <= ALWAYS_PLAUSIBLE) return null
        val days = maxOf(ChronoUnit.DAYS.between(lastDate, date), 1)
        val expected = ratePerDayMilli / 1000.0 * days
        return if (delta > expected * IMPLAUSIBLE_FACTOR) ReadingWarning.Implausible else null
    }
}
