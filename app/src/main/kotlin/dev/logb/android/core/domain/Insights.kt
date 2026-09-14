package dev.logb.android.core.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Port of `src/domain/insights.rs`: usage rate, estimated dates, monthly usage, fuel consumption. Integer arithmetic throughout, as the server's. */
object Insights {
    /**
     * How far back the usage rate looks: recent enough to follow a change of habit (a new commute,
     * a winter the bike stays in), long enough to smooth out one long trip.
     */
    const val RATE_WINDOW_DAYS = 180L

    /** The shortest span a rate is measured over. Two readings a day apart say more about that day than about how the object is used. */
    const val RATE_MIN_SPAN_DAYS = 14L

    /** An estimate further out than this is not a date anyone can plan around. */
    private const val MAX_ESTIMATE_DAYS = 3650L

    /** How many fills the trend draws. */
    const val FILL_BARS = 12

    /** One counter reading, as the rate needs it. */
    data class Reading(val date: LocalDate, val counter: Long)

    /** How far the counter moved in one calendar month. `amount` is null when the readings cannot say. */
    data class MonthUsage(val month: String, val amount: Long?)

    /** One fuel entry that carries an odometer reading, an amount, and what it cost. */
    data class Fill(val counter: Long, val quantityMilli: Long, val costCents: Long?)

    /** One fuel entry with its date, as the per-fill trend needs it. */
    data class DatedFill(val date: String, val counter: Long, val quantityMilli: Long)

    data class FillRate(val date: String, val per100Milli: Long)

    /** An object's newest reading and the rate its recent readings rise at (`api::insights::Usage`). */
    data class Usage(val last: Reading, val rateMilli: Long)

    private val readingOrder = compareBy<Reading>({ it.date }, { it.counter })

    /** The newest reading: latest date, and on that date the highest value. */
    fun latestReading(readings: List<Reading>): Reading? = readings.maxWithOrNull(readingOrder)

    /**
     * Counter units per day, scaled by 1000, or null when the readings cannot support a rate.
     *
     * Measured from the earliest reading inside the window to the latest one. When the window
     * holds too short a span -- readings only started recently, or there is one old reading and one
     * new -- it falls back to the earliest reading of all. A span under [RATE_MIN_SPAN_DAYS], or a
     * counter that did not rise (a replaced odometer), gives no rate rather than a wrong one.
     */
    fun dailyRateMilli(readings: List<Reading>): Long? {
        val last = latestReading(readings) ?: return null
        fun rateFrom(first: Reading): Long? {
            val span = ChronoUnit.DAYS.between(first.date, last.date)
            val delta = last.counter - first.counter
            return if (span >= RATE_MIN_SPAN_DAYS && delta > 0) delta * 1000 / span else null
        }
        val windowStart = last.date.minusDays(RATE_WINDOW_DAYS)
        val inWindow = readings.filter { it.date >= windowStart && it.date < last.date }.minWithOrNull(readingOrder)
        val earliest = readings.minWithOrNull(readingOrder)
        return inWindow?.let(::rateFrom) ?: earliest?.let(::rateFrom)
    }

    /**
     * The date the counter is expected to reach `target`, projected from the latest reading at
     * `rateMilli` units per day (scaled by 1000). Null when the target is already reached -- that
     * reminder is due, not upcoming -- or when there is no usable rate.
     */
    fun estimatedDate(last: Reading, rateMilli: Long, target: Long): LocalDate? {
        val remaining = target - last.counter
        if (rateMilli <= 0 || remaining <= 0) return null
        val scaled = runCatching { Math.multiplyExact(remaining, 1000L) }.getOrDefault(Long.MAX_VALUE)
        val days = (scaled + rateMilli - 1) / rateMilli
        if (days > MAX_ESTIMATE_DAYS) return null
        return last.date.plusDays(days)
    }

