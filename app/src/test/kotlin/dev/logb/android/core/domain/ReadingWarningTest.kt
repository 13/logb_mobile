package dev.logb.android.core.domain

import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** `frontend/tests/reading.test.ts`, the warning cases. */
class ReadingWarningTest {
    private val last = LocalDate.parse("2026-09-01")
    private fun warn(value: Long, date: String, lastCounter: Long? = 50_000, lastDate: LocalDate? = last, rate: Long? = 40_000) =
        ReadingChecks.readingWarning(value, LocalDate.parse(date), lastCounter, lastDate, rate)

    @Test fun `says nothing for an ordinary reading`() {
        assertNull(warn(50_400, "2026-09-11")); assertNull(warn(50_000, "2026-09-11"))
    }

    @Test fun `questions a reading lower than the last one`() = assertEquals(ReadingWarning.Lower, warn(49_999, "2026-09-11"))

    @Test fun `questions a jump far beyond the usual rate, which is usually an extra digit`() {
        // 10 days at 40 km/day is 400 km; 5x that is 2_000 km.
        assertEquals(ReadingWarning.Implausible, warn(52_001, "2026-09-11"))
        assertNull(warn(51_999, "2026-09-11"))
        assertEquals(ReadingWarning.Implausible, warn(500_000, "2026-09-11"))
    }

    @Test fun `never questions a short absolute jump or a reading without history`() {
        assertNull(warn(50_300, "2026-09-01"))
        assertNull(warn(900_000, "2026-09-11", lastCounter = null, lastDate = null, rate = null))
        assertNull(warn(900_000, "2026-09-11", rate = null))
    }
}
