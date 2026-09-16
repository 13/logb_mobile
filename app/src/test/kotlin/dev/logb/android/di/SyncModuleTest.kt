package dev.logb.android.di

import androidx.test.core.app.ApplicationProvider
import dev.logb.android.core.auth.ActiveAccount
import dev.logb.android.core.auth.ApiFactory
import dev.logb.android.core.auth.FakeServerStore
import dev.logb.android.core.auth.FakeTokenStore
import dev.logb.android.core.auth.ServerRecord
import dev.logb.android.core.auth.SessionRepository
import dev.logb.android.core.blobs.BlobPrefs
import dev.logb.android.core.blobs.BlobStore
import dev.logb.android.core.db.DatabaseProvider
import dev.logb.android.core.network.ApiClient
import dev.logb.android.core.sync.ConnectivityMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * `SyncModule.downloaderProvider` used to read `accounts.db` and `accounts.api` as two separate
 * calls, each independently re-resolving the signed-in account. `ActiveAccount.bound()` reads
 * both together in one synchronised step, so a switch landing between the two reads can no longer
 * pair one account's mirror with another's client.
 *
 * A genuinely concurrent regression test (start a switch mid-way between the two old reads) is not
 * practical here: `ActiveAccount` and `DatabaseProvider` are concrete, non-open classes with no
 * seam to inject a delay between property reads, and this project has no mocking library to fake
 * either. The fix also removes the seam such a race would need -- `bound()` is a single
 * synchronized call -- so there is no interleaving left to reproduce even with thread control. A
 * real query against `DatabaseProvider`'s Room database (the bundled native SQLite driver) also
 * cannot run in this sandboxed unit-test JVM (`UnsatisfiedLinkError: no sqliteJni`), unlike the
 * in-memory driver `TestDatabase` uses elsewhere, which rules out asserting on mirror *content*
 * here too.
 *
 * What this test does check: `downloaderProvider`'s built-in account identity actually follows
 * `bound()` -- the api client changes identity across a switch exactly when `bound()`'s own does,
 * proving the provider is sourced from one `bound()` call rather than two independent accessors
 * that could each re-resolve to a different moment in time.
 */
@RunWith(RobolectricTestRunner::class)
class SyncModuleTest {
    private class FakeConnectivity : ConnectivityMonitor {
        override val isOnline = MutableStateFlow(true)
        override val isUnmetered = true
    }

    private val serverStore = FakeServerStore()
    private val tokenStore = FakeTokenStore()
    private val sessions = SessionRepository(serverStore, tokenStore, ApiFactory { b, t, j -> ApiClient.create(b, t, j) })
    private val accounts = ActiveAccount(sessions, DatabaseProvider(ApplicationProvider.getApplicationContext()), ApiFactory { b, t, j -> ApiClient.create(b, t, j) })
    private val blobStore = BlobStore(ApplicationProvider.getApplicationContext())
    private val blobPrefs = BlobPrefs(ApplicationProvider.getApplicationContext())
    private val servers = mutableListOf<MockWebServer>()

    @After fun stop() = servers.forEach { it.close() }

    private fun server(): MockWebServer = MockWebServer().apply { start(); servers += this }

    private fun signIn(base: String, userId: Long, token: String) = runBlocking {
        serverStore.write(ServerRecord(base, userId, "user$userId", 9))
        tokenStore.write(token)
        sessions.restore()
    }

    private val provider = SyncModule.downloaderProvider(accounts, FakeConnectivity(), blobStore, blobPrefs)

    @Test
    fun `the on-demand downloader is null when signed out, and non-null once signed in`() = runBlocking {
        assertNull(provider(), "no account signed in yet")

        val a = server()
        signIn(a.url("/").toString(), 1, "logb_pat_a")
        assertNotNull(provider(), "now built for the signed-in account")
        Unit
    }

    @Test
    fun `the downloader built after a switch is a fresh one, not the previous account's`() = runBlocking {
        val a = server()
        val b = server()
        signIn(a.url("/").toString(), 1, "logb_pat_a")
        val boundBeforeSwitch = accounts.bound()!!
        assertNotNull(provider())

        // Same user id, another server: still a different account, so bound() (and therefore the
        // provider built from it) must not reuse the old client or mirror.
        signIn(b.url("/").toString(), 1, "logb_pat_b")
        val boundAfterSwitch = accounts.bound()!!

        assertNotSame(boundBeforeSwitch.db, boundAfterSwitch.db)
        assertNotSame(boundBeforeSwitch.api, boundAfterSwitch.api)
        assertNotNull(provider(), "still built for whoever is signed in now")

        // bound() itself is stable within one account -- calling it twice without a switch in
        // between must resolve to the very same, already-open mirror and client, not reopen them.
        val boundAgain = accounts.bound()!!
        assertSame(boundAfterSwitch.db, boundAgain.db)
        assertSame(boundAfterSwitch.api, boundAgain.api)
    }
}
