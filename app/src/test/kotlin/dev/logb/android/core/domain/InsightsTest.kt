package dev.logb.android.core.domain

import dev.logb.android.core.domain.Insights.DatedFill
import dev.logb.android.core.domain.Insights.FILL_BARS
import dev.logb.android.core.domain.Insights.Fill
import dev.logb.android.core.domain.Insights.FillRate
import dev.logb.android.core.domain.Insights.MonthUsage
import dev.logb.android.core.domain.Insights.Reading
import dev.logb.android.core.domain.Insights.consumptionPer100Milli
import dev.logb.android.core.domain.Insights.consumptionPerFill
import dev.logb.android.core.domain.Insights.costPerCounterMilli
import dev.logb.android.core.domain.Insights.dailyRateMilli
import dev.logb.android.core.domain.Insights.defaultFuelUnit
import dev.logb.android.core.domain.Insights.estimatedDate
import dev.logb.android.core.domain.Insights.fuelCostPerCounterMilli
import dev.logb.android.core.domain.Insights.monthlyUsage
import dev.logb.android.core.domain.Insights.usage
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every test in `src/domain/insights.rs`, with the same figures. */
class InsightsTest {
    private fun f(counter: Long, quantityMilli: Long, costCents: Long?) = Fill(counter, quantityMilli, costCents)
    private fun day(s: String) = LocalDate.parse(s)
    private fun r(date: String, counter: Long) = Reading(day(date), counter)
    private fun df(date: String, counter: Long, quantityMilli: Long) = DatedFill(date, counter, quantityMilli)
    private fun rate(date: String, per100: Long) = FillRate(date, per100)

    @Test fun `monthlyUsage measures each month that has a reading`() {
        val readings = listOf(r("2026-06-15", 10_000), r("2026-07-10", 10_800), r("2026-07-30", 11_000), r("2026-09-05", 12_500))
        val usage = monthlyUsage(readings, day("2026-09-13"), 4)
        assertEquals(listOf("2026-06", "2026-07", "2026-08", "2026-09"), usage.map { it.month })
        // June: the first reading ever, nothing to measure from. July: 11_000 - 10_000.
        // August: no reading, so no claim. September: 12_500 - 11_000.
        assertEquals(listOf(null, 1_000L, null, 1_500L), usage.map { it.amount })
    }

    @Test fun `monthlyUsage does not invent negative months`() {
        val readings = listOf(r("2026-07-10", 90_000), r("2026-08-10", 100))
        assertEquals(listOf(MonthUsage("2026-08", null)), monthlyUsage(readings, day("2026-08-20"), 1), "a replaced odometer")
    }

    @Test fun `monthlyUsage crosses a year`() {
        val readings = listOf(r("2025-12-31", 5_000), r("2026-01-02", 5_040))
        assertEquals(MonthUsage("2026-01", 40), monthlyUsage(readings, day("2026-01-05"), 1)[0])
    }

    @Test fun `the rate runs from the earliest reading in the window to the latest`() {
        // 3_000 km over 100 days: 30 km a day. The 2024 reading is outside the window and must not dilute the recent rate.
        val readings = listOf(r("2024-01-01", 0), r("2026-06-01", 50_000), r("2026-09-09", 52_970))
        assertEquals(2_970 * 1000 / 100, dailyRateMilli(readings))
    }

    @Test fun `a short window falls back to the earliest reading`() {
        // The only reading inside the window is five days old: too short, so the rate is taken from the start of the history instead.
        val readings = listOf(r("2025-09-09", 40_000), r("2026-09-04", 49_950), r("2026-09-09", 50_000))
        assertEquals(10_000L * 1000 / 365, dailyRateMilli(readings))
    }

    @Test fun `no rate without a real span or a rising counter`() {
        assertNull(dailyRateMilli(emptyList()))
        assertNull(dailyRateMilli(listOf(r("2026-09-01", 1_000))))
        assertNull(dailyRateMilli(listOf(r("2026-09-01", 1_000), r("2026-09-10", 1_500))), "under 14 days")
        assertNull(dailyRateMilli(listOf(r("2026-01-01", 90_000), r("2026-09-01", 1_000))), "odometer replaced")
    }

    @Test fun `the estimate projects from the latest reading`() {
        // 1_000 km to go at 25 km a day: 40 days after the reading, not after today.
        assertEquals(day("2026-10-11"), estimatedDate(r("2026-09-01", 59_000), 25_000, 60_000))
        // A remainder that does not divide evenly rounds up: the target is reached on day 41.
        assertEquals(day("2026-10-12"), estimatedDate(r("2026-09-01", 59_000), 24_900, 60_000))
    }

    @Test fun `no estimate once reached or without a rate or absurdly far`() {
        assertNull(estimatedDate(r("2026-09-01", 60_000), 25_000, 60_000))
        assertNull(estimatedDate(r("2026-09-01", 59_000), 0, 60_000))
        assertNull(estimatedDate(r("2026-09-01", 0), 1, 1_000_000))
    }

    @Test fun `consumption excludes the first fill`() {
        // 40 L burned over 800 km -> 5 L/100 km. The first fill's fuel was burned before the window opened.
        val fills = listOf(f(10_000, 45_000, 5_000), f(10_400, 20_000, 4_000), f(10_800, 20_000, 4_000))
        assertEquals(5_000, consumptionPer100Milli(fills))
    }

