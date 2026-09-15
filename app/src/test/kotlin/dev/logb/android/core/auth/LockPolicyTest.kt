package dev.logb.android.core.auth

import androidx.biometric.BiometricManager
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LockPolicyTest {
    @Test fun `disabled never locks`() {
        assertFalse(LockPolicy.shouldLock(enabled = false, backgroundedAt = null, now = 1_000_000))
    }

    @Test fun `a cold start locks, a short background stay does not, a long one does`() {
        assertTrue(LockPolicy.shouldLock(true, backgroundedAt = null, now = 1_000_000))
        assertFalse(LockPolicy.shouldLock(true, backgroundedAt = 1_000_000, now = 1_000_000 + 30_000))
        assertTrue(LockPolicy.shouldLock(true, backgroundedAt = 1_000_000, now = 1_000_000 + 61_000))
    }

    @Test fun `a target only forces a recheck when unlocked belief might be stale from a real background`() {
        // Currently unlocked and mid-resume (backgroundedAt not yet cleared by onForeground): recheck.
        assertTrue(LockPolicy.shouldRecheckForTarget(backgroundedAt = 1_000_000, currentlyLocked = false))
        // Never backgrounded this session: nothing stale to distrust.
        assertFalse(LockPolicy.shouldRecheckForTarget(backgroundedAt = null, currentlyLocked = false))
        // Already locked, or still unknown: leave it alone either way -- this must never itself unlock.
        assertFalse(LockPolicy.shouldRecheckForTarget(backgroundedAt = 1_000_000, currentlyLocked = true))
        assertFalse(LockPolicy.shouldRecheckForTarget(backgroundedAt = 1_000_000, currentlyLocked = null))
    }

    @Test fun `availability maps the manager's codes`() {
        assertEquals(LockPolicy.Availability.Available, LockPolicy.availability(BiometricManager.BIOMETRIC_SUCCESS))
        assertEquals(LockPolicy.Availability.NoneEnrolled, LockPolicy.availability(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED))
        assertEquals(LockPolicy.Availability.NoHardware, LockPolicy.availability(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE))
        assertEquals(LockPolicy.Availability.Unavailable, LockPolicy.availability(BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED))
    }
}
