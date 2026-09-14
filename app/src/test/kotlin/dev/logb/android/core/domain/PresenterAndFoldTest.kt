package dev.logb.android.core.domain

import dev.logb.android.core.db.act
import dev.logb.android.core.db.rem
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReminderPresenterTest {
    private val today = LocalDate.parse("2026-09-13")

    @Test fun `a service reminder reports due, days and counter until`() {
        val v = ReminderPresenter.present(rem("r", "o", dueDate = "2026-09-20", dueCounter = 90_000), currentCounter = 86_000, lastReadingDate = null, today = today)
        assertFalse(v.due); assertEquals(7, v.daysUntil); assertEquals(4_000, v.counterUntil); assertEquals(LocalDate.parse("2026-09-20"), v.nextDueDate)
    }

    @Test fun `a done reminder is never due even when its date has passed`() {
        val v = ReminderPresenter.present(rem("r", "o", dueDate = "2020-01-01", doneAt = "t"), null, null, today)
        assertFalse(v.due); assertEquals(-2447, v.daysUntil)
    }

    @Test fun `a reading reminder reports its derived date, not its start`() {
        val reading = rem("r", "o").copy(kind = "reading", everyN = 1, everyUnit = "month", dueDate = "2026-01-01")
        val v = ReminderPresenter.present(reading, null, lastReadingDate = "2026-09-01", today = today)
        assertFalse(v.due); assertEquals(LocalDate.parse("2026-10-01"), v.nextDueDate); assertEquals(18, v.daysUntil); assertNull(v.counterUntil)
    }

    @Test fun `an unparseable due date does not block the counter path`() {
        val v = ReminderPresenter.present(rem("r", "o", dueDate = "not-a-date", dueCounter = 10_000), 10_000, null, today)
        assertTrue(v.due)
    }

    @Test fun `usage estimates a counter target but never makes it due`() {
        val usage = Insights.Usage(Insights.Reading(LocalDate.parse("2026-09-01"), 59_000), 25_000)
        val v = ReminderPresenter.present(rem("r", "o", dueCounter = 60_000), currentCounter = 59_000, lastReadingDate = null, today = today, usage = usage)
        assertFalse(v.due)
        assertEquals(LocalDate.parse("2026-10-11"), v.estimatedDueDate)
        assertEquals(28, v.soonestDays, "no due date: the estimate is what the lookahead sees")
        assertNull(v.daysUntil)
    }

    @Test fun `a due reminder has no estimate and soonest picks the nearer of date and estimate`() {
        val usage = Insights.Usage(Insights.Reading(LocalDate.parse("2026-09-01"), 59_000), 25_000)
        assertNull(ReminderPresenter.present(rem("r", "o", dueCounter = 59_000), 59_000, null, today, usage).estimatedDueDate)
        val far = ReminderPresenter.present(rem("r", "o", dueDate = "2027-01-01", dueCounter = 60_000), 59_000, null, today, usage)
        assertEquals(28, far.soonestDays)
        val near = ReminderPresenter.present(rem("r", "o", dueDate = "2026-09-20", dueCounter = 60_000), 59_000, null, today, usage)
        assertEquals(7, near.soonestDays)
        assertNull(ReminderPresenter.present(rem("r", "o", dueCounter = 60_000), 59_000, null, today, usage = null).estimatedDueDate)
    }

    @Test fun `a reading dated past tomorrow is not the last reading`() {
        assertNull(ReminderPresenter.clampLastReading("2026-09-15", today))
        assertEquals("2026-09-14", ReminderPresenter.clampLastReading("2026-09-14", today))
    }
}

class TimelineFoldTest {
    @Test fun `two or more consecutive readings fold, a single one does not, an entry breaks the run`() {
        val rows = TimelineFold.fold(
            listOf(
                act("a", "o", "2026-09-01", title = "Service"),
                act("r1", "o", "2026-08-01", counter = 86_000, category = "reading"),
                act("r2", "o", "2026-07-01", counter = 85_000, category = "reading"),
                act("b", "o", "2026-06-01", title = "Fuel", category = "fuel"),
                act("r3", "o", "2026-05-01", counter = 84_000, category = "reading"),
            ),
        )
        assertEquals(4, rows.size)
        assertIs<TimelineRow.Entry>(rows[0])
        val run = assertIs<TimelineRow.Readings>(rows[1])
        assertEquals("rr1", run.key); assertEquals(listOf("r1", "r2"), run.readings.map { it.uuid })
        assertIs<TimelineRow.Entry>(rows[2])
        assertIs<TimelineRow.Entry>(rows[3])
        assertEquals(85_000L to 86_000L, TimelineFold.readingSpan(run.readings))
        assertNull(TimelineFold.readingSpan(emptyList()))
    }
}

class ObjectTypesTest {
    @Test fun `a type offers its own categories plus the current one`() {
        assertEquals(listOf("symptom", "treatment", "appointment", "medication", "other"), ObjectTypes.categoriesFor("body"))
        assertEquals(listOf("symptom", "treatment", "appointment", "medication", "other", "fuel"), ObjectTypes.categoriesFor("body", current = "fuel"))
        assertEquals(ObjectTypes.CATEGORIES, ObjectTypes.categoriesFor("other"))
        assertTrue(ObjectTypes.hasFuel("car")); assertFalse(ObjectTypes.hasFuel("bike"))
    }
}
