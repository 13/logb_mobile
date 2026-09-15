package dev.logb.android.feature.settings.data

import dev.logb.android.core.network.ApiClient
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

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
}
