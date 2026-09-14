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
