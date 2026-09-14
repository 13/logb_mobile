package dev.logb.android.feature.objects

import dev.logb.android.core.db.T0
import dev.logb.android.core.db.TestDatabase
import dev.logb.android.core.db.act
import dev.logb.android.core.db.obj
import dev.logb.android.core.db.rem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class ObjectsModelTest {
    private val db = TestDatabase.inMemory()
    private val model = ObjectsModel(db) { LocalDate.parse("2026-09-13") }

    @After fun close() = db.close()

    @Test
    fun `cards carry stats and the due count of their own reminders, roots only`() = runTest {
        db.objectDao().upsert(obj("golf", "Golf"), obj("house", "House", type = "home"), obj("garage", "Garage", parent = "house", type = "home"))
        db.activityDao().upsert(act("a1", "golf", "2026-03-01", cost = 18900, counter = 84210), act("a2", "golf", "2026-06-10", cost = 7250, counter = 86000))
        db.reminderDao().upsert(
            rem("due-by-counter", "golf", dueCounter = 85_000),
            rem("not-yet", "golf", dueDate = "2026-12-01"),
            rem("done", "golf", dueDate = "2020-01-01", doneAt = T0),
            rem("house-due", "house", dueDate = "2026-09-01"),
        )
        val cards = model.cards(flowOf(false)).first()
        assertEquals(listOf("Golf", "House"), cards.map { it.name })
        val golf = cards.first { it.name == "Golf" }
        assertEquals(86000, golf.counter); assertEquals("km", golf.counterUnit); assertEquals(26150, golf.totalCostCents)
        assertEquals("2026-06-10", golf.lastActivityDate); assertEquals(1, golf.dueCount)
        assertEquals(1, cards.first { it.name == "House" }.dueCount)
        assertEquals(2, model.totalDue().first())
    }

    @Test
    fun `the archived filter swaps the list`() = runTest {
        db.objectDao().upsert(obj("golf", "Golf"), obj("old", "Old bike", archived = T0))
        assertEquals(listOf("Golf"), model.cards(flowOf(false)).first().map { it.name })
        assertEquals(listOf("Old bike"), model.cards(flowOf(true)).first().map { it.name })
    }
}
