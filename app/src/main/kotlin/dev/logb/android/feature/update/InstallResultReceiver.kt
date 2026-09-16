package dev.logb.android.feature.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface InstallResult {
    data object Success : InstallResult
    data object Cancelled : InstallResult
    data class Failed(val message: String?) : InstallResult
}

/**
 * The outcome of an install session. The system creates this receiver, so results reach the view
 * model through a buffered shared flow rather than an injected dependency.
 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // Android always asks the user itself; this is that request. Recording it before the
            // startActivity attempt means the row can still offer it by hand if Android 14+ blocks
            // this background start -- keep trying it too, since most devices still allow it.
            val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
            track(status, confirm)
            if (confirm == null) mutableResults.tryEmit(InstallResult.Failed(null)) else context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        track(status, null)
        resultFor(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))?.let { mutableResults.tryEmit(it) }
    }

    companion object {
        const val ACTION = "dev.logb.android.INSTALL_RESULT"

        private val mutableResults = MutableSharedFlow<InstallResult>(extraBufferCapacity = 4)
        val results: SharedFlow<InstallResult> = mutableResults

        private val mutablePendingConfirmation = MutableStateFlow<Intent?>(null)

        /**
         * The confirm intent Android handed over for the install session currently open, kept
         * here because the receiver and the view model are separate objects that both need it.
         * Null once there is nothing left to confirm.
         */
        val pendingConfirmation: StateFlow<Intent?> = mutablePendingConfirmation.asStateFlow()

        fun resultFor(status: Int, message: String?): InstallResult? = when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> null
            PackageInstaller.STATUS_SUCCESS -> InstallResult.Success
            PackageInstaller.STATUS_FAILURE_ABORTED -> InstallResult.Cancelled
            else -> InstallResult.Failed(message ?: "status $status")
        }

        /**
         * Pure, so this is unit-testable without touching Android beyond the [Intent] value class:
         * a pending status records the confirm intent so it can be offered later; any final status
         * (success, cancelled, or another failure) clears it, since there is nothing left to confirm.
         */
        fun track(status: Int, confirm: Intent?) {
            mutablePendingConfirmation.value = if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) confirm else null
        }

        /** Hands over the pending confirm intent and clears it -- it is only good to open once. */
        fun consumePendingConfirmation(): Intent? = mutablePendingConfirmation.value.also { mutablePendingConfirmation.value = null }
    }
}