    /**
     * Usage per calendar month for the `months` months ending with today's, oldest first.
     *
     * A month's amount is the highest reading up to its end minus the highest reading before it
     * began -- but only for a month that has a reading of its own. A month without one says
     * nothing: spreading the next month's jump back over it would draw a smooth curve the data
     * does not have, and charging it all to the next month would draw a spike that never happened.
     * A counter that went down (a replaced odometer) is unknown too, not negative.
     */
    fun monthlyUsage(readings: List<Reading>, today: LocalDate, months: Int): List<MonthUsage> {
        val thisMonth = today.withDayOfMonth(1)
        fun highestBefore(date: LocalDate): Long? = readings.filter { it.date < date }.maxOfOrNull { it.counter }
        return (months - 1 downTo 0).map { back ->
            val first = thisMonth.minusMonths(back.toLong())
            val next = first.plusMonths(1)
            // Measured to the month's own highest reading, not to the highest up to its end: the
            // latter can never be below the start, so a replaced odometer would read as a quiet
            // month of zero rather than the unknown it is.
            val end = readings.filter { it.date >= first && it.date < next }.maxOfOrNull { it.counter }
            val start = highestBefore(first)
            val amount = if (end != null && start != null && end >= start) end - start else null
            MonthUsage(monthKey(first), amount)
        }
    }

    /** `YYYY-MM` of the month a date falls in. */
    fun monthKey(date: LocalDate): String = "%04d-%02d".format(date.year, date.monthValue)

    /**
     * Quantity burned per 100 counter units, scaled by 1000, or null when it cannot be measured.
     *
     * The standard tank method: the earliest fill only marks where the window opens -- its fuel
     * was burned before it -- so every later fill's quantity is divided by the distance from the
     * first fill to the last.
     */
    fun consumptionPer100Milli(fills: List<Fill>): Long? {
        if (fills.size < 2) return null
        val sorted = fills.sortedBy { it.counter }
        val span = sorted.last().counter - sorted.first().counter
        if (span <= 0) return null
        val burned = sorted.drop(1).sumOf { it.quantityMilli }
        return burned * 100 / span
    }

    /**
     * Cents per counter unit for the fuel window, scaled by 1000, or null when consumption itself
     * is not measurable. The same fills and span as [consumptionPer100Milli], the earliest excluded;
     * a fill with no recorded cost contributes 0 but still counts as a fill.
     */
    fun fuelCostPerCounterMilli(fills: List<Fill>): Long? {
        if (fills.size < 2) return null
        val sorted = fills.sortedBy { it.counter }
        val span = sorted.last().counter - sorted.first().counter
        if (span <= 0) return null
        val cost = sorted.drop(1).sumOf { it.costCents ?: 0 }
        return cost * 1000 / span
    }

    /** Cents per counter unit, scaled by 1000, or null when the object has not moved. */
    fun costPerCounterMilli(totalCostCents: Long, span: Long): Long? = if (span <= 0) null else totalCostCents * 1000 / span

    /** The unit a quantity is in when the object does not name one: petrol countries measure kilometres in litres and miles in gallons. */
    fun defaultFuelUnit(counterUnit: String?): String = if (counterUnit == "mi") "gal" else "l"

    /**
     * Quantity per 100 counter units for each fill, measured from the fill before it; the newest
     * [FILL_BARS], oldest first.
     *
     * The tank method one interval at a time: a fill's fuel was burned over the distance since the
     * previous fill, so the first fill has no figure. Fills are taken in date order, not counter
     * order, so a replaced odometer shows up as a distance that is not positive -- and that, like
     * the same counter typed twice, gives no figure rather than a spike. The fill still starts the
     * next interval.
     */
    fun consumptionPerFill(fills: List<DatedFill>): List<FillRate> {
        val sorted = fills.sortedWith(compareBy({ it.date }, { it.counter }))
        val rates = sorted.zipWithNext().mapNotNull { (a, b) ->
            val distance = b.counter - a.counter
            if (distance > 0) FillRate(b.date, b.quantityMilli * 100 / distance) else null
        }
        return rates.takeLast(FILL_BARS)
    }

    /**
     * `api::insights::usage_by_object` for one object: the latest reading and the rate over readings
     * dated no later than tomorrow (the server's `reading_horizon`) and no earlier than twice the
     * window back from today -- the fallback in [dailyRateMilli] reaches past the window, so the
     * server reads twice its length, and so does this.
     */
    fun usage(readings: List<Reading>, today: LocalDate): Usage? {
        val horizon = today.plusDays(1)
        val from = today.minusDays(RATE_WINDOW_DAYS * 2)
        val recent = readings.filter { it.date <= horizon && it.date >= from }
        val rate = dailyRateMilli(recent) ?: return null
        val last = latestReading(recent) ?: return null
        return Usage(last, rate)
    }
}
