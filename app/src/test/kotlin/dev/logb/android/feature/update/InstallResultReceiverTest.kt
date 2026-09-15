package dev.logb.android.feature.update

import android.content.pm.PackageInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InstallResultReceiverTest {
    @Test fun `maps installer statuses to results`() {
        assertEquals(InstallResult.Success, InstallResultReceiver.resultFor(PackageInstaller.STATUS_SUCCESS, null))
        assertEquals(InstallResult.Cancelled, InstallResultReceiver.resultFor(PackageInstaller.STATUS_FAILURE_ABORTED, null))
        assertEquals(InstallResult.Failed("conflict"), InstallResultReceiver.resultFor(PackageInstaller.STATUS_FAILURE_CONFLICT, "conflict"))
        assertEquals(InstallResult.Failed("status 42"), InstallResultReceiver.resultFor(42, null))
    }

    /** Android asking the user to confirm is not a result yet. */
    @Test fun `pending user action is not a result`() = assertNull(InstallResultReceiver.resultFor(PackageInstaller.STATUS_PENDING_USER_ACTION, null))
}
