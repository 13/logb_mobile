package dev.logb.android.core.domain

import dev.logb.android.core.domain.SpendStats.Amount
import dev.logb.android.core.domain.SpendStats.ObjectRow
import dev.logb.android.core.domain.SpendStats.PURCHASE_PRICE
import dev.logb.android.core.domain.SpendStats.Spend
import dev.logb.android.core.domain.SpendStats.dayOf
import dev.logb.android.core.domain.SpendStats.monthsEnding
import dev.logb.android.core.domain.SpendStats.ownership
import dev.logb.android.core.domain.SpendStats.purchaseSpend
import dev.logb.android.core.domain.SpendStats.summarize
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every test in `src/domain/stats.rs`, with the same figures; ids are strings here. */
class SpendStatsTest {
    private fun obj(id: String, parent: String?, name: String, kind: String) =
        ObjectRow(id, parent, name, kind, archived = false, purchaseDate = null, purchasePriceCents = null, createdAt = "2024-01-01T10:00:00Z")

    private fun spend(objectId: String, month: String, category: String, cents: Long) = Spend(objectId, month, category, cents)
    private fun amounts(list: List<Amount>) = list.map { it.bucket to it.costCents }
    private fun day(s: String) = LocalDate.parse(s)

    /** House (1) > Garage (2) > Bulb (3), plus a car (4). */
    private fun tree() = listOf(obj("1", null, "House", "home"), obj("2", "1", "Garage", "other"), obj("3", "2", "Bulb", "appliance"), obj("4", null, "Car", "car"))

    @Test fun `a parent carries every descendants spend`() {
        val rows = listOf(spend("1", "2026-01", "repair", 1_000), spend("2", "2026-02", "maintenance", 200), spend("3", "2026-03", "repair", 30), spend("4", "2026-01", "fuel", 500))
        val s = summarize(tree(), rows, null)
        assertEquals(1_730, s.totalCents)
        assertEquals(2, s.byObject.size, "two roots")
        val house = s.byObject[0]
        assertEquals("House" to 1_230L, house.name to house.costCents, "largest first")
        assertEquals("Garage" to 230L, house.children[0].name to house.children[0].costCents)
        assertEquals("Bulb" to 30L, house.children[0].children[0].name to house.children[0].children[0].costCents)
        assertEquals("Car" to 500L, s.byObject[1].name to s.byObject[1].costCents)
    }

    @Test fun `a subtree that spent nothing is left out`() {
        val s = summarize(tree(), listOf(spend("4", "2026-01", "fuel", 500)), null)
        assertEquals(listOf("Car"), s.byObject.map { it.name })
    }

    @Test fun `an object whose parent is not in the list is a root`() {
        val s = summarize(listOf(obj("2", "99", "Garage", "other")), listOf(spend("2", "2026-01", "repair", 70)), null)
        assertEquals("Garage", s.byObject[0].name)
    }

    @Test fun `type is each objects own not its roots`() {
        val s = summarize(tree(), listOf(spend("1", "2026-01", "repair", 1_000), spend("3", "2026-01", "repair", 30)), null)
        assertEquals(listOf("home" to 1_000L, "appliance" to 30L), amounts(s.byType))
    }

    @Test fun `all years draws one bar per year oldest first and lists years newest first`() {
        val rows = listOf(spend("4", "2024-05", "fuel", 10), spend("4", "2026-01", "fuel", 20), spend("4", "2026-09", "fuel", 5))
        val s = summarize(tree(), rows, null)
        assertEquals(listOf("2024" to 10L, "2026" to 25L), amounts(s.overTime), "no bar for a year with no spend")
        assertEquals(listOf("2026", "2024"), s.years)
    }

    @Test fun `one year draws all twelve months and filters every block`() {
        val rows = listOf(spend("4", "2025-12", "fuel", 999), spend("4", "2026-01", "fuel", 20), spend("1", "2026-03", "repair", 7))
        val s = summarize(tree(), rows, 2026)
        assertEquals(12, s.overTime.size)
        assertEquals(Amount("2026-01", 20), s.overTime[0])
        assertEquals(Amount("2026-02", 0), s.overTime[1], "known zero, not missing")
        assertEquals("2026-12", s.overTime[11].bucket)
        assertEquals(27, s.totalCents)
        assertEquals(listOf("fuel" to 20L, "repair" to 7L), amounts(s.byCategory))
        assertEquals(listOf("2026", "2025"), s.years, "the year list ignores the filter")
    }

