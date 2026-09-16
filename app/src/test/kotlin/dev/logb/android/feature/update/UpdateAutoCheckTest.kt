package dev.logb.android.feature.update

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class UpdateAutoCheckTest {
    private val day = UpdateAutoCheck.DAY_MS
    private val now = 1_800_000_000_000L

    @Test fun `due only for release builds, when enabled, once a day`() {
        assertTrue(UpdateAutoCheck.isDue(enabled = true, debug = false, lastCheckedAt = 0L, now = now))
        assertTrue(UpdateAutoCheck.isDue(true, false, now - day, now))
        assertFalse(UpdateAutoCheck.isDue(true, false, now - day + 1, now))
        assertFalse(UpdateAutoCheck.isDue(false, false, 0L, now))
        assertFalse(UpdateAutoCheck.isDue(true, true, 0L, now))
    }

    /** A clock set back must not silence the check for however long it was set back. */
    @Test fun `a last check in the future is due`() = assertTrue(UpdateAutoCheck.isDue(true, false, now + day, now))

    @Test fun `only a newer readable version is worth showing`() {
        assertEquals("0.8.0", UpdateAutoCheck.newerThanInstalled("0.8.0", "0.7.1"))
        assertNull(UpdateAutoCheck.newerThanInstalled("0.7.1", "0.7.1"))
        assertNull(UpdateAutoCheck.newerThanInstalled(null, "0.7.1"))
        assertNull(UpdateAutoCheck.newerThanInstalled("nightly", "0.7.1"))
    }

    private class FakePrefs(var value: UpdateSettings = UpdateSettings()) : UpdatePrefsStore {
        private val flow = MutableStateFlow(value)
        override val settings: StateFlow<UpdateSettings> = flow
        override suspend fun current() = value
        override suspend fun setAutoCheck(enabled: Boolean) { value = value.copy(autoCheck = enabled); flow.value = value }
        override suspend fun recordCheck(at: Long, available: String?) { value = value.copy(lastCheckedAt = at, available = available); flow.value = value }
    }

    private fun autoCheck(prefs: UpdatePrefsStore, answer: () -> GitHubRelease): UpdateAutoCheck {
        val api = object : GitHubApi { override suspend fun latestRelease(repo: String) = answer() }
        val signatures = object : ApkSignatures { override fun installed() = emptySet<String>(); override fun ofArchive(file: File) = null }
        return UpdateAutoCheck(prefs, UpdateRepository(api, OkHttpClient(), ApplicationProvider.getApplicationContext(), signatures))
    }

    private fun release(tag: String) = GitHubRelease(
        tag,
        "https://github.com/13/logb_mobile/releases/tag/$tag",
        assets = listOf(GitHubAsset("LogB-${tag.removePrefix("v")}.apk", 1, "https://example.invalid/a.apk")),
    )

    @Test fun `a found update is recorded with the time`() = runTest {
        val prefs = FakePrefs()
        assertTrue(autoCheck(prefs) { release("v99.0.0") }.runIfDue(now, debug = false))
        assertEquals(UpdateSettings(autoCheck = true, lastCheckedAt = now, available = "99.0.0"), prefs.value)
    }

    @Test fun `an up-to-date answer clears what an earlier check found`() = runTest {
        val prefs = FakePrefs(UpdateSettings(lastCheckedAt = now - 2 * day, available = "0.0.9"))
        assertTrue(autoCheck(prefs) { release("v0.0.1") }.runIfDue(now, debug = false))
        assertEquals(UpdateSettings(lastCheckedAt = now, available = null), prefs.value)
    }

    @Test fun `a failed check changes nothing, so the next start tries again`() = runTest {
        val before = UpdateSettings(lastCheckedAt = 0L, available = null)
        val prefs = FakePrefs(before)
        assertFalse(autoCheck(prefs) { throw IOException("offline") }.runIfDue(now, debug = false))
        assertEquals(before, prefs.value)
    }

    @Test fun `not due means GitHub is not asked`() = runTest {
        val prefs = FakePrefs(UpdateSettings(lastCheckedAt = now - 1000))
        assertFalse(autoCheck(prefs) { error("must not be called") }.runIfDue(now, debug = false))
    }
}
