package dev.logb.android.feature.update

import dev.logb.android.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The daily check at app start. It only records what it found; the hub row and the About page
 * read that, and downloading always starts from the About page.
 */
@Singleton
class UpdateAutoCheck @Inject constructor(
    private val prefs: UpdatePrefsStore,
    private val repository: UpdateRepository,
) {
    /** True when GitHub answered and the answer was recorded. */
    suspend fun runIfDue(now: Long = System.currentTimeMillis(), debug: Boolean = BuildConfig.DEBUG): Boolean {
        val s = prefs.current()
        if (!isDue(s.autoCheck, debug, s.lastCheckedAt, now)) return false
        // A failed check is not recorded: the next start asks again instead of waiting a day.
        return prefs.record(repository.check(), now)
    }

    companion object {
        const val DAY_MS = 86_400_000L

        fun isDue(enabled: Boolean, debug: Boolean, lastCheckedAt: Long, now: Long): Boolean =
            !debug && enabled && (lastCheckedAt <= 0L || now < lastCheckedAt || now - lastCheckedAt >= DAY_MS)

        /** [available] when it reads as a version newer than [installed]; a stale record after updating is null. */
        fun newerThanInstalled(available: String?, installed: String): String? {
            val a = AppVersion.parse(available) ?: return null
            val i = AppVersion.parse(installed) ?: return null
            return if (a > i) a.toString() else null
        }
    }
}
