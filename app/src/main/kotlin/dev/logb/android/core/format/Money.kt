package dev.logb.android.core.format

import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/** Integer cents in the instance's currency, formatted for the locale: `€189.00`, `189,00 €`. */
fun formatCents(cents: Long, currency: String, locale: Locale): String {
    val format = NumberFormat.getCurrencyInstance(locale)
    runCatching { Currency.getInstance(currency) }.getOrNull()?.let { format.currency = it }
    format.minimumFractionDigits = 2
    format.maximumFractionDigits = 2
    return format.format(cents / 100.0)
}

/** Whole units, no cents: `€1,235` for the per-year figure, where cents would claim a precision an average does not have. */
fun formatCentsWhole(cents: Long, currency: String, locale: Locale): String {
    val format = NumberFormat.getCurrencyInstance(locale)
    runCatching { Currency.getInstance(currency) }.getOrNull()?.let { format.currency = it }
    format.minimumFractionDigits = 0
    format.maximumFractionDigits = 0
    return format.format(Math.round(cents / 100.0))
}

/** Milli-cents per counter unit as money: 2_496 → `€0.02`, the web's `perCounter`. */
fun formatPerCounter(milli: Long, currency: String, locale: Locale): String = formatCents(Math.round(milli / 1000.0), currency, locale)
