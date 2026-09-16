package dev.logb.android.feature.pairing

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
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
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * The "Switch account?" dialog's body must name the account this phone is about to sign out of,
 * not just its host -- see the release's final-review controller notes. This renders the real
 * [PairingConfirmDialog] (via [PairingConfirmHost]) against a real, signed-in [SessionRepository],
 * so a wrong string-resource argument order (username/host/host) would show up here exactly as it
 * would on a phone, not just as a field on [PairingPrompt.Replace] nobody actually displayed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class PairingConfirmDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val dispatcher = UnconfinedTestDispatcher()
    private val newServer = MockWebServer()
    private val oldServer = MockWebServer()
    private val serverStore = FakeServerStore()
    private val tokenStore = FakeTokenStore()
    private val sessions = SessionRepository(serverStore, tokenStore, ApiFactory { base, token, jar -> ApiClient.create(base, token, jar) })
    private val capabilities = ServerCapabilities(serverStore)
    private val shareInbox = ShareInbox()

    private fun drain() {
        shadowOf(android.os.Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        newServer.start()
        oldServer.start()
        runBlocking {
            serverStore.write(ServerRecord(oldServer.url("/").toString(), 1, "ben", 9))
            tokenStore.write("logb_pat_existing")
            sessions.restore()
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        newServer.close()
        oldServer.close()
    }

    @Test
    fun `the replace dialog names the current account, its host, and the new host`() {
        val link = Intent(Intent.ACTION_VIEW, Uri.parse("logb://pair?server=${java.net.URLEncoder.encode(newServer.url("/").toString(), "UTF-8")}&code=abc123"))
        shareInbox.offer(link)
        drain()

        compose.setContent { PairingConfirmHost(androidx.compose.runtime.remember { PairingConfirmViewModel(shareInbox, sessions, capabilities) }) }
        drain()

        val fromHost = oldServer.url("/").host + ":" + oldServer.url("/").port
        val toHost = newServer.url("/").host + ":" + newServer.url("/").port
        val expected = compose.activity.getString(dev.logb.android.R.string.pair_replace_body, "ben", fromHost, toHost)

        compose.onNodeWithText(expected).assertIsDisplayed()
    }

    private fun showRejected(message: String?) {
        compose.setContent { androidx.compose.material3.Text(pairErrorMessage(dev.logb.android.core.auth.PairError.Rejected(message))) }
        drain()
    }

    @Test
    fun `a short, plain server refusal is shown word for word`() {
        showRejected("device_name must not be empty")
        compose.onNodeWithText("device_name must not be empty").assertIsDisplayed()
    }

    @Test
    fun `a server refusal longer than 200 characters shows the generic text instead`() {
        showRejected("x".repeat(201))
        compose.onNodeWithText(compose.activity.getString(dev.logb.android.R.string.pair_rejected)).assertIsDisplayed()
    }

    @Test
    fun `a server refusal with control characters shows the generic text instead`() {
        showRejected("refused\nplease visit evil.example")
        compose.onNodeWithText(compose.activity.getString(dev.logb.android.R.string.pair_rejected)).assertIsDisplayed()
    }

    @Test
    fun `displayableServerMessage keeps exactly 200 plain characters and refuses controls and bidi overrides`() {
        val limit = "y".repeat(200)
        kotlin.test.assertEquals(limit, displayableServerMessage(limit))
        kotlin.test.assertNull(displayableServerMessage(limit + "y"))
        kotlin.test.assertNull(displayableServerMessage("tab\there"))
        kotlin.test.assertNull(displayableServerMessage("bell\u0007"))
        kotlin.test.assertNull(displayableServerMessage("abc\u202Eevil"))
        kotlin.test.assertNull(displayableServerMessage("   "))
        kotlin.test.assertEquals("Gerätename darf nicht leer sein", displayableServerMessage("Gerätename darf nicht leer sein"))
    }
}
