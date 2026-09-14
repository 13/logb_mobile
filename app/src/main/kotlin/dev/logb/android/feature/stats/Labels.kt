package dev.logb.android.feature.stats

import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The bucket labels the web's `stats.ts` and `insights.ts` draw; an unparseable bucket is shown as stored. */
object Labels {
    /** `2026` stays `2026`; `2026-03` becomes the short month name. The year is already on screen in the picker. */
    fun periodLabel(bucket: String, locale: Locale): String =
        if (bucket.length == 4) bucket else month(bucket)?.format(DateTimeFormatter.ofPattern("MMM", locale)) ?: bucket

    /** `2026-09` as "Sep 26": the last twelve months cross a year, so the year stays. */
    fun monthLabel(month: String, locale: Locale): String = month(month)?.format(DateTimeFormatter.ofPattern("MMM yy", locale)) ?: month

    /** `2024-05-01` as "May 2024". */
    fun sinceLabel(date: String, locale: Locale): String = month(date.take(7))?.format(DateTimeFormatter.ofPattern("MMM yyyy", locale)) ?: date

    /** `2026-01-10` as "Jan 10" (or "10. Jan." in German). */
    fun fillLabel(date: String, locale: Locale): String {
        val d = runCatching { LocalDate.parse(date) }.getOrNull() ?: return date
        val pattern = if (locale.language == "de") "d. MMM" else "MMM d"
        return d.format(DateTimeFormatter.ofPattern(pattern, locale))
    }

    private fun month(yyyyMm: String): YearMonth? = runCatching { YearMonth.parse(yyyyMm) }.getOrNull()
}
