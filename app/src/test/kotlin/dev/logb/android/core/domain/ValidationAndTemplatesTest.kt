package dev.logb.android.core.domain

import dev.logb.android.core.db.obj
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ValidationTest {
    private val car = obj("c", "Golf")
    private val house = obj("h", "House", type = "home").copy(counterUnit = null)

    @Test fun `object drafts need a name and known units`() {
        assertEquals(mapOf("name" to "error_required"), Validation.objectDraft(ObjectDraft(name = " ")))
        assertEquals(mapOf("counterUnit" to "error_invalid", "purchaseDate" to "error_date", "purchasePriceCents" to "error_negative"),
            Validation.objectDraft(ObjectDraft(name = "x", counterUnit = "furlongs", purchaseDate = "yesterday", purchasePriceCents = -1)))
        assertEquals(emptyMap(), Validation.objectDraft(ObjectDraft(name = "Golf", fuelUnit = "l", purchaseDate = "2019-03-14")))
    }

    @Test fun `an own type is a valid object type, a bare custom prefix or unknown key is not`() {
        assertEquals(emptyMap(), Validation.objectDraft(ObjectDraft(name = "Boat", type = "custom:" + java.util.UUID.randomUUID())))
        assertEquals(mapOf("type" to "error_invalid"), Validation.objectDraft(ObjectDraft(name = "Boat", type = "custom:")))
        assertEquals(mapOf("type" to "error_invalid"), Validation.objectDraft(ObjectDraft(name = "Boat", type = "boat")))
    }

    @Test fun `entry drafts follow the server's rules`() {
        assertEquals(mapOf("title" to "error_required"), Validation.activityDraft(ActivityDraft(date = "2026-09-14"), car))
        assertEquals(mapOf("counterValue" to "error_no_counter"), Validation.activityDraft(ActivityDraft(date = "2026-09-14", title = "x", counterValue = 5), house))
        assertEquals(mapOf("counterValue" to "error_reading_needs_counter"), Validation.activityDraft(ActivityDraft(date = "2026-09-14", title = "x", category = "reading"), car))
        assertEquals(mapOf("category" to "error_invalid"), Validation.activityDraft(ActivityDraft(date = "2026-09-14", title = "x", category = "dancing"), house))
        assertEquals(emptyMap(), Validation.activityDraft(ActivityDraft(date = "2026-09-14", title = "x", category = "fuel"), house), "per-type lists are presentation only")
        assertEquals(emptyMap(), Validation.activityDraft(ActivityDraft(date = "2026-09-14", title = "Fuel", category = "fuel", counterValue = 1, costCents = 1, quantityMilli = 1), car))
    }

    @Test fun `reminder drafts follow the server's rules`() {
        assertEquals(mapOf("dueDate" to "error_due_required"), Validation.reminderDraft(ReminderDraft(title = "Oil"), car))
        assertEquals(mapOf("dueCounter" to "error_no_counter"), Validation.reminderDraft(ReminderDraft(title = "Oil", dueCounter = 100), house))
        assertEquals(mapOf("everyN" to "error_every"), Validation.reminderDraft(ReminderDraft(title = "r", kind = "reading", everyN = 0, everyUnit = "month"), car))
        assertEquals(mapOf("kind" to "error_no_counter"), Validation.reminderDraft(ReminderDraft(title = "r", kind = "reading", everyN = 1, everyUnit = "month"), house))
        assertEquals(emptyMap(), Validation.reminderDraft(ReminderDraft(title = "Oil", dueDate = "2027-01-01", repeatMonths = 12), car))
    }
}

class ReminderTemplatesTest {
    private val today = LocalDate.parse("2026-09-14")

    @Test fun `a type offers its templates, counter-only ones only with a counter`() {
        assertEquals(listOf("oil", "inspection", "tyres", "reading"), ReminderTemplates.templatesFor("car", "km").map { it.id })
        assertEquals(listOf("oil", "inspection", "tyres"), ReminderTemplates.templatesFor("car", null).map { it.id })
        assertEquals(listOf("service", "inspection"), ReminderTemplates.templatesFor("motorcycle", null).map { it.id }, "the chain template is distance-only")
        assertEquals(emptyList(), ReminderTemplates.templatesFor("other", "km"))
    }

    @Test fun `a ticked template becomes a reminder from today and the current reading`() {
        val oil = ReminderTemplates.templatesFor("car", "km").first()
        val d = ReminderTemplates.toDraft(oil, "Oil change", "km", currentReading = 80_000, today = today)!!
        assertEquals("2027-09-14", d.dueDate); assertEquals(12, d.repeatMonths); assertEquals(95_000, d.dueCounter); assertEquals(15_000, d.repeatCounter)
        val noReading = ReminderTemplates.toDraft(oil, "Oil change", "km", currentReading = null, today = today)!!
        assertNull(noReading.dueCounter); assertEquals("2027-09-14", noReading.dueDate)
        val chain = ReminderTemplates.templatesFor("motorcycle", "km").first { it.id == "chain" }
        assertNull(ReminderTemplates.toDraft(chain, "Chain", "km", currentReading = null, today = today), "distance-only without a reading is skipped")
        val reading = ReminderTemplates.toDraft(ReminderTemplates.templatesFor("car", "km").last(), "Reading", "km", null, today)!!
        assertEquals("reading", reading.kind); assertEquals("2026-10-14", reading.dueDate); assertEquals(1, reading.everyN)
    }
}
