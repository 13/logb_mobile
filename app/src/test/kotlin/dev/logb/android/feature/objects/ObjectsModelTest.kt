package dev.logb.android.feature.objects

import dev.logb.android.core.db.T0
import dev.logb.android.core.db.TestDatabase
import dev.logb.android.core.db.act
import dev.logb.android.core.db.entity.ObjectTypeEntity
import dev.logb.android.core.db.obj
import dev.logb.android.core.db.rem
import dev.logb.android.core.domain.TypeRegistry
import kotlinx.coroutines.flow.first
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
    fun `cards carry stats, parent, usage and the due count of their own reminders`() = runTest {
        db.objectDao().upsert(obj("golf", "Golf"), obj("house", "House", type = "home"), obj("garage", "Garage", parent = "house", type = "home"))
        db.activityDao().upsert(act("a1", "golf", "2026-03-01", cost = 18900, counter = 84210), act("a2", "golf", "2026-06-10", cost = 7250, counter = 86000))
        db.reminderDao().upsert(
            rem("due-by-counter", "golf", dueCounter = 85_000),
            rem("not-yet", "golf", dueDate = "2026-12-01"),
            rem("done", "golf", dueDate = "2020-01-01", doneAt = T0),
            rem("house-due", "house", dueDate = "2026-09-01"),
        )
        val cards = model.allCards().first()
        assertEquals(listOf("Garage", "Golf", "House"), cards.map { it.name })
        assertEquals("house", cards.first { it.name == "Garage" }.parentUuid)
        val golf = cards.first { it.name == "Golf" }
        assertEquals(86000, golf.counter); assertEquals("km", golf.counterUnit); assertEquals(26150, golf.totalCostCents)
        assertEquals("2026-06-10", golf.lastActivityDate); assertEquals(1, golf.dueCount)
        assertEquals(1790L * 1000 / 101, golf.counterPerDayMilli, "84 210 to 86 000 over 101 days")
        assertEquals(1, cards.first { it.name == "House" }.dueCount)
        assertEquals(2, model.totalDue().first())
    }

    @Test
    fun `archived objects are carried with their flag`() = runTest {
        db.objectDao().upsert(obj("golf", "Golf"), obj("old", "Old bike", archived = T0))
        val cards = model.allCards().first()
        assertEquals(mapOf("Golf" to false, "Old bike" to true), cards.associate { it.name to it.archived })
    }
}

class SearchTypeLabelTest {
    private val boat = ObjectTypeEntity("u1", 1, "Boat", "tool", """["repair","other"]""", "h", "t", "t", null)
    private val registry = TypeRegistry(listOf(boat))
    private val builtIn: (String) -> String = { it.replaceFirstChar(Char::uppercase) }

    @Test fun `a built-in key gets its label, a known own type its name`() {
        assertEquals("Car", searchTypeLabel("car", registry, builtIn, "Unknown type"))
        assertEquals("Boat", searchTypeLabel(dev.logb.android.core.domain.CustomTypes.key("u1"), registry, builtIn, "Unknown type"))
    }

    @Test fun `a key that is neither built-in nor a known own type is Unknown type, not the built-in fallback`() {
        assertEquals("Unknown type", searchTypeLabel(dev.logb.android.core.domain.CustomTypes.key("gone"), registry, builtIn, "Unknown type"))
    }
}
