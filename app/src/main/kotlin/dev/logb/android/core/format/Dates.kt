package dev.logb.android.core.format

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** An ISO `YYYY-MM-DD` date in the locale's medium style; an unparseable one is shown as typed. */
fun formatDate(iso: String, locale: Locale): String =
    runCatching { LocalDate.parse(iso).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)) }
        .getOrDefault(iso)

/** The year an ISO date falls in, for timeline group headers. */
fun yearOf(iso: String): String = iso.take(4)
