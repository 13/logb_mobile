package dev.logb.android.feature.objects

import dev.logb.android.core.db.T0
import dev.logb.android.core.db.TestDatabase
import dev.logb.android.core.db.act
import dev.logb.android.core.db.obj
import dev.logb.android.core.db.rem
import dev.logb.android.core.domain.TimelineRow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ObjectDetailModelTest {
    private val db = TestDatabase.inMemory()

    @After fun close() = db.close()

    @Test
    fun `the timeline groups by year newest first, folds readings, and the filter keeps only one category`() = runTest {
        db.objectDao().upsert(obj("golf", "Golf"))
        db.activityDao().upsert(
            act("s26", "golf", "2026-03-01", title = "Service"),
            act("r1", "golf", "2026-02-01", counter = 86_000, category = "reading"),
            act("r2", "golf", "2026-01-01", counter = 85_000, category = "reading"),
            act("f25", "golf", "2025-12-24", title = "Fuel", category = "fuel", cost = 7000),
        )
        val model = ObjectDetailModel(db, "golf") { LocalDate.parse("2026-09-13") }
        val s = model.state(flowOf(null), "EUR").first()
        assertEquals(listOf("2026", "2025"), s.years.map { it.year })
        assertEquals(2, s.years[0].rows.size)
        assertIs<TimelineRow.Readings>(s.years[0].rows[1])
        assertEquals(listOf("maintenance", "fuel", "reading"), s.categories)
        val filtered = model.state(flowOf("fuel"), "EUR").first()
        assertEquals(listOf("2025"), filtered.years.map { it.year })
        assertEquals("fuel", filtered.categoryFilter)
    }

    @Test
    fun `ancestors, children and reminders come along, due ones first`() = runTest {
        db.objectDao().upsert(obj("house", "House", type = "home"), obj("garage", "Garage", parent = "house", type = "home"), obj("light", "Light", parent = "garage", type = "appliance"))
        db.reminderDao().upsert(rem("later", "garage", dueDate = "2026-12-01"), rem("now", "garage", dueDate = "2026-09-01"), rem("done", "garage", doneAt = T0))
        val s = ObjectDetailModel(db, "garage") { LocalDate.parse("2026-09-13") }.state(flowOf(null), "EUR").first()
        assertEquals(listOf("House"), s.ancestors.map { it.name })
        assertEquals(listOf("Light"), s.children.map { it.name })
        assertEquals(listOf("now", "later"), s.openReminders.map { it.reminder.uuid })
        assertTrue(s.openReminders[0].due)
        assertEquals(listOf("done"), s.doneReminders.map { it.reminder.uuid })
    }
}
