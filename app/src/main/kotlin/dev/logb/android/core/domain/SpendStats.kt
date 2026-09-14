package dev.logb.android.core.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Port of `src/domain/stats.rs`: spend rollups over the object tree, purchase prices, ownership. Ids are uuids. */
object SpendStats {
    /**
     * The `byCategory` bucket for purchase prices. Not an activity category: an object's
     * `purchase_price_cents` is a field of the object, and it gets its own row so it is never
     * mistaken for an activity logged under `purchase`.
     */
    const val PURCHASE_PRICE = "purchase_price"

    /** Fewer days owned than this and there is no per-year figure: a few weeks' spend multiplied out to a year is not a number anyone can plan with. */
    const val MIN_DAYS_FOR_PER_YEAR = 90L

    /** One of the user's non-deleted objects, as the statistics need it. */
    data class ObjectRow(
        val id: String,
        val parentId: String?,
        val name: String,
        /** The object's `type` column. */
        val kind: String,
        val archived: Boolean,
        val purchaseDate: String?,
        val purchasePriceCents: Long?,
        /** RFC 3339, as the server writes it. */
        val createdAt: String,
    )

    /** Money spent on one object in one month (`YYYY-MM`) under one category. */
    data class Spend(val objectId: String, val month: String, val category: String, val costCents: Long)

    data class Amount(val bucket: String, val costCents: Long)

    data class ObjectNode(
        val id: String,
        val name: String,
        val kind: String,
        val archived: Boolean,
        /** This object's own spend plus every descendant's. */
        val costCents: Long,
        val children: List<ObjectNode>,
    )

    data class Stats(
        val totalCents: Long,
        val years: List<String>,
        val overTime: List<Amount>,
        val byObject: List<ObjectNode>,
        val byType: List<Amount>,
        val byCategory: List<Amount>,
    )

    /** What owning an object has cost: running costs plus the purchase price actually counted, and that spread over the years owned. */
    data class Ownership(
        val totalCents: Long,
        val purchaseCents: Long,
        /** `YYYY-MM-DD`: the purchase date, or the day the object was created. */
        val since: String,
        val perYearCents: Long?,
    )

    /**
     * Purchase prices as spend, one entry per object that has a positive price.
     *
     * Dated by `purchaseDate`, or by the day the object was created when no purchase date was
     * entered. An object in `purchased` already has a costed `purchase` activity: that entry is
     * the purchase, and adding the price as well would count the same money twice.
     */
    fun purchaseSpend(objects: List<ObjectRow>, purchased: Set<String>): List<Spend> =
        objects.filter { it.id !in purchased }.mapNotNull { o ->
            val cents = o.purchasePriceCents?.takeIf { it > 0 } ?: return@mapNotNull null
            val date = o.purchaseDate ?: o.createdAt
            if (date.length < 7) return@mapNotNull null
            Spend(o.id, date.substring(0, 7), PURCHASE_PRICE, cents)
        }

    /** True for a month shaped exactly like the ones this codebase produces: four ASCII digits, a dash, two ASCII digits. */
    private fun validMonth(month: String): Boolean =
        month.length == 7 && month.substring(0, 4).all { it in '0'..'9' } && month[4] == '-' && month.substring(5).all { it in '0'..'9' }

