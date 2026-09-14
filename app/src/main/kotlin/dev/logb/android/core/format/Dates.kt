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

/** "just now", "3 min ago", "2 h ago", else the date: for the sync line. Never wrong by more than it says. */
@androidx.compose.runtime.Composable
fun relativeTime(iso: String): String {
    val then = runCatching { java.time.Instant.parse(iso) }.getOrNull() ?: return iso
    val minutes = java.time.Duration.between(then, java.time.Instant.now()).toMinutes()
    return when {
        minutes < 1 -> androidx.compose.ui.res.stringResource(dev.logb.android.R.string.time_just_now)
        minutes < 60 -> androidx.compose.ui.res.stringResource(dev.logb.android.R.string.time_minutes_ago, minutes)
        minutes < 24 * 60 -> androidx.compose.ui.res.stringResource(dev.logb.android.R.string.time_hours_ago, minutes / 60)
        else -> formatDate(iso.take(10), currentLocale())
    }
}

/** The locale Compose observes, so a language change recomposes formatted figures. */
@androidx.compose.runtime.Composable
fun currentLocale(): java.util.Locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
