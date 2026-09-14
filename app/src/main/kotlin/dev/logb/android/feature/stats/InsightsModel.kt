package dev.logb.android.feature.stats

import dev.logb.android.core.db.LogbDatabase
import dev.logb.android.core.db.model.Bucket
import dev.logb.android.core.domain.Insights
import dev.logb.android.core.domain.SpendStats
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/** `api::insights::FuelOut`. */
data class FuelInsights(
    val unit: String,
    val quantityMilli: Long,
    val per100Milli: Long?,
    val costPerCounterMilli: Long?,
    /** Consumption per fill, oldest first. */
    val fills: List<Insights.FillRate>,
)

/** `api::insights::InsightsOut`, computed from the mirror. */
data class ObjectInsights(
    val byYear: List<Bucket>,
    val byCategory: List<Bucket>,
    val counterSpan: Pair<Long, Long>?,
    val costPerCounterMilli: Long?,
    val fuel: FuelInsights?,
    /** Counter units per day over recent readings, scaled by 1000. Null until there is enough history. */
    val counterPerDayMilli: Long?,
    /** The last twelve calendar months, oldest first; empty for an object without a counter or a single measurable month. */
    val usageByMonth: List<Insights.MonthUsage>,
    val hasContents: Boolean,
    val ownership: SpendStats.Ownership,
    /** The last twelve calendar months of spend, oldest first, zeros included. */
    val byMonth: List<SpendStats.Amount>,
) {
    /** The web's "spent" test: whether any year has a cost at all. */
    val spent: Boolean get() = byYear.any { it.costCents > 0 }
}

/** Port of `api::insights::read` and `usage`; every figure the Info tab shows, from Room. */
class InsightsModel(private val db: LogbDatabase, private val today: () -> LocalDate = { LocalDate.now() }) {
    /** How many months the usage chart covers. */
    private val usageMonths = 12

    suspend fun insights(uuid: String, contents: Boolean): ObjectInsights? {
        val obj = db.objectDao().get(uuid)?.takeIf { it.deletedAt == null } ?: return null
        val t = today()
        val descendants = db.objectDao().descendantUuids(uuid)
        val scope = if (contents) listOf(uuid) + descendants else listOf(uuid)
        val activities = db.activityDao()

        val byYear = activities.byYear(scope)
        val byCategory = activities.byCategory(scope)
        val span = activities.counterSpan(uuid)
        val counterSpan = if (span.minCounter != null && span.maxCounter != null) span.minCounter to span.maxCounter else null
        val costPerCounter = Insights.costPerCounterMilli(span.totalCostCents, counterSpan?.let { it.second - it.first } ?: 0)

        val running = activities.runningCents(scope)
        val scoped = db.objectDao().all().first().filter { it.uuid in scope }.map { it.toRow() }
        val purchased = activities.purchasedObjectUuids().filter { it in scope }.toSet()
        val purchaseCents = SpendStats.purchaseSpend(scoped, purchased).sumOf { it.costCents }
        // "Since" and "until" are always the object's own: a boiler bought later does not shorten how long the house has been owned.
        val since = obj.purchaseDate?.let(SpendStats::dayOf) ?: SpendStats.dayOf(obj.createdAt) ?: t
        val until = obj.archivedAt?.let(SpendStats::dayOf) ?: t
        val ownership = SpendStats.ownership(running, purchaseCents, since, until)

        val byMonth = SpendStats.monthsEnding(t, usageMonths, activities.monthTotals(scope).map { it.month to it.costCents })

        val fillRows = activities.fills(uuid)
        val fuel = if (fillRows.isEmpty()) null else {
            val fills = fillRows.map { Insights.Fill(it.counterValue, it.quantityMilli, it.costCents) }
            FuelInsights(
                unit = obj.fuelUnit ?: Insights.defaultFuelUnit(obj.counterUnit),
                quantityMilli = fills.sumOf { it.quantityMilli },
                per100Milli = Insights.consumptionPer100Milli(fills),
                costPerCounterMilli = Insights.fuelCostPerCounterMilli(fills),
                fills = Insights.consumptionPerFill(fillRows.map { Insights.DatedFill(it.date, it.counterValue, it.quantityMilli) }),
            )
        }

        val readings = readings(uuid, t)
        val usage = Insights.usage(readings, t)
        val usageByMonth = if (obj.counterUnit != null) {
            // Every reading, not a window: the first month of the chart is measured from whatever reading came before it.
            Insights.monthlyUsage(readings, t, usageMonths).takeIf { months -> months.any { it.amount != null } } ?: emptyList()
        } else {
            emptyList()
        }

        return ObjectInsights(
            byYear = byYear, byCategory = byCategory, counterSpan = counterSpan, costPerCounterMilli = costPerCounter, fuel = fuel,
            counterPerDayMilli = usage?.rateMilli, usageByMonth = usageByMonth, hasContents = descendants.isNotEmpty(),
            ownership = ownership, byMonth = byMonth,
        )
    }

    /** `api::insights::usage` for one object: the latest reading and daily rate, or null without enough history. */
    suspend fun usage(uuid: String): Insights.Usage? {
        val t = today()
        return Insights.usage(readings(uuid, t), t)
    }

    private suspend fun readings(uuid: String, today: LocalDate): List<Insights.Reading> =
        db.activityDao().readingRows(uuid, today.plusDays(1).toString()).mapNotNull { row ->
            // A stored date that does not parse is skipped, as every other read of a row does.
            runCatching { LocalDate.parse(row.date) }.getOrNull()?.let { Insights.Reading(it, row.counterValue) }
        }
}