    /**
     * The whole statistics response for `spend`, restricted to `year` when one is given.
     * `years` is computed before the filter, so the year picker always offers every year that has spend.
     */
    fun summarize(objects: List<ObjectRow>, spend: List<Spend>, year: Int?): Stats {
        val wellFormed = spend.filter { validMonth(it.month) }
        val years = wellFormed.filter { it.costCents > 0 }.map { it.month.substring(0, 4) }.distinct().sortedDescending()

        val prefix = year?.let { "%04d-".format(it) }
        val selected = wellFormed.filter { it.costCents > 0 }.filter { prefix == null || it.month.startsWith(prefix) }

        val overTime = if (year != null) {
            fill((1..12).map { m -> "%04d-%02d".format(year, m) }) { b -> selected.filter { it.month == b }.sumOf { it.costCents } }
        } else {
            selected.groupBy { it.month.substring(0, 4) }.toSortedMap().map { (bucket, list) -> Amount(bucket, list.sumOf { it.costCents }) }
        }

        val own = HashMap<String, Long>()
        val byCategory = HashMap<String, Long>()
        for (s in selected) {
            own[s.objectId] = (own[s.objectId] ?: 0) + s.costCents
            byCategory[s.category] = (byCategory[s.category] ?: 0) + s.costCents
        }
        val byType = HashMap<String, Long>()
        for (o in objects) own[o.id]?.let { c -> byType[o.kind] = (byType[o.kind] ?: 0) + c }

        return Stats(
            totalCents = selected.sumOf { it.costCents },
            years = years,
            overTime = overTime,
            byObject = rollUp(objects, own),
            byType = sorted(byType),
            byCategory = sorted(byCategory),
        )
    }

    /** Largest first, then by bucket so equal amounts do not shuffle between reads. */
    private fun sorted(map: Map<String, Long>): List<Amount> =
        map.filter { it.value > 0 }.map { (bucket, cents) -> Amount(bucket, cents) }
            .sortedWith(compareByDescending<Amount> { it.costCents }.thenBy { it.bucket })

    /**
     * The object tree with each node carrying its subtree's spend. An object whose parent is not
     * among `objects` is a root. Subtrees that spent nothing are dropped.
     */
    private fun rollUp(objects: List<ObjectRow>, own: Map<String, Long>): List<ObjectNode> {
        val ids = objects.map { it.id }.toSet()
        val children = objects.groupBy { o -> o.parentId?.takeIf { it in ids } }
        fun build(parent: String?): List<ObjectNode> =
            (children[parent] ?: emptyList()).map { o ->
                val kids = build(o.id)
                ObjectNode(o.id, o.name, o.kind, o.archived, (own[o.id] ?: 0) + kids.sumOf { it.costCents }, kids)
            }.filter { it.costCents > 0 }.sortedWith(compareByDescending<ObjectNode> { it.costCents }.thenBy { it.name })
        return build(null)
    }

    /** The day at the start of a stored date (`YYYY-MM-DD`) or timestamp (RFC 3339), or null when it does not parse. */
    fun dayOf(s: String): LocalDate? = if (s.length < 10) null else runCatching { LocalDate.parse(s.substring(0, 10)) }.getOrNull()

    /** Ownership from `since` up to `until` -- the archive date for an object no longer in use, today otherwise. Integer arithmetic: cents × 365 ÷ days. */
    fun ownership(runningCents: Long, purchaseCents: Long, since: LocalDate, until: LocalDate): Ownership {
        val total = runningCents + purchaseCents
        val days = ChronoUnit.DAYS.between(since, until)
        val perYear = if (days >= MIN_DAYS_FOR_PER_YEAR) total * 365 / days else null
        return Ownership(total, purchaseCents, since.toString(), perYear)
    }

    /** `months` calendar months ending with today's, oldest first, each with the sum of `totals` for that `YYYY-MM` -- 0 for a month without spend. */
    fun monthsEnding(today: LocalDate, months: Int, totals: List<Pair<String, Long>>): List<Amount> {
        val thisMonth = today.withDayOfMonth(1)
        val buckets = (months - 1 downTo 0).map { back -> Insights.monthKey(thisMonth.minusMonths(back.toLong())) }
        return fill(buckets) { b -> totals.filter { it.first == b }.sumOf { it.second } }
    }

    /** One [Amount] per bucket, in the order given, zeros included. */
    private fun fill(buckets: List<String>, amountOf: (String) -> Long): List<Amount> = buckets.map { Amount(it, amountOf(it)) }
}
