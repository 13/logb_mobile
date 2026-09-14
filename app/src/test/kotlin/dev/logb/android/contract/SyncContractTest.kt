package dev.logb.android.contract

import dev.logb.android.core.auth.ApiFactory
import dev.logb.android.core.auth.FakeServerStore
import dev.logb.android.core.auth.FakeTokenStore
import dev.logb.android.core.auth.Session
import dev.logb.android.core.auth.SessionRepository
import dev.logb.android.core.db.TestDatabase
import dev.logb.android.core.network.ApiClient
import dev.logb.android.core.sync.PullEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The one test that talks to the real server: starts the `logb` binary named by the `logb.bin`
 * system property on a temp data dir, seeds it over REST, and drives sign-in, bootstrap and
 * pull against it. Skipped when no binary is named.
 */
@RunWith(RobolectricTestRunner::class)
class SyncContractTest {
    private val bin: String? = System.getProperty("logb.bin")?.takeIf { it.isNotBlank() }
    private lateinit var process: Process
    private lateinit var dataDir: File
    private var port = 0
    private val base get() = "http://127.0.0.1:$port/"
    private val http = OkHttpClient.Builder().cookieJar(dev.logb.android.core.auth.InMemoryCookieJar()).build()
    private val json = "application/json".toMediaType()

    @Before
    fun startServer() {
        assumeTrue("set -PlogbBin=/path/to/logb to run the contract test", bin != null)
        port = ServerSocket(0).use { it.localPort }
        dataDir = kotlin.io.path.createTempDirectory("logb-contract").toFile()
        process = ProcessBuilder(bin!!).apply {
            environment()["LOGB_DATA_DIR"] = dataDir.absolutePath
            environment()["LOGB_PORT"] = port.toString()
            environment()["LOGB_LOG"] = "warn"
            redirectErrorStream(true)
            redirectOutput(File(dataDir, "server.log"))
        }.start()
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            if (runCatching { http.newCall(Request.Builder().url(base + "api/health").build()).execute().use { it.isSuccessful } }.getOrDefault(false)) return
            Thread.sleep(200)
        }
        error("logb did not come up: ${File(dataDir, "server.log").readText()}")
    }

    @After
    fun stopServer() {
        if (::process.isInitialized) process.destroy()
        if (::dataDir.isInitialized) dataDir.deleteRecursively()
    }

    private fun call(method: String, path: String, body: String? = null): String =
        http.newCall(Request.Builder().url(base + "api" + path).method(method, body?.toRequestBody(json)).build()).execute().use {
            check(it.isSuccessful) { "$method $path -> ${it.code} ${it.body.string()}" }
            it.body.string()
        }

    private fun id(json: String): Long = Regex("\"id\":(\\d+)").find(json)!!.groupValues[1].toLong()

    private val png: ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==",
    )

    @Test
    fun signInBootstrapPullAndOffline() = runBlocking {
        // Seed, as a browser would.
        call("POST", "/auth/setup", """{"username":"ben","password":"correct horse","timezone":"Europe/Berlin"}""")
        val house = id(call("POST", "/objects", """{"name":"House","type":"home"}"""))
        val garage = id(call("POST", "/objects", """{"name":"Garage","type":"home","parent_id":$house}"""))
        id(call("POST", "/objects", """{"name":"Light","type":"appliance","parent_id":$garage}"""))
        val golf = id(call("POST", "/objects", """{"name":"Golf","type":"car","counter_unit":"km"}"""))
        val oil = id(call("POST", "/objects/$golf/activities", """{"date":"2026-03-01","category":"maintenance","title":"Oil change","counter_value":84210,"cost_cents":18900}"""))
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("activity_id", oil.toString())
            .addFormDataPart("file", "a.png", png.toRequestBody("image/png".toMediaType())).build()
        val att = id(http.newCall(Request.Builder().url(base + "api/objects/$golf/attachments").post(multipart).build()).execute().use { check(it.isSuccessful) { it.body.string() }; it.body.string() })
        call("PATCH", "/objects/$golf", """{"name":"Golf","type":"car","counter_unit":"km","cover_attachment_id":$att}""")
        val reminder = id(call("POST", "/objects/$golf/reminders", """{"title":"Oil","due_date":"2026-03-01"}"""))
        call("POST", "/reminders/$reminder/done", """{"activity_id":$oil}""")

        // The phone signs in and bootstraps.
        val sessions = SessionRepository(FakeServerStore(), FakeTokenStore(), ApiFactory { b, t, j -> ApiClient.create(b, t, j) })
        check(sessions.signIn(base, "ben", "correct horse").isSuccess)
        val signedIn = assertIs<Session.SignedIn>(sessions.session.value)
        val api = ApiClient.create(base, { signedIn.token })
        val db = TestDatabase.inMemory()
        val engine = PullEngine(db, api, deviceId = "contract-phone")
        engine.run()

        val light = db.objectDao().uuidForServerId(3)!!
        assertEquals(listOf("House", "Garage"), db.objectDao().ancestors(light).map { it.name })
        val golfUuid = db.objectDao().uuidForServerId(golf)!!
        val photo = db.attachmentDao().forObject(golfUuid).first().single()
        assertEquals("image/png", photo.file.mime)
        assertEquals(photo.attachment.uuid, db.objectDao().get(golfUuid)!!.coverAttachmentUuid)
        val done = db.reminderDao().forObject(golfUuid).first().single()
        assertNotNull(done.doneAt)
        assertEquals(db.activityDao().uuidForServerId(oil), done.doneActivityUuid)

        // The browser edits, deletes and creates; the phone pulls and sees all of it.
        call("PATCH", "/objects/$garage", """{"name":"Double garage","type":"home","parent_id":$house}""")
        call("DELETE", "/activities/$oil")
        val inspection = id(call("POST", "/objects/$golf/reminders", """{"title":"Inspection","due_date":"2026-10-01"}"""))
        engine.run()

        val garageUuid = db.objectDao().uuidForServerId(garage)!!
        assertEquals("Double garage", db.objectDao().get(garageUuid)!!.name)
        assertNotNull(db.fieldClockDao().get("object", garageUuid, "name"))
        assertNotNull(db.activityDao().get(db.activityDao().uuidForServerId(oil)!!)!!.deletedAt)
        assertNull(db.objectDao().get(golfUuid)!!.coverAttachmentUuid, "phase 0 logs the cover clear")
        assertNull(db.reminderDao().get(done.uuid)!!.doneActivityUuid, "phase 0 logs the unlink")
        assertNotNull(db.reminderDao().uuidForServerId(inspection))

        // The server goes away; the mirror stays.
        process.destroy(); process.waitFor()
        assertFailsWith<IOException> { engine.run() }
        assertEquals("Double garage", db.objectDao().get(garageUuid)!!.name)
        db.close()
    }
}
