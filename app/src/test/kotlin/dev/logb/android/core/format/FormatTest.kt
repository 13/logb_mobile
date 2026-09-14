package dev.logb.android.core.format

import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals

class MoneyTest {
    @Test
    fun `cents format as the instance currency with two decimals`() {
        assertEquals("€189.00", formatCents(18900, "EUR", Locale.UK))
        assertEquals("189,00\u00a0€", formatCents(18900, "EUR", Locale.GERMANY))
    }

    @Test
    fun `zero and an unknown currency code still format`() {
        assertEquals("€0.00", formatCents(0, "EUR", Locale.UK))
        assertEquals("£5.00", formatCents(500, "not-a-code", Locale.UK))
    }
}

class CounterTest {
    @Test
    fun `a counter shows its unit with grouping`() {
        assertEquals("84,210 km", formatCounter(84210, "km", Locale.UK))
        assertEquals("84.210 km", formatCounter(84210, "km", Locale.GERMANY))
        assertEquals("120 h", formatCounter(120, "h", Locale.UK))
    }

    @Test
    fun `no unit means a bare number, null means a dash`() {
        assertEquals("12", formatCounter(12, null, Locale.UK))
        assertEquals("—", formatCounter(null, "km", Locale.UK))
    }
}

class DatesTest {
    @Test
    fun `iso dates render in the locale medium style`() {
        assertEquals("1 Sept 2026", formatDate("2026-09-01", Locale.UK))
        assertEquals("01.09.2026", formatDate("2026-09-01", Locale.GERMANY))
    }

    @Test
    fun `a date that does not parse is shown as typed`() {
        assertEquals("not-a-date", formatDate("not-a-date", Locale.UK))
        assertEquals("2026", yearOf("2026-09-01"))
    }
}
