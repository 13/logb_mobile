package dev.logb.android

import dev.logb.android.core.db.entity.SyncStateEntity
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [RootViewModel.firstRunPending] decides the one thing [MainActivity] cares about: whether to
 * show the full-screen "Getting your logbook…" first-run state instead of the normal app. It must
 * say yes only before this account's *first ever* bootstrap, and never again afterwards -- even
 * though [SyncStateEntity.bootstrapNeeded] itself gets set again and again in the background (an
 * import, a placeholder [dev.logb.android.core.sync.ChangeApplier] has to heal, the 0.8.0
 * migration...), because those re-bootstraps must run quietly behind the normal UI.
 */
class RootViewModelTest {
    @Test fun `no sync state row at all -- never signed in to a mirror before -- shows the first-run screen`() {
        assertTrue(RootViewModel.firstRunPending(null))
    }

    @Test fun `a row exists but no bootstrap has landed yet (an offline write queued the row first) still shows it`() {
        val neverBootstrapped = SyncStateEntity(deviceId = "d", epoch = null, bootstrapNeeded = true)
        assertTrue(RootViewModel.firstRunPending(neverBootstrapped))
    }

    @Test fun `once a bootstrap has landed, a later re-bootstrap request does not bring the screen back`() {
        // This is exactly what an import leaves behind: requestBootstrap() sets the flag again,
        // but the epoch from the bootstrap that already ran is untouched.
        val bootstrappedThenReRequested = SyncStateEntity(deviceId = "d", epoch = "epoch-123", bootstrapNeeded = true)
        assertFalse(RootViewModel.firstRunPending(bootstrappedThenReRequested))
    }

    @Test fun `a bootstrapped, settled account shows no first-run screen either`() {
        val settled = SyncStateEntity(deviceId = "d", epoch = "epoch-123", bootstrapNeeded = false)
        assertFalse(RootViewModel.firstRunPending(settled))
    }
}
