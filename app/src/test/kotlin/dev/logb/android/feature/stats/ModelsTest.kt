package dev.logb.android.feature.stats

import dev.logb.android.core.db.T0
import dev.logb.android.core.db.TestDatabase
import dev.logb.android.core.db.act
import dev.logb.android.core.db.obj
import dev.logb.android.core.domain.Insights
import dev.logb.android.core.domain.SpendStats
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ModelsTest {
    private val db = TestDatabase.inMemory()
    private val today = LocalDate.parse("2026-09-13")
    private val stats = StatsModel(db)
    private val insights = InsightsModel(db) { today }

    @After fun close() = db.close()

    /** House > Garage > Bulb, plus a car bought 2024-05-01 for 15 000 with fuel, a service and readings. */
    @Before fun seed() = runTest {
        db.objectDao().upsert(
            obj("house", "House", type = "home").copy(counterUnit = null),
            obj("garage", "Garage", parent = "house", type = "other").copy(counterUnit = null),
            obj("bulb", "Bulb", parent = "garage", type = "appliance").copy(counterUnit = null),
            obj("car", "Car").copy(purchaseDate = "2024-05-01", purchasePriceCents = 1_500_000, createdAt = "2024-06-01T00:00:00Z"),
        )
        db.activityDao().upsert(
            act("h1", "house", "2026-01-10", cost = 1_000, category = "repair"),
            act("g1", "garage", "2026-02-10", cost = 200, category = "maintenance"),
            act("b1", "bulb", "2026-03-10", cost = 30, category = "repair"),
            act("f1", "car", "2026-06-01", cost = 5_000, counter = 10_000, category = "fuel").copy(quantityMilli = 45_000),
            act("f2", "car", "2026-07-01", cost = 4_000, counter = 10_400, category = "fuel").copy(quantityMilli = 20_000),
            act("f3", "car", "2026-08-01", cost = 4_000, counter = 10_800, category = "fuel").copy(quantityMilli = 20_000),
            act("s1", "car", "2025-09-01", cost = 20_000, counter = 8_000, category = "maintenance"),
            act("r1", "car", "2026-09-05", counter = 11_000, category = "reading"),
        )
    }

    @Test fun `stats roll the tree up and offer every year`() = runTest {
        val s = stats.stats(null, purchases = false)
        assertEquals(1_000 + 200 + 30 + 5_000 + 4_000 + 4_000 + 20_000L, s.totalCents)
        assertEquals(listOf("2026", "2025"), s.years)
        assertEquals(listOf("Car", "House"), s.byObject.map { it.name })
        assertEquals(1_230, s.byObject[1].costCents)
        assertEquals("Garage", s.byObject[1].children[0].name)
        assertEquals(listOf("car", "home", "other", "appliance"), s.byType.map { it.bucket })
        val y2026 = stats.stats(2026, purchases = false)
        assertEquals(12, y2026.overTime.size)
        assertEquals(1_000, y2026.overTime[0].costCents)
        assertEquals(14_230, y2026.totalCents)
    }

    @Test fun `purchase prices are counted once and only when asked`() = runTest {
        val with = stats.stats(null, purchases = true)
        assertEquals(34_230 + 1_500_000L, with.totalCents)
        assertEquals(SpendStats.PURCHASE_PRICE, with.byCategory[0].bucket)
        assertEquals(listOf("2026", "2025", "2024"), with.years, "the purchase is dated by its purchase date")
        db.activityDao().upsert(act("p1", "car", "2024-05-02", cost = 1_400_000, category = "purchase"))
        val recorded = stats.stats(null, purchases = true)
        assertEquals(34_230 + 1_400_000L, recorded.totalCents, "a costed purchase entry is the purchase")
    }

    @Test fun `a cars insights cover cost, fuel, usage and ownership`() = runTest {
        val i = assertNotNull(insights.insights("car", contents = false))
        assertEquals(listOf("2026" to 13_000L, "2025" to 20_000L), i.byYear.map { it.bucket to it.costCents })
        assertEquals(listOf("maintenance", "fuel"), i.byCategory.map { it.bucket }, "readings left out, largest first")
        assertEquals(8_000L to 11_000L, i.counterSpan)
        assertEquals(33_000L * 1000 / 3_000, i.costPerCounterMilli)
        val fuel = assertNotNull(i.fuel)
        assertEquals("l", fuel.unit)
        assertEquals(85_000, fuel.quantityMilli)
        assertEquals(5_000, fuel.per100Milli)
        assertEquals(8_000L * 1000 / 800, fuel.costPerCounterMilli)
        assertEquals(listOf("2026-07-01", "2026-08-01"), fuel.fills.map { it.date })
        // Rate: 2026-06-01 (10 000) is inside the 180-day window before 2026-09-05 (11 000): 1 000 over 96 days.
        assertEquals(1_000L * 1000 / 96, i.counterPerDayMilli)
        assertEquals(12, i.usageByMonth.size)
        assertEquals(Insights.MonthUsage("2026-09", 200), i.usageByMonth.last())
        assertEquals(Insights.MonthUsage("2026-06", 2_000), i.usageByMonth[8], "measured from the 2025 service's counter")
        assertEquals(Insights.MonthUsage("2026-05", null), i.usageByMonth[7], "a month without a reading claims nothing")
        assertFalse(i.hasContents)
        assertEquals("2024-05-01", i.ownership.since)
        assertEquals(33_000 + 1_500_000L, i.ownership.totalCents)
        assertNotNull(i.ownership.perYearCents)
        assertEquals(12, i.byMonth.size)
        assertEquals(SpendStats.Amount("2026-08", 4_000), i.byMonth[10])
        assertTrue(i.spent)
        assertEquals(Insights.Usage(Insights.Reading(LocalDate.parse("2026-09-05"), 11_000), 1_000L * 1000 / 96), insights.usage("car"))
    }

    @Test fun `a house folds its contents in only when asked and has no counter figures`() = runTest {
        val own = assertNotNull(insights.insights("house", contents = false))
        assertEquals(1_000, own.byYear.single().costCents)
        assertTrue(own.hasContents)
        assertNull(own.counterSpan); assertNull(own.fuel); assertNull(own.counterPerDayMilli); assertTrue(own.usageByMonth.isEmpty())
        assertEquals("2026-01-01", own.ownership.since, "created_at's day when there is no purchase date")
        val all = assertNotNull(insights.insights("house", contents = true))
        assertEquals(1_230, all.byYear.single().costCents)
        assertEquals(1_230, all.ownership.totalCents)
        assertEquals(listOf("repair" to 1_030L, "maintenance" to 200L), all.byCategory.map { it.bucket to it.costCents })
    }

    @Test fun `a deleted object has no insights`() = runTest {
        db.objectDao().upsert(obj("x", "X").copy(deletedAt = T0))
        assertNull(insights.insights("x", false))
    }
}