    @Test fun `a single fill cannot produce consumption`() {
        assertNull(consumptionPer100Milli(listOf(f(10_000, 45_000, 5_000))))
        assertNull(consumptionPer100Milli(emptyList()))
    }

    @Test fun `a zero span produces nothing rather than dividing by zero`() {
        assertNull(consumptionPer100Milli(listOf(f(10_000, 45_000, 5_000), f(10_000, 20_000, 4_000))))
    }

    @Test fun `fills out of order are sorted before measuring`() {
        val fills = listOf(f(10_800, 20_000, 4_000), f(10_000, 45_000, 5_000), f(10_400, 20_000, 4_000))
        assertEquals(5_000, consumptionPer100Milli(fills))
    }

    @Test fun `cost per counter needs a span`() {
        assertEquals(2_496, costPerCounterMilli(48_000, 19_230))
        assertNull(costPerCounterMilli(48_000, 0))
        assertNull(costPerCounterMilli(48_000, -5))
    }

    @Test fun `fuel cost matches the worked consumption case`() {
        // Only the later two fills' 4_000 + 4_000 = 8_000 cents count, over the 800 km fuel span.
        val fills = listOf(f(10_000, 45_000, 5_000), f(10_400, 20_000, 4_000), f(10_800, 20_000, 4_000))
        assertEquals(8_000L * 1000 / 800, fuelCostPerCounterMilli(fills))
    }

    @Test fun `the first fills cost is excluded like its quantity`() {
        val fills = listOf(f(10_000, 45_000, 9_000), f(10_400, 20_000, 4_000), f(10_800, 20_000, 4_000))
        assertEquals(10_000, fuelCostPerCounterMilli(fills))
        assertNotEquals(21_250, fuelCostPerCounterMilli(fills))
    }

    @Test fun `a fill with no recorded cost contributes zero but still counts`() {
        val fills = listOf(f(10_000, 45_000, 5_000), f(10_400, 20_000, null), f(10_800, 20_000, 4_000))
        assertEquals(4_000L * 1000 / 800, fuelCostPerCounterMilli(fills))
    }

    @Test fun `unmeasurable consumption means unmeasurable cost too`() {
        assertNull(fuelCostPerCounterMilli(listOf(f(10_000, 45_000, 5_000))))
        assertNull(fuelCostPerCounterMilli(emptyList()))
        assertNull(fuelCostPerCounterMilli(listOf(f(10_000, 45_000, 5_000), f(10_000, 20_000, 4_000))))
    }

    @Test fun `default fuel unit uses gallons for miles and litres otherwise`() {
        assertEquals("gal", defaultFuelUnit("mi"))
        assertEquals("l", defaultFuelUnit("km"))
        assertEquals("l", defaultFuelUnit(null))
    }

    @Test fun `each fill is measured from the one before it`() {
        // 30 L over 500 km, then 25 L over 500 km. The first fill only opens the window.
        val fills = listOf(df("2026-01-01", 10_000, 40_000), df("2026-02-01", 10_500, 30_000), df("2026-03-01", 11_000, 25_000))
        assertEquals(listOf(rate("2026-02-01", 6_000), rate("2026-03-01", 5_000)), consumptionPerFill(fills))
    }

    @Test fun `fills are put in date order first`() {
        val fills = listOf(df("2026-03-01", 11_000, 25_000), df("2026-01-01", 10_000, 40_000), df("2026-02-01", 10_500, 30_000))
        assertEquals(listOf(rate("2026-02-01", 6_000), rate("2026-03-01", 5_000)), consumptionPerFill(fills))
    }

    @Test fun `a distance that is not positive gives no bar but starts the next interval`() {
        // February repeats the counter; March is after an odometer replacement; April is 500 km on.
        val fills = listOf(df("2026-01-01", 10_000, 40_000), df("2026-02-01", 10_000, 30_000), df("2026-03-01", 500, 20_000), df("2026-04-01", 1_000, 25_000))
        assertEquals(listOf(rate("2026-04-01", 5_000)), consumptionPerFill(fills))
    }

    @Test fun `only the newest twelve are kept`() {
        val fills = (0 until 20).map { i -> df("2026-01-%02d".format(i + 1), 10_000L + i * 100, 5_000) }
        val rates = consumptionPerFill(fills)
        assertEquals(FILL_BARS, rates.size)
        assertEquals("2026-01-09", rates[0].date)
        assertEquals("2026-01-20", rates[11].date)
    }

    @Test fun `fewer than two fills give nothing`() {
        assertTrue(consumptionPerFill(emptyList()).isEmpty())
        assertTrue(consumptionPerFill(listOf(df("2026-01-01", 10_000, 40_000))).isEmpty())
    }

    @Test fun `usage ignores readings past tomorrow and older than twice the window`() {
        val today = day("2026-09-13")
        // A typo'd 2027 reading must not become the latest; a 2024 reading is out of the query's reach.
        val readings = listOf(r("2024-01-01", 0), r("2026-03-01", 50_000), r("2026-09-09", 52_970), r("2027-09-09", 99_000))
        val u = usage(readings, today)!!
        assertEquals(r("2026-09-09", 52_970), u.last)
        assertEquals(2_970 * 1000 / 192, u.rateMilli)
        assertNull(usage(listOf(r("2026-09-09", 52_970)), today))
    }
}
