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

    /**
     * `RootViewModel.onForeground`'s synchronous pre-check -- run before the async read of the
     * setting lands -- is exactly `shouldLock(lockEnabled.value ?: true, backgroundedAt, now)`.
     * These mirror the five scenarios that check must get right, in the order the review asked
     * for them: the "not yet known" case is written as passing `true` directly, since that *is*
     * what `lockEnabled.value ?: true` resolves to at that call site -- `shouldLock` itself only
     * ever sees a resolved `Boolean`, never the `null`.
     */
    @Test fun `synchronous resume check -- lock disabled leaves the logbook on screen`() {
        assertFalse(LockPolicy.shouldLock(enabled = false, backgroundedAt = 1_000_000, now = 1_000_000 + 120_000))
    }

    @Test fun `synchronous resume check -- enabled but still within the grace period leaves it on screen`() {
        assertFalse(LockPolicy.shouldLock(enabled = true, backgroundedAt = 1_000_000, now = 1_000_000 + 30_000))
    }

    @Test fun `synchronous resume check -- enabled and past the grace period stops showing it`() {
        assertTrue(LockPolicy.shouldLock(enabled = true, backgroundedAt = 1_000_000, now = 1_000_000 + 61_000))
    }

    @Test fun `synchronous resume check -- setting not yet loaded and past the grace period must check, never assume unlocked`() {
        // lockEnabled.value is still null here; the call site passes `true` (never `false`) for it.
        assertTrue(LockPolicy.shouldLock(enabled = true, backgroundedAt = 1_000_000, now = 1_000_000 + 61_000))
    }

    @Test fun `synchronous resume check -- a cold start has no backgroundedAt to measure from`() {
        // RootViewModel only ever runs this check while `_locked == false`, which a cold start
        // (`_locked == null`) never is, but shouldLock's own answer for a null backgroundedAt is
        // unconditional locking, same as the always-locks-cold-start behaviour above.
        assertTrue(LockPolicy.shouldLock(enabled = true, backgroundedAt = null, now = 1_000_000))
    }

    @Test fun `a notification tap or share-in that arrives within the grace period does not force a recheck`() {
        // What used to be a separate shouldRecheckForTarget, now covered by the same synchronous
        // check: a tap 59s into the background must not needlessly tear down AppNavHost.
        assertFalse(LockPolicy.shouldLock(enabled = true, backgroundedAt = 1_000_000, now = 1_000_000 + 59_000))
    }

    @Test fun `availability maps the manager's codes`() {
        assertEquals(LockPolicy.Availability.Available, LockPolicy.availability(BiometricManager.BIOMETRIC_SUCCESS))
        assertEquals(LockPolicy.Availability.NoneEnrolled, LockPolicy.availability(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED))
        assertEquals(LockPolicy.Availability.NoHardware, LockPolicy.availability(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE))
        assertEquals(LockPolicy.Availability.Unavailable, LockPolicy.availability(BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED))
    }
}
