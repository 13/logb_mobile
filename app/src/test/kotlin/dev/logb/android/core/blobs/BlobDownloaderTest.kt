package dev.logb.android.core.blobs

import androidx.test.core.app.ApplicationProvider
import dev.logb.android.core.db.T0
import dev.logb.android.core.db.TestDatabase
import dev.logb.android.core.db.entity.AttachmentEntity
import dev.logb.android.core.db.entity.FileEntity
import dev.logb.android.core.db.obj
import dev.logb.android.core.network.ApiClient
import dev.logb.android.core.sync.ConnectivityMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class BlobDownloaderTest {
    private val server = MockWebServer()
    private val db = TestDatabase.inMemory()
    private val store = BlobStore(ApplicationProvider.getApplicationContext())
    private class Net(unmetered: Boolean) : ConnectivityMonitor { override val isOnline = MutableStateFlow(true); override val isUnmetered = unmetered }
    private var settings = BlobSettings()
    private val original = "original bytes".toByteArray()
    private val sha = MessageDigest.getInstance("SHA-256").digest(original).joinToString("") { "%02x".format(it) }

    @Before fun start() { server.start() }
    @After fun stop() { server.close(); db.close() }

    private fun downloader(unmetered: Boolean) = BlobDownloader(db, ApiClient.create(server.url("/").toString(), { "t" }), store, Net(unmetered)) { settings }
    private fun bytes(b: ByteArray, type: String) = MockResponse.Builder().code(200).addHeader("content-type", type).body(Buffer().write(b)).build()

    private suspend fun seed() {
        db.objectDao().upsert(obj("golf", "Golf", serverId = 4))
        db.fileDao().upsert(FileEntity("f1", 12, sha, "a.jpg", "image/jpeg", original.size.toLong(), null, null, null, T0, null))
        db.attachmentDao().upsert(AttachmentEntity("t1", 33, "golf", null, "f1", "photo", "", T0, null))
    }

    @Test
    fun `after a pull the thumb is fetched on any connection and the original only on unmetered`() = runTest {
        seed()
        server.enqueue(bytes(byteArrayOf(1, 2, 3), "image/jpeg"))
        downloader(unmetered = false).runAfterPull()
        assertEquals("/api/files/12/thumb", server.takeRequest().url.encodedPath)
        assertTrue(store.hasThumb(sha)); assertFalse(store.hasOriginal(sha))
        assertEquals(1, server.requestCount)

        server.enqueue(bytes(original, "image/jpeg"))
        downloader(unmetered = true).runAfterPull()
        assertEquals("/api/files/12", server.takeRequest().url.encodedPath)
        assertTrue(store.hasOriginal(sha))
        assertTrue(db.blobDao().get(sha)!!.originalPresent)
    }

    @Test
    fun `the unmetered rule can be turned off, the budget binds, and wrong bytes are refused`() = runTest {
        seed()
        store.writeThumb(sha, byteArrayOf(1))
        settings = BlobSettings(budgetBytes = 5, originalsUnmeteredOnly = false)
        downloader(unmetered = false).runAfterPull()
        assertEquals(0, server.requestCount, "over budget: nothing fetched")
        settings = BlobSettings(budgetBytes = 1_000, originalsUnmeteredOnly = false)
        server.enqueue(bytes("not the same bytes".toByteArray(), "image/jpeg"))
        downloader(unmetered = false).runAfterPull()
        assertFalse(store.hasOriginal(sha), "a mismatching download is thrown away")
    }

    @Test
    fun `a cancelled download stops promptly and leaves no partial file behind`() = runTest {
        seed()
        // Trickled well below the transfer's own size (14 bytes): still mid-download when cancelled.
        server.enqueue(
            MockResponse.Builder().code(200).addHeader("content-type", "image/jpeg")
                .body(Buffer().write(original))
                .throttleBody(1, 100, TimeUnit.MILLISECONDS)
                .build(),
        )
        val d = downloader(unmetered = true)
        val scope = CoroutineScope(Dispatchers.Default)
        val job = scope.launch { d.runAfterPull() }
        // Real time, off the test scheduler: long enough for the read to have actually started.
        withContext(Dispatchers.Default) { delay(300) }

        val start = System.nanoTime()
        job.cancelAndJoin()
        val tookMs = (System.nanoTime() - start) / 1_000_000
        assertTrue(tookMs < 5_000, "cancellation took $tookMs ms; should be well under OkHttp's 60 s read timeout")

        assertFalse(store.hasOriginal(sha), "no completed original from a cancelled download")
        val filesDir = ApplicationProvider.getApplicationContext<android.content.Context>().filesDir
        val partials = filesDir.listFiles { f -> f.name.endsWith(".part") }?.toList() ?: emptyList()
        assertTrue(partials.isEmpty(), "no partial file left behind: $partials")
    }

    @Test
    fun `ensureOriginal fetches once on demand and later just returns the file`() = runTest {
        seed()
        server.enqueue(bytes(original, "image/jpeg"))
        val d = downloader(unmetered = false)
        assertNotNull(d.ensureOriginal(sha))
        assertNotNull(d.ensureOriginal(sha))
        assertEquals(1, server.requestCount)
        assertNotNull(db.blobDao().get(sha)!!.lastAccessAt)
    }
}