    @Test fun `categories are sorted largest first and zero rows dropped`() {
        val rows = listOf(spend("4", "2026-01", "fuel", 5), spend("4", "2026-01", "repair", 50), spend("4", "2026-01", "other", 0))
        assertEquals(listOf("repair" to 50L, "fuel" to 5L), amounts(summarize(tree(), rows, null).byCategory))
    }

    @Test fun `archived is carried through`() {
        val objects = tree().toMutableList().also { it[3] = it[3].copy(archived = true) }
        assertTrue(summarize(objects, listOf(spend("4", "2026-01", "fuel", 5)), null).byObject[0].archived)
    }

    @Test fun `nothing spent is an empty but complete answer`() {
        val s = summarize(tree(), emptyList(), 2026)
        assertEquals(0, s.totalCents)
        assertTrue(s.years.isEmpty() && s.byObject.isEmpty() && s.byType.isEmpty() && s.byCategory.isEmpty())
        assertEquals(12, s.overTime.size)
    }

    @Test fun `a purchase price is dated by its purchase date`() {
        val house = obj("1", null, "House", "home").copy(purchaseDate = "2019-06-30", purchasePriceCents = 30_000_000)
        assertEquals(listOf(spend("1", "2019-06", PURCHASE_PRICE, 30_000_000)), purchaseSpend(listOf(house), emptySet()))
    }

    @Test fun `a purchase price without a date falls back to when the object was created`() {
        val car = obj("4", null, "Car", "car").copy(purchasePriceCents = 1_500_000, createdAt = "2023-11-02T08:00:00Z")
        assertEquals(listOf(spend("4", "2023-11", PURCHASE_PRICE, 1_500_000)), purchaseSpend(listOf(car), emptySet()))
    }

    @Test fun `a purchase price is skipped when a costed purchase entry already records it`() {
        val car = obj("4", null, "Car", "car").copy(purchasePriceCents = 1_500_000)
        assertTrue(purchaseSpend(listOf(car), setOf("4")).isEmpty())
    }

    @Test fun `no price or a zero price adds nothing`() {
        val free = obj("5", null, "Gift", "tool").copy(purchasePriceCents = 0)
        assertTrue(purchaseSpend(listOf(obj("4", null, "Car", "car"), free), emptySet()).isEmpty())
    }

    @Test fun `a typo year is still well formed and stays offered and selectable`() {
        val rows = listOf(spend("4", "0202-05", "fuel", 40))
        assertEquals(listOf("0202"), summarize(tree(), rows, null).years)
        val s = summarize(tree(), rows, 202)
        assertEquals(12, s.overTime.size)
        assertEquals(Amount("0202-05", 40), s.overTime[4])
        assertEquals(40, s.totalCents)
    }

    @Test fun `a malformed month is ignored everywhere`() {
        val s = summarize(tree(), listOf(spend("4", "202-0", "fuel", 10), spend("4", "2026-5", "fuel", 20)), null)
        assertTrue(s.years.isEmpty(), "not counted towards years")
        assertEquals(0, s.totalCents)
        assertTrue(s.byObject.isEmpty() && s.byCategory.isEmpty() && s.byType.isEmpty())
    }

    @Test fun `the month window ends with this month and crosses a year`() {
        val totals = listOf("2025-11" to 500L, "2026-02" to 70L, "2026-02" to 30L, "2025-10" to 999L)
        assertEquals(listOf("2025-11" to 500L, "2025-12" to 0L, "2026-01" to 0L, "2026-02" to 100L), amounts(monthsEnding(day("2026-02-14"), 4, totals)))
    }

    @Test fun `ownership adds the purchase price and measures up to until`() {
        val o = ownership(100_000, 300_000, day("2024-05-01"), day("2026-05-01"))
        assertEquals(Triple(400_000L, 300_000L, "2024-05-01"), Triple(o.totalCents, o.purchaseCents, o.since))
        // 2024-05-01 to 2026-05-01 is 730 days.
        assertEquals(400_000L * 365 / 730, o.perYearCents)
    }

    @Test fun `no per year figure under ninety days owned`() {
        assertNull(ownership(5_000, 0, day("2026-01-01"), day("2026-03-31")).perYearCents, "89 days")
        assertEquals(5_000L * 365 / 90, ownership(5_000, 0, day("2026-01-01"), day("2026-04-01")).perYearCents)
        assertNull(ownership(5_000, 0, day("2026-05-01"), day("2026-04-01")).perYearCents, "since after until")
    }

    @Test fun `day of reads dates and timestamps`() {
        assertEquals(day("2024-05-01"), dayOf("2024-05-01"))
        assertEquals(day("2023-11-02"), dayOf("2023-11-02T08:00:00Z"))
        assertNull(dayOf("2023-11"))
        assertNull(dayOf("not a date"))
    }
}
