package dev.logb.android.core.db

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class StatsDaoTest {
    private val db = TestDatabase.inMemory()

    @After fun close() = db.close()

    @Test
    fun `spend rows group by object, month and category and skip readings, costless, deleted and orphaned entries`() = runTest {
        db.objectDao().upsert(obj("car", "Car"), obj("gone", "Gone").copy(deletedAt = T0))
        db.activityDao().upsert(
            act("a1", "car", "2026-01-05", cost = 100, category = "fuel"),
            act("a2", "car", "2026-01-20", cost = 50, category = "fuel"),
            act("a3", "car", "2026-01-21", cost = 70, category = "repair"),
            act("a4", "car", "2026-02-01", cost = 5, category = "fuel"),
            act("r1", "car", "2026-01-10", cost = 999, counter = 1000, category = "reading"),
            act("n1", "car", "2026-01-11", cost = null, category = "repair"),
            act("d1", "car", "2026-01-12", cost = 999, category = "repair").copy(deletedAt = T0),
            act("o1", "gone", "2026-01-12", cost = 999, category = "repair"),
        )
        val rows = db.activityDao().spendByObjectMonthCategory().map { Triple(it.month, it.category, it.costCents) }.toSet()
        assertEquals(setOf(Triple("2026-01", "fuel", 150L), Triple("2026-01", "repair", 70L), Triple("2026-02", "fuel", 5L)), rows)
    }

    @Test
    fun `purchased objects are those with a costed purchase entry`() = runTest {
        db.objectDao().upsert(obj("car", "Car"), obj("bike", "Bike"))
        db.activityDao().upsert(act("p1", "car", "2026-01-05", cost = 1_500_000, category = "purchase"), act("p2", "bike", "2026-01-05", cost = 0, category = "purchase"))
        assertEquals(listOf("car"), db.activityDao().purchasedObjectUuids())
    }

    @Test
    fun `insight buckets, span, month totals and fills`() = runTest {
        db.objectDao().upsert(obj("car", "Car"), obj("seat", "Seat", parent = "car"))
        db.activityDao().upsert(
            act("f1", "car", "2025-12-01", cost = 5_000, counter = 10_000, category = "fuel").copy(quantityMilli = 45_000),
            act("f2", "car", "2026-01-01", cost = 4_000, counter = 10_400, category = "fuel").copy(quantityMilli = 20_000),
            act("f3", "car", "2026-02-01", cost = null, counter = 10_800, category = "fuel").copy(quantityMilli = null),
            act("r1", "car", "2026-01-15", counter = 10_600, category = "reading"),
            act("s1", "seat", "2026-01-20", cost = 300, category = "repair"),
        )
        val own = listOf("car")
        val withContents = listOf("car", "seat")
        assertEquals(listOf("2026" to 4_000L, "2025" to 5_000L), db.activityDao().byYear(own).map { it.bucket to it.costCents })
        assertEquals(listOf("2026" to 4_300L, "2025" to 5_000L), db.activityDao().byYear(withContents).map { it.bucket to it.costCents })
        assertEquals(listOf("fuel"), db.activityDao().byCategory(own).map { it.bucket }, "readings left out")
        assertEquals(3, db.activityDao().byCategory(own)[0].count)
        val span = db.activityDao().counterSpan("car")
        assertEquals(Triple(10_000L, 10_800L, 9_000L), Triple(span.minCounter, span.maxCounter, span.totalCostCents))
        assertEquals(setOf("2025-12" to 5_000L, "2026-01" to 4_300L), db.activityDao().monthTotals(withContents).map { it.month to it.costCents }.toSet())
        assertEquals(9_300, db.activityDao().runningCents(withContents))
        assertEquals(listOf("f1", "f2").size, db.activityDao().fills("car").size, "a fill needs both a counter and a quantity")
        assertEquals(listOf(10_000L, 10_400L, 10_600L, 10_800L), db.activityDao().readingRows("car", "2026-09-14").map { it.counterValue })
        assertEquals(listOf(10_000L, 10_400L), db.activityDao().readingRows("car", "2026-01-01").map { it.counterValue }, "the horizon cuts")
        assertNull(db.activityDao().counterSpan("seat").minCounter)
    }
}
