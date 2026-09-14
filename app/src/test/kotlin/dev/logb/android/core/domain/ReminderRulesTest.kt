package dev.logb.android.core.domain

import dev.logb.android.core.domain.ReminderRules.Every
import dev.logb.android.core.domain.ReminderRules.Repeat
import dev.logb.android.core.domain.ReminderRules.counterUntil
import dev.logb.android.core.domain.ReminderRules.daysUntil
import dev.logb.android.core.domain.ReminderRules.isDue
import dev.logb.android.core.domain.ReminderRules.isUpcoming
import dev.logb.android.core.domain.ReminderRules.nextDue
import dev.logb.android.core.domain.ReminderRules.readingNextDue
import dev.logb.android.core.domain.ReminderRules.readingStatus
import dev.logb.android.core.domain.ReminderRules.snoozedDate
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Each test is one of `src/domain/reminder.rs`'s, transliterated. */
class ReminderRulesTest {
    private fun d(s: String): LocalDate = LocalDate.parse(s)

    @Test fun `due by date`() {
        assertTrue(isDue(d("2026-09-04"), null, d("2026-09-04"), null, null))
        assertTrue(isDue(d("2026-09-04"), null, d("2026-01-01"), null, null))
        assertFalse(isDue(d("2026-09-04"), null, d("2026-09-05"), null, null))
    }

    @Test fun `due by counter needs a reading`() {
        assertTrue(isDue(d("2026-09-04"), 10_000, null, 10_000, null))
        assertFalse(isDue(d("2026-09-04"), 9_999, null, 10_000, null))
        assertFalse(isDue(d("2026-09-04"), null, null, 10_000, null))
    }

    @Test fun `either condition suffices`() {
        assertTrue(isDue(d("2026-09-04"), 0, d("2020-01-01"), 10_000, null))
        assertTrue(isDue(d("2020-01-01"), 20_000, d("2030-01-01"), 10_000, null))
    }

    @Test fun `a future snooze suppresses a date-due and a counter-due reminder`() {
        assertFalse(isDue(d("2026-09-06"), null, d("2020-01-01"), null, d("2026-09-13")))
        assertFalse(isDue(d("2026-09-06"), 10_000, null, 10_000, d("2026-09-13")))
    }

    @Test fun `a lapsed snooze suppresses nothing`() {
        assertTrue(isDue(d("2026-09-06"), 10_000, null, 10_000, d("2026-09-06")))
        assertTrue(isDue(d("2026-09-06"), 10_000, null, 10_000, d("2020-01-01")))
        assertTrue(isDue(d("2026-09-06"), null, d("2020-01-01"), null, d("2026-09-06")))
    }

    @Test fun `next due from completion point`() {
        assertEquals(d("2027-09-04") to 62_000L, nextDue(d("2026-09-04"), 52_000, 50_000, Repeat(months = 12, counter = 10_000)))
        assertEquals(d("2027-02-28") to null, nextDue(d("2026-08-31"), null, null, Repeat(months = 6)))
        assertEquals(null to 55_000L, nextDue(d("2026-09-04"), null, 50_000, Repeat(counter = 5_000)))
    }

    @Test fun `no repeat means no follow-up`() {
        assertNull(nextDue(d("2026-09-04"), 1, 1, Repeat()))
        assertNull(nextDue(d("2026-09-04"), null, null, Repeat(counter = 10)))
    }

    @Test fun `snooze runs from today when overdue, from the due date when ahead, and gives a counter-only reminder a date`() {
        assertEquals(d("2026-09-13"), snoozedDate(d("2026-09-06"), d("2026-06-01"), 7))
        assertEquals(d("2026-10-08"), snoozedDate(d("2026-09-06"), d("2026-10-01"), 7))
        assertEquals(d("2026-09-13"), snoozedDate(d("2026-09-06"), null, 7))
    }

