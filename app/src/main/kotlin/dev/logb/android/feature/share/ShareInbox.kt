package dev.logb.android.feature.share

import android.content.Intent
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Where a launcher shortcut or a notification tap wants to land. */
enum class LaunchTarget {
    Due, Search, NewObject, Reminders, Reading;

    companion object {
        const val ACTION = "dev.logb.android.action.OPEN"
        const val EXTRA = "target"

        /** Which object a `Reminders` or `Reading` target opens; unused by the other targets. */
        const val EXTRA_OBJECT = "object"

        /** The target named by an intent, or null for any other intent (a plain launch, a share). */
        fun from(intent: Intent?): LaunchTarget? =
            if (intent?.action == ACTION) intent.getStringExtra(EXTRA)?.let { name -> entries.firstOrNull { it.name == name } } else null
    }
}

/** A launch target plus the object it names, for `Reminders` and `Reading` (null for the others). */
data class LaunchRequest(val target: LaunchTarget, val objectUuid: String?) {
    companion object {
        fun from(intent: Intent?): LaunchRequest? = LaunchTarget.from(intent)?.let { LaunchRequest(it, intent?.getStringExtra(LaunchTarget.EXTRA_OBJECT)) }
    }
}

/** Files shared into the app, waiting for the person to say which object they belong to; and launch targets waiting for the nav host. */
@Singleton
class ShareInbox @Inject constructor() {
    private val _pending = MutableStateFlow<List<Uri>>(emptyList())
    val pending: StateFlow<List<Uri>> = _pending

    private val _target = MutableStateFlow<LaunchRequest?>(null)
    val target: StateFlow<LaunchRequest?> = _target

    fun offer(intent: Intent?): Boolean {
        LaunchRequest.from(intent)?.let { _target.value = it; return true }
        val uris: List<Uri> = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(extra(intent))
            Intent.ACTION_SEND_MULTIPLE -> extras(intent)
            else -> emptyList()
        }
        if (uris.isEmpty()) return false
        _pending.value = uris
        return true
    }

    /** The launch request, once. */
    fun takeTarget(): LaunchRequest? = _target.value.also { _target.value = null }

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
