package dev.logb.android.feature.update

import android.content.Intent
import android.content.pm.PackageInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** [InstallResultReceiver.track] touches [Intent], so this needs the Robolectric runtime, like its siblings in this package. */
@RunWith(RobolectricTestRunner::class)
class InstallResultReceiverTest {
    @Test fun `maps installer statuses to results`() {
        assertEquals(InstallResult.Success, InstallResultReceiver.resultFor(PackageInstaller.STATUS_SUCCESS, null))
        assertEquals(InstallResult.Cancelled, InstallResultReceiver.resultFor(PackageInstaller.STATUS_FAILURE_ABORTED, null))
        assertEquals(InstallResult.Failed("conflict"), InstallResultReceiver.resultFor(PackageInstaller.STATUS_FAILURE_CONFLICT, "conflict"))
        assertEquals(InstallResult.Failed("status 42"), InstallResultReceiver.resultFor(42, null))
    }

    /** Android asking the user to confirm is not a result yet. */
    @Test fun `pending user action is not a result`() = assertNull(InstallResultReceiver.resultFor(PackageInstaller.STATUS_PENDING_USER_ACTION, null))

    @Test fun `a pending status records the confirm intent`() {
        val confirm = Intent(Intent.ACTION_VIEW)
        InstallResultReceiver.track(PackageInstaller.STATUS_PENDING_USER_ACTION, confirm)
        assertEquals(confirm, InstallResultReceiver.pendingConfirmation.value)
    }

    @Test fun `success, cancelled and other failures clear a pending confirm intent`() {
        InstallResultReceiver.track(PackageInstaller.STATUS_PENDING_USER_ACTION, Intent(Intent.ACTION_VIEW))
        InstallResultReceiver.track(PackageInstaller.STATUS_SUCCESS, null)
        assertNull(InstallResultReceiver.pendingConfirmation.value)

        InstallResultReceiver.track(PackageInstaller.STATUS_PENDING_USER_ACTION, Intent(Intent.ACTION_VIEW))
        InstallResultReceiver.track(PackageInstaller.STATUS_FAILURE_ABORTED, null)
        assertNull(InstallResultReceiver.pendingConfirmation.value)

        InstallResultReceiver.track(PackageInstaller.STATUS_PENDING_USER_ACTION, Intent(Intent.ACTION_VIEW))
        InstallResultReceiver.track(PackageInstaller.STATUS_FAILURE_CONFLICT, null)
        assertNull(InstallResultReceiver.pendingConfirmation.value)
    }

    @Test fun `consuming the pending confirm intent clears it`() {
        val confirm = Intent(Intent.ACTION_VIEW)
        InstallResultReceiver.track(PackageInstaller.STATUS_PENDING_USER_ACTION, confirm)

        assertEquals(confirm, InstallResultReceiver.consumePendingConfirmation())
        assertNull(InstallResultReceiver.pendingConfirmation.value)
        assertNull(InstallResultReceiver.consumePendingConfirmation())
    }

    /** Defence in depth: only a broadcast carrying this receiver's own action is a real install result. */
    @Test fun `only the receiver's own action is accepted`() {
        assertTrue(InstallResultReceiver.isOwnAction(InstallResultReceiver.ACTION))
        assertFalse(InstallResultReceiver.isOwnAction("android.intent.action.PACKAGE_REPLACED"))
        assertFalse(InstallResultReceiver.isOwnAction(null))
    }
}
