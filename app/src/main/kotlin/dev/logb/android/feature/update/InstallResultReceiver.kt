package dev.logb.android.feature.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

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
            // Android always asks the user itself; this is that request.
            val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
            if (confirm == null) mutableResults.tryEmit(InstallResult.Failed(null)) else context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        resultFor(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))?.let { mutableResults.tryEmit(it) }
    }

    companion object {
        const val ACTION = "dev.logb.android.INSTALL_RESULT"

        private val mutableResults = MutableSharedFlow<InstallResult>(extraBufferCapacity = 4)
        val results: SharedFlow<InstallResult> = mutableResults

        fun resultFor(status: Int, message: String?): InstallResult? = when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> null
            PackageInstaller.STATUS_SUCCESS -> InstallResult.Success
            PackageInstaller.STATUS_FAILURE_ABORTED -> InstallResult.Cancelled
            else -> InstallResult.Failed(message ?: "status $status")
        }
    }
}
