package dev.logb.android.feature.settings.data

import dev.logb.android.core.network.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DataTransferTest {
    private val server = MockWebServer()
    @Before fun start() = server.start()
    @After fun stop() = server.close()

    private val zip = ByteArray(200_000) { (it % 251).toByte() }

    @Test fun `export streams the archive into the chosen document`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).addHeader("content-type", "application/zip").body(Buffer().write(zip)).build())
        val out = ByteArrayOutputStream()
        val written = DataTransfer.export(ApiClient.create(server.url("/").toString(), { "t" }), out)
        assertEquals(zip.size.toLong(), written)
        assertContentEquals(zip, out.toByteArray())
        assertEquals("/api/export", server.takeRequest().url.encodedPath)
    }

    @Test fun `import sends the document as a raw zip body and reads the counts`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).addHeader("content-type", "application/json")
            .body("""{"objects":2,"activities":5,"attachments":1,"reminders":0,"types_created":1,"types_merged":0}""").build())
        val counts = ApiClient.create(server.url("/").toString(), { "t" }).importZip(DataTransfer.zipBody({ ByteArrayInputStream(zip) }, zip.size.toLong()))
        assertEquals(2, counts.objects); assertEquals(1, counts.typesCreated)
        val req = server.takeRequest()
        assertEquals("/api/import", req.url.encodedPath)
        assertEquals("application/zip", req.headers["Content-Type"])
        assertEquals(zip.size.toLong(), req.bodySize)
    }

    @Test fun `export file name carries the date`() = assertEquals("logb-export-2026-09-15.zip", DataTransfer.exportFileName(LocalDate.parse("2026-09-15")))

    @Test fun `a null stream from a revoked grant surfaces as FileNotFoundException`() {
        assertFailsWith<FileNotFoundException> { DataTransfer.openOrThrow { null } }
    }

    @Test fun `a SecurityException from a revoked grant surfaces as FileNotFoundException`() {
        assertFailsWith<FileNotFoundException> { DataTransfer.openOrThrow { throw SecurityException("Permission Denial") } }
    }

    /**
     * A real, throttled body: `runBlocking`/`delay` are real wall-clock time here, not the virtual
     * time `runTest` would give a plain `delay` -- the throttle is enforced by MockWebServer on its
     * own thread, so the cancellation race needs real time to be meaningful.
     */
    @Test fun `cancelling an export stops reading the throttled body early`() = runBlocking {
        val big = ByteArray(4_000_000) { (it % 251).toByte() }
        server.enqueue(
            MockResponse.Builder().code(200).addHeader("content-type", "application/zip")
                .throttleBody(32 * 1024, 100, TimeUnit.MILLISECONDS)
                .body(Buffer().write(big)).build(),
        )
        val out = ByteArrayOutputStream()
        val job = launch(Dispatchers.IO) {
            DataTransfer.export(ApiClient.create(server.url("/").toString(), { "t" }), out)
        }
        delay(300) // enough real time for a few throttled chunks, nowhere near the whole body
        job.cancelAndJoin()

        assertTrue(out.size() in 1 until big.size, "export must have started but not finished reading the throttled body")
    }
}
