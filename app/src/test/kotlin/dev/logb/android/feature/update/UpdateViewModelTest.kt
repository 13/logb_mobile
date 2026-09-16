package dev.logb.android.feature.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the two bugs found in final review: [UpdateViewModel.confirmationIntent] used to consume
 * the pending confirm intent, stranding the row with no button if the person left Android's
 * dialog without acting; and nothing cleared a stale confirmation when a new install or check
 * session began. [ApkInstaller] was made `open` so a fake never has to touch a real
 * PackageInstaller; [UpdateRepository] and [ApkSignatures]/[GitHubApi] were already easy to
 * construct for real, the same way [UpdateRepositoryTest] and [UpdateAutoCheckTest] do.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class UpdateViewModelTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dispatcher = UnconfinedTestDispatcher()
    private val payload = ByteArray(1_000) { it.toByte() }
    private val installedKey = setOf("release-key-digest")

    @Before fun start() {
        Dispatchers.setMain(dispatcher)
        // Isolate each test from whatever a previous one left in this process-wide companion state.
        InstallResultReceiver.track(PackageInstaller.STATUS_SUCCESS, null)
    }

    @After fun stop() {
        Dispatchers.resetMain()
        InstallResultReceiver.track(PackageInstaller.STATUS_SUCCESS, null)
    }

    private class FakeGitHub(private val answer: () -> GitHubRelease) : GitHubApi {
        override suspend fun latestRelease(repo: String): GitHubRelease = answer()
    }

    private class FakeSignatures(private val installed: Set<String>, private val archive: Set<String>?) : ApkSignatures {
        override fun installed(): Set<String> = installed
        override fun ofArchive(file: File): Set<String>? = archive
    }

    /** Never touches a real PackageInstaller: the tests only care about the confirmation flow. */
    private class FakeApkInstaller(context: Context, private val allowed: Boolean = true) : ApkInstaller(context) {
        var installCount = 0
        override fun canInstall(): Boolean = allowed
        override fun install(file: File) { installCount++ }
    }

    private class FakePrefsStore(var value: UpdateSettings = UpdateSettings()) : UpdatePrefsStore {
        private val flow = MutableStateFlow(value)
        override val settings: Flow<UpdateSettings> = flow
        override suspend fun current() = value
        override suspend fun setAutoCheck(enabled: Boolean) { value = value.copy(autoCheck = enabled); flow.value = value }
        override suspend fun recordCheck(at: Long, available: String?) { value = value.copy(lastCheckedAt = at, available = available); flow.value = value }
    }

    /** No checksum published, so the download skips the digest check; the signature check still runs. */
    private fun release(tag: String = "v9.9.9") = GitHubRelease(
        tagName = tag,
        htmlUrl = "https://github.com/13/logb_mobile/releases/tag/$tag",
        assets = listOf(GitHubAsset("LogB-${tag.removePrefix("v")}.apk", payload.size.toLong(), "https://example.invalid/a.apk")),
    )

    private fun httpServing(): OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("ok")
            .body(payload.toResponseBody("application/vnd.android.package-archive".toMediaType())).build()
    }.build()

    private fun repository(): UpdateRepository =
        UpdateRepository(FakeGitHub { release() }, httpServing(), context, FakeSignatures(installedKey, installedKey))

    private fun viewModel(installer: ApkInstaller = FakeApkInstaller(context)): UpdateViewModel =
        UpdateViewModel(repository(), installer, FakePrefsStore(UpdateSettings(autoCheck = false)))

    /** Pumps both the coroutine test scheduler and Robolectric's shadowed main looper. */
    private fun TestScope.drain() {
        advanceUntilIdle()
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    /**
     * `check()`/`download()` do real (if tiny) I/O on [Dispatchers.IO], a real thread outside the
     * test scheduler's virtual time -- `drain()` alone can return before it has actually finished.
     * Polls with a short real sleep between each `drain()` until the state matches.
     */
    private fun TestScope.awaitState(vm: UpdateViewModel, timeoutMs: Long = 5_000, predicate: (UpdateUiState) -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!predicate(vm.state.value) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            drain()
        }
        assertTrue(predicate(vm.state.value), "timed out waiting for state; last was ${vm.state.value}")
    }

    private fun TestScope.readyViewModel(installer: ApkInstaller = FakeApkInstaller(context)): UpdateViewModel {
        val vm = viewModel(installer)
        vm.check()
        awaitState(vm) { it is UpdateUiState.Available }
        vm.download()
        awaitState(vm) { it is UpdateUiState.Ready }
        return vm
    }

    @Test fun `installing with a pending confirmation offers it on resume`() = runTest(dispatcher) {
        val vm = readyViewModel()
        vm.install()
        assertEquals(UpdateUiState.Installing, vm.state.value)

        val confirm = Intent(Intent.ACTION_VIEW)
        InstallResultReceiver.track(PackageInstaller.STATUS_PENDING_USER_ACTION, confirm)
        vm.onResumed()

        assertTrue(vm.state.value is UpdateUiState.NeedsConfirmation)
    }

    @Test fun `opening the confirmation leaves it available until a terminal status arrives`() = runTest(dispatcher) {
        val vm = readyViewModel()
        vm.install()
        val confirm = Intent(Intent.ACTION_VIEW)
        InstallResultReceiver.track(PackageInstaller.STATUS_PENDING_USER_ACTION, confirm)
        vm.onResumed()
        assertTrue(vm.state.value is UpdateUiState.NeedsConfirmation)

        // The person taps through to Android's dialog...
        val opened = vm.confirmationIntent()
        assertEquals(confirm, opened)
        assertEquals(UpdateUiState.Installing, vm.state.value)
        assertEquals(confirm, InstallResultReceiver.pendingConfirmation.value, "a peek must not consume it")

        // ...then leaves without choosing anything and comes back: the same intent is offered again.
        vm.onResumed()
        assertTrue(vm.state.value is UpdateUiState.NeedsConfirmation)
        assertEquals(confirm, vm.confirmationIntent())
    }

    @Test fun `a terminal status clears the pending confirmation and moves the row on`() = runTest(dispatcher) {
        val vm = readyViewModel()
        vm.install()
        InstallResultReceiver.track(PackageInstaller.STATUS_PENDING_USER_ACTION, Intent(Intent.ACTION_VIEW))
        assertNotNull(InstallResultReceiver.pendingConfirmation.value)

        // A real broadcast this time: the user cancelled Android's own confirmation dialog.
        val cancelled = Intent(InstallResultReceiver.ACTION).putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE_ABORTED)
        InstallResultReceiver().onReceive(context, cancelled)
        drain()

        assertNull(InstallResultReceiver.pendingConfirmation.value)
        assertTrue(vm.state.value is UpdateUiState.Ready, "cancelling returns to the downloaded file, not to nothing")

        // Even if the row had stayed on Installing, a resume must not resurrect the cleared intent.
        vm.onResumed()
        assertFalse(vm.state.value is UpdateUiState.NeedsConfirmation)
    }

    @Test fun `a new install clears a stale confirmation from a previous session`() = runTest(dispatcher) {
        val vm = readyViewModel()
        vm.install()
        InstallResultReceiver.track(PackageInstaller.STATUS_PENDING_USER_ACTION, Intent(Intent.ACTION_VIEW))
        assertNotNull(InstallResultReceiver.pendingConfirmation.value, "precondition: a confirmation is pending")

        // The old session never resolved (e.g. the process died with Android's dialog still up),
        // but a fresh install must never be offered that stale intent.
        vm.install()

        assertNull(InstallResultReceiver.pendingConfirmation.value)
    }

    @Test fun `starting a new check also clears a stale confirmation`() = runTest(dispatcher) {
        val vm = readyViewModel()
        vm.install()
        InstallResultReceiver.track(PackageInstaller.STATUS_PENDING_USER_ACTION, Intent(Intent.ACTION_VIEW))
        assertNotNull(InstallResultReceiver.pendingConfirmation.value)

        vm.check()

        assertNull(InstallResultReceiver.pendingConfirmation.value)
    }
}
