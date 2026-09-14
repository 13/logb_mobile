package dev.logb.android.core.format

import java.text.NumberFormat
import java.util.Locale

/** A milli-scaled amount with its unit, at most one decimal: 41_300 → `41.3 l`, the web's `quantity`. */
fun formatQuantity(milli: Long, unit: String, locale: Locale): String {
    val format = NumberFormat.getNumberInstance(locale)
    format.maximumFractionDigits = 1
    return "${format.format(milli / 1000.0)} $unit"
}
