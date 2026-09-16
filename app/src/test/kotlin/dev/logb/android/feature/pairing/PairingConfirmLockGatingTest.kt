package dev.logb.android.feature.pairing

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.logb.android.core.auth.ApiFactory
import dev.logb.android.core.auth.FakeServerStore
import dev.logb.android.core.auth.FakeTokenStore
import dev.logb.android.core.auth.ServerRecord
import dev.logb.android.core.auth.SessionRepository
import dev.logb.android.core.network.ApiClient
import dev.logb.android.core.server.ServerCapabilities
import dev.logb.android.feature.share.ShareInbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals

/**
 * `MainActivity` mounts [PairingConfirmHost] only in its unlocked branches -- never alongside
 * `LockScreen` -- see `MainActivity.kt` and [PairingConfirmViewModel]'s own doc. A real
 * `MainActivity` Robolectric test would need a full Hilt test component (every screen it composes
 * is `@HiltViewModel`-backed, and this module has no Hilt test entry point wired up for a plain
 * unit test); this harness instead reproduces `MainActivity`'s own gating shape -- the ViewModel
 * (and the `shareInbox.pendingPairing` collector its `init` block starts) is only ever *created*
 * once `locked == false`, exactly like `hiltViewModel()` lazily creating it on first composition
 * -- around the *real* [PairingConfirmViewModel] (built with fakes, the same way
 * [PairingConfirmViewModelTest] builds it) and the real [PairingConfirmHost]/[PairingConfirmDialog]
 * composables, so what is under test is production code reacting to the gate, not a stand-in.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class PairingConfirmLockGatingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val dispatcher = UnconfinedTestDispatcher()
    private val server = MockWebServer()
    private val oldServer = MockWebServer()
    private val serverStore = FakeServerStore()
    private val tokenStore = FakeTokenStore()
    private val sessions = SessionRepository(serverStore, tokenStore, ApiFactory { base, token, jar -> ApiClient.create(base, token, jar) })
    private val capabilities = ServerCapabilities(serverStore)
    private val shareInbox = ShareInbox()

    // Set once PairingConfirmHost's ViewModel is actually created inside the composition below
    // (only once unlocked) -- captured here purely so the test can poll its `busy` flag; nothing
    // reads it before that point.
    private var viewModel: PairingConfirmViewModel? = null

    private fun json(body: String, code: Int = 200) = MockResponse.Builder().code(code).addHeader("content-type", "application/json").body(body).build()

    private fun pairIntent(base: String = server.url("/").toString(), code: String = "abc123") =
        Intent(Intent.ACTION_VIEW, Uri.parse("logb://pair?server=${java.net.URLEncoder.encode(base, "UTF-8")}&code=$code"))

    private fun drain() {
        shadowOf(android.os.Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        server.start()
        oldServer.start()
        // "Signed in" -- the deep link arrives while an account is already active, so the gate
        // under test (locked) is the only thing standing between it and the replace dialog.
        runBlocking {
            serverStore.write(ServerRecord(oldServer.url("/").toString(), 1, "ben", 9))
            tokenStore.write("logb_pat_existing")
            sessions.restore()
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        server.close()
        oldServer.close()
    }

    @Test
    fun `locked, a pairing link sits unseen -- unlocking shows the replace dialog once, and nothing redeems before Confirm`() {
        shareInbox.offer(pairIntent())
        drain()

        var locked by mutableStateOf(true)
        compose.setContent {
            // MainActivity's own shape: PairingConfirmHost -- and the ViewModel + collector it
            // creates -- exists only once unlocked, never alongside the lock screen.
            if (!locked) {
                val vm = remember { PairingConfirmViewModel(shareInbox, sessions, capabilities).also { viewModel = it } }
                PairingConfirmHost(vm)
            }
        }
        drain()

        // Locked: no dialog, no ViewModel even exists yet, and the link must not have been touched.
        compose.onNodeWithText("Switch account?").assertDoesNotExist()
        assertEquals(null, viewModel, "no ViewModel -- and so no collector -- exists while locked")
        assertEquals(0, server.requestCount)
        assertEquals(0, oldServer.requestCount)

        // Unlock: PairingConfirmHost mounts, its ViewModel starts collecting, and the prompt that
        // was waiting in ShareInbox appears -- exactly once.
        compose.runOnIdle { locked = false }
        drain()

        compose.onNodeWithText("Switch account?").assertIsDisplayed()
        assertEquals(0, server.requestCount, "showing the dialog must not itself touch the server")
        assertEquals(0, oldServer.requestCount)

        // Still nothing until Confirm is actually tapped.
        drain()
        assertEquals(0, server.requestCount)

        oldServer.enqueue(json("", code = 204)) // signOut()'s revoke-by-id
        server.enqueue(json("{}")) // health
        server.enqueue(json("""{"token":"logb_pat_paired","token_id":22,"user":{"id":2,"username":"ann","lang":"en"}}""")) // redeem
        server.enqueue(json("""{"id":2,"username":"ann","is_admin":false,"lang":"en"}""")) // me
        server.enqueue(json("""{"currency":"CHF","timezone":"Europe/Zurich"}""")) // settings
        server.enqueue(json("""{"status":"ok","version":"0.11.0","features":["pairing"]}""")) // health (version)

        compose.onNodeWithText("Sign in").performClick()

        val deadline = System.currentTimeMillis() + 5_000
        while (viewModel?.state?.value?.busy != false && System.currentTimeMillis() < deadline) {
            drain()
            Thread.sleep(20)
        }

        assertEquals(5, server.requestCount, "tapping Confirm redeems the link that was waiting")
    }
}
