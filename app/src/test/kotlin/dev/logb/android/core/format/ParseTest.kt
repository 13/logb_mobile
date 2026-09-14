package dev.logb.android.core.format

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ParseTest {
    @Test fun `money accepts comma or dot decimals and grouping`() {
        assertEquals(18950, Parse.cents("189.50")); assertEquals(18950, Parse.cents("189,50")); assertEquals(1234500, Parse.cents("12,345.00"))
        assertEquals(500, Parse.cents("5")); assertNull(Parse.cents("")); assertNull(Parse.cents("abc"))
        assertEquals("189.50", Parse.centsToText(18950)); assertEquals("", Parse.centsToText(null))
    }
    @Test fun `counters drop grouping, fuel has three decimals`() {
        assertEquals(84210, Parse.long("84 210")); assertEquals(84210, Parse.long("84.210")); assertNull(Parse.long("x"))
        assertEquals(42300, Parse.milli("42.3")); assertEquals(42300, Parse.milli("42,3")); assertEquals("42.3", Parse.milliToText(42300)); assertEquals("40", Parse.milliToText(40000))
    }
}
