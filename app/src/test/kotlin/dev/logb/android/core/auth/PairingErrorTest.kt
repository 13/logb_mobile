package dev.logb.android.core.auth

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PairingErrorTest {
    @Test
    fun `classifyPairingError maps 400 and 429 to their own outcomes`() {
        assertEquals(PairError.Rejected("device_name must not be empty"), classifyPairingError(PairingRejected("device_name must not be empty")))
        assertEquals(PairError.RateLimited, classifyPairingError(PairingRateLimited("too many requests")))
    }

    @Test
    fun `a blank rejection message falls back to null, not an empty string`() {
        val error = classifyPairingError(PairingRejected(""))
        assertEquals(PairError.Rejected(null), error)
        assertNull((error as PairError.Rejected).message)
    }
}
