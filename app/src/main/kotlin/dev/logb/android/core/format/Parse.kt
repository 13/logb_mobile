package dev.logb.android.core.format

/** Text-field input the way people type it: "189,50" or "189.50" to cents; "84 210" to a counter. Blank is null; garbage is null too. */
object Parse {
    fun cents(text: String): Long? {
        val t = text.trim().replace(" ", "").replace(" ", "")
        if (t.isEmpty()) return null
        val normalised = if (t.count { it == ',' } == 1 && !t.contains('.')) t.replace(',', '.') else t.replace(",", "")
        val value = normalised.toBigDecimalOrNull() ?: return null
        return runCatching { value.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact() }.getOrNull()
    }

    fun long(text: String): Long? {
        val t = text.trim().replace(" ", "").replace(" ", "").replace(".", "").replace(",", "")
        if (t.isEmpty()) return null
        return t.toLongOrNull()
    }

    /** Millilitres (or thousandths of any fuel unit) from "42.3". */
    fun milli(text: String): Long? = cents(text.replace(",", "."))?.let { it * 10 }

    fun centsToText(cents: Long?): String = cents?.let { "%d.%02d".format(it / 100, it % 100) } ?: ""
    fun milliToText(milli: Long?): String = milli?.let { val whole = it / 1000; val frac = (it % 1000) / 10; if (frac == 0L) "$whole" else "%d.%02d".format(whole, frac).trimEnd('0') } ?: ""
}
