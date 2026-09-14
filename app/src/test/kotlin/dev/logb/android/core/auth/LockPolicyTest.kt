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

    @Test fun `availability maps the manager's codes`() {
        assertEquals(LockPolicy.Availability.Available, LockPolicy.availability(BiometricManager.BIOMETRIC_SUCCESS))
        assertEquals(LockPolicy.Availability.NoneEnrolled, LockPolicy.availability(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED))
        assertEquals(LockPolicy.Availability.NoHardware, LockPolicy.availability(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE))
        assertEquals(LockPolicy.Availability.Unavailable, LockPolicy.availability(BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED))
    }
}
