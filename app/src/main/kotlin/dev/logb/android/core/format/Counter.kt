package dev.logb.android.core.format

import java.text.NumberFormat
import java.util.Locale

/** A dash rather than nothing, so a missing figure still holds its place in a row. */
const val NO_VALUE = "—"

/** A counter reading with its unit: `84,210 km`. No unit gives the bare number; null gives a dash. */
fun formatCounter(value: Long?, unit: String?, locale: Locale): String {
    if (value == null) return NO_VALUE
    val number = NumberFormat.getIntegerInstance(locale).format(value)
    return if (unit.isNullOrBlank()) number else "$number $unit"
}
