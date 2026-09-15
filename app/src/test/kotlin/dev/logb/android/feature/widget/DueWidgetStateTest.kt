package dev.logb.android.feature.widget

import dev.logb.android.core.notify.DueFixtures
import org.junit.Assert.assertEquals
import org.junit.Test

class DueWidgetStateTest {
    private fun from(items: List<dev.logb.android.feature.reminders.DueItem>, locked: Boolean = false) =
        DueWidgetStates.from(items, locked, signedIn = true, dueWord = "due", upcomingWord = { "in $it days" })

    @Test fun `up to five rows, count of all`() {
        val items = (1..7).map { DueFixtures.service("r$it", "O$it", "T$it", due = true) }
        val s = from(items)
        assertEquals(7, s.count)
        assertEquals(5, s.rows.size)
    }

    @Test fun `reading rows cannot be marked done`() =
        assertEquals(false, from(listOf(DueFixtures.reading("r1", "Golf", "Mileage", due = true))).rows.single().canMarkDone)

    @Test fun `locked shows the count and no names`() {
        val s = from(listOf(DueFixtures.service("r1", "Golf", "Oil", due = true)), locked = true)
        assertEquals(1, s.count)
        assertEquals(emptyList<WidgetRow>(), s.rows)
    }

    @Test fun `signed out shows nothing`() =
        assertEquals(DueWidgetState(0, emptyList(), locked = false, signedIn = false), DueWidgetStates.from(emptyList(), false, signedIn = false, "due") { "" })

    @Test fun `unknown lock setting counts as locked`() {
        assertEquals(true, DueWidgetStates.resolveLocked(null))
        assertEquals(true, DueWidgetStates.resolveLocked(true))
        assertEquals(false, DueWidgetStates.resolveLocked(false))
    }
}
