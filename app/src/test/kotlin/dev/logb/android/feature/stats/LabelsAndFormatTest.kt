package dev.logb.android.feature.stats

import dev.logb.android.core.format.formatCentsWhole
import dev.logb.android.core.format.formatPerCounter
import dev.logb.android.core.format.formatQuantity
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals

class LabelsAndFormatTest {
    @Test fun `period labels keep years and shorten months`() {
        assertEquals("2026", Labels.periodLabel("2026", Locale.GERMAN))
        assertEquals("März", Labels.periodLabel("2026-03", Locale.GERMAN))
        assertEquals("Mar", Labels.periodLabel("2026-03", Locale.ENGLISH))
        assertEquals("202-0", Labels.periodLabel("202-0", Locale.ENGLISH), "shown as stored")
    }

    @Test fun `month, since and fill labels`() {
        assertEquals("Sep 26", Labels.monthLabel("2026-09", Locale.ENGLISH))
        assertEquals("May 2024", Labels.sinceLabel("2024-05-01", Locale.ENGLISH))
        assertEquals("Jan 10", Labels.fillLabel("2026-01-10", Locale.ENGLISH))
        assertEquals("10. Jan.", Labels.fillLabel("2026-01-10", Locale.GERMAN))
    }

    @Test fun `per-counter, whole and quantity formats`() {
        assertEquals("0,02 €", formatPerCounter(2_496, "EUR", Locale.GERMANY))
        assertEquals("€0.02", formatPerCounter(2_496, "EUR", Locale.US))
        assertEquals("€1,235", formatCentsWhole(123_456, "EUR", Locale.US))
        assertEquals("41.3 l", formatQuantity(41_300, "l", Locale.US))
        assertEquals("5 l", formatQuantity(5_000, "l", Locale.US))
    }
}
