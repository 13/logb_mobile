package dev.logb.android.feature.share

import android.content.Intent
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Files shared into the app, waiting for the person to say which object they belong to. */
@Singleton
class ShareInbox @Inject constructor() {
    private val _pending = MutableStateFlow<List<Uri>>(emptyList())
    val pending: StateFlow<List<Uri>> = _pending

    fun offer(intent: Intent?): Boolean {
        val uris: List<Uri> = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(extra(intent))
            Intent.ACTION_SEND_MULTIPLE -> extras(intent)
            else -> emptyList()
        }
        if (uris.isEmpty()) return false
        _pending.value = uris
        return true
    }

    /** Hands the files over to whoever attaches them, once. */
    fun take(): List<Uri> = _pending.value.also { _pending.value = emptyList() }

    fun clear() { _pending.value = emptyList() }

    @Suppress("DEPRECATION")
    private fun extra(i: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun extras(i: Intent): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableArrayListExtra(Intent.EXTRA_STREAM)) ?: emptyList()
}