    @Test fun `days until has no value without a due date and counts both ways`() {
        assertNull(daysUntil(d("2026-09-06"), null))
        assertEquals(0, daysUntil(d("2026-09-06"), d("2026-09-06")))
        assertEquals(10, daysUntil(d("2026-09-06"), d("2026-09-16")))
        assertEquals(-10, daysUntil(d("2026-09-06"), d("2026-08-27")))
    }

    @Test fun `counter until needs both readings and counts both ways`() {
        assertNull(counterUntil(null, 10_000)); assertNull(counterUntil(9_000, null)); assertNull(counterUntil(null, null))
        assertEquals(1_000, counterUntil(9_000, 10_000))
        assertEquals(-500, counterUntil(10_500, 10_000))
    }

    @Test fun `is upcoming includes the due flag, is inclusive of within_days, excludes past and today`() {
        assertTrue(isUpcoming(d("2026-09-06"), true, null, 30, null))
        assertTrue(isUpcoming(d("2026-09-06"), true, -5, 30, null))
        assertTrue(isUpcoming(d("2026-09-06"), false, 30, 30, null))
        assertFalse(isUpcoming(d("2026-09-06"), false, 31, 30, null))
        assertFalse(isUpcoming(d("2026-09-06"), false, 0, 30, null))
        assertFalse(isUpcoming(d("2026-09-06"), false, -1, 30, null))
        assertFalse(isUpcoming(d("2026-09-06"), false, null, 30, null))
    }

    @Test fun `a live snooze takes a future reminder out of the lookahead, a lapsed one does not`() {
        val today = d("2026-09-06")
        assertTrue(isUpcoming(today, false, 5, 30, null))
        assertFalse(isUpcoming(today, false, 5, 30, d("2026-09-13")))
        assertTrue(isUpcoming(today, false, 5, 30, today))
        assertTrue(isUpcoming(today, false, 5, 30, d("2026-09-01")))
    }

    @Test fun `every reads only complete in-range intervals`() {
        assertEquals(Every.Month(1), Every.fromParts(1, "month"))
        assertEquals(Every.Week(2), Every.fromParts(2, "week"))
        assertNull(Every.fromParts(0, "month")); assertNull(Every.fromParts(61, "month")); assertNull(Every.fromParts(-1, "week"))
        assertNull(Every.fromParts(1, "day")); assertNull(Every.fromParts(null, "month")); assertNull(Every.fromParts(1, null))
    }

    @Test fun `a monthly interval clamps to the end of a short month`() {
        assertEquals(d("2026-02-28"), Every.Month(1).after(d("2026-01-31")))
        assertEquals(d("2027-01-08"), Every.Week(2).after(d("2026-12-25")))
    }

    @Test fun `reading next due - no reading means the start, a reading moves it, an old reading never pulls it earlier`() {
        assertEquals(d("2026-10-01"), readingNextDue(d("2026-10-01"), null, Every.Month(1)))
        assertEquals(d("2026-10-20"), readingNextDue(d("2026-09-01"), d("2026-09-20"), Every.Month(1)))
        assertEquals(d("2026-10-01"), readingNextDue(d("2026-10-01"), d("2025-01-01"), Every.Month(1)))
    }

    @Test fun `a reading reminder is due once the interval has passed, a snooze hides it, an unusable row is never due`() {
        val every = Every.Month(1)
        val start = d("2026-01-01")
        assertEquals(true to d("2026-09-13"), readingStatus(d("2026-09-13"), start, d("2026-08-13"), every, null))
        assertEquals(false to d("2026-09-13"), readingStatus(d("2026-09-12"), start, d("2026-08-13"), every, null))
        val (due, next) = readingStatus(d("2026-09-13"), d("2026-01-01"), null, Every.Week(1), d("2026-09-20"))
        assertFalse(due); assertEquals(d("2026-01-01"), next)
        assertEquals(false to null, readingStatus(d("2026-09-13"), null, null, Every.Month(1), null))
        assertEquals(false to null, readingStatus(d("2026-09-13"), d("2020-01-01"), null, null, null))
    }
}
