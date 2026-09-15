package dev.logb.android.contract

import dev.logb.android.core.auth.ApiFactory
import dev.logb.android.core.auth.FakeServerStore
import dev.logb.android.core.auth.FakeTokenStore
import dev.logb.android.core.auth.Session
import dev.logb.android.core.auth.SessionRepository
import dev.logb.android.core.db.TestDatabase
import dev.logb.android.core.network.ApiClient
import dev.logb.android.core.domain.ActivityDraft
import dev.logb.android.core.domain.CustomTypes
import dev.logb.android.core.domain.ObjectDraft
import dev.logb.android.core.domain.ReminderDraft
import dev.logb.android.core.domain.Tags
import dev.logb.android.core.domain.TypeInput
import dev.logb.android.core.sync.LocalWriter
import dev.logb.android.core.sync.PullEngine
import dev.logb.android.core.sync.PushEngine
import dev.logb.android.core.blobs.BlobDownloader
import dev.logb.android.core.blobs.BlobSettings
import dev.logb.android.core.blobs.BlobStore
import dev.logb.android.core.sync.ConnectivityMonitor
import dev.logb.android.feature.entries.AttachmentRepository
import kotlinx.coroutines.flow.MutableStateFlow
import dev.logb.android.feature.entries.ActivityRepository
import dev.logb.android.feature.objects.ObjectRepository
import dev.logb.android.feature.reminders.ReminderRepository
import dev.logb.android.feature.types.ObjectTypeRepository
import dev.logb.android.feature.types.TypeSave
import java.time.LocalDate
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

        // The browser edits and deletes; the phone pulls both through the feed alone.
        call("PATCH", "/objects/$garage", """{"name":"Double garage","type":"home","parent_id":$house}""")
        call("DELETE", "/activities/$oil")
        engine.run()
        val garageUuid = db.objectDao().uuidForServerId(garage)!!
        assertEquals("Double garage", db.objectDao().get(garageUuid)!!.name)
        assertNotNull(db.fieldClockDao().get("object", garageUuid, "name"))
        assertNotNull(db.activityDao().get(db.activityDao().uuidForServerId(oil)!!)!!.deletedAt)
        assertNull(db.objectDao().get(golfUuid)!!.coverAttachmentUuid, "phase 0 logs the cover clear")
        assertNull(db.reminderDao().get(done.uuid)!!.doneActivityUuid, "phase 0 logs the unlink")

        // The browser creates. A REST create reaches the feed without values, so this pull ends in a
        // bootstrap, which rebuilds the mirror and restarts the field clocks -- hence the pull above.
        val inspection = id(call("POST", "/objects/$golf/reminders", """{"title":"Inspection","due_date":"2026-10-01"}"""))
        engine.run()
        assertNotNull(db.reminderDao().uuidForServerId(inspection))
        assertEquals("Double garage", db.objectDao().get(garageUuid)!!.name)

        // The server goes away; the mirror stays.
        process.destroy(); process.waitFor()
        assertFailsWith<IOException> { engine.run() }
        assertEquals("Double garage", db.objectDao().get(garageUuid)!!.name)
        db.close()
    }

    @Test
    fun offlineCreatesEditsAndLastWriteWinsBothWays() = runBlocking {
        call("POST", "/auth/setup", """{"username":"ben","password":"correct horse","timezone":"Europe/Berlin"}""")
        val sessions = SessionRepository(FakeServerStore(), FakeTokenStore(), ApiFactory { b, t, j -> ApiClient.create(b, t, j) })
        check(sessions.signIn(base, "ben", "correct horse").isSuccess)
        val token = (sessions.session.value as Session.SignedIn).token
        val api = ApiClient.create(base, { token })
        val db = TestDatabase.inMemory()
        val pull = PullEngine(db, api, deviceId = "contract-phone")
        val push = PushEngine(db, api)
        pull.run() // empty bootstrap; the device id and clock land

        // Offline: a nested tree, an entry, a repeating reminder marked done.
        val writer = LocalWriter(db)
        val objects = ObjectRepository(db, writer); val activities = ActivityRepository(db, writer); val reminders = ReminderRepository(db, writer)
        val today = LocalDate.parse("2026-09-14")
        val house = objects.create(ObjectDraft(name = "House", type = "home", counterUnit = null))
        val garage = objects.create(ObjectDraft(name = "Garage", type = "home", counterUnit = null, parentUuid = house))
        val light = objects.create(ObjectDraft(name = "Light", type = "appliance", counterUnit = null, parentUuid = garage))
        val bulb = activities.create(light, ActivityDraft(date = "2026-08-20", category = "repair", title = "Bulb replaced", costCents = 499))
        val oil = reminders.create(light, ReminderDraft(title = "Check", dueDate = "2026-09-01", repeatMonths = 6), today)
        val successor = reminders.done(oil, bulb, today)!!
        objects.update(light, ObjectDraft(name = "Main light", type = "appliance", counterUnit = null, parentUuid = garage)) // before push: the create carries it
        assertEquals(listOf("create", "create", "create", "create", "create", "create"), db.opDao().pending().map { it.kind })

        push.run(); pull.run()
        assertEquals(emptyList(), db.opDao().pending())
        val serverObjects = call("GET", "/objects?all=true")
        for (uuid in listOf(house, garage, light)) check(serverObjects.contains(uuid)) { "server lacks $uuid" }
        assertNotNull(db.objectDao().get(light)!!.serverId)
        val lightId = db.objectDao().get(light)!!.serverId!!
        val garageId = db.objectDao().get(garage)!!.serverId!!
        check(call("GET", "/objects/$lightId").contains("\"parent_id\":$garageId"))
        check(call("GET", "/objects/$lightId").contains("\"name\":\"Main light\""))
        check(call("GET", "/objects/$lightId/activities").contains("Bulb replaced"))
        val serverReminders = call("GET", "/objects/$lightId/reminders")
        check(serverReminders.contains(oil) && serverReminders.contains(successor)) { serverReminders }
        check(serverReminders.contains("\"due_date\":\"2027-03-14\"")) { "successor six months on from the completion day: $serverReminders" }
        check(Regex("\"id\":\\d+,\"object_id\":$lightId,\"title\":\"Check\"[^}]*\"due_date\":\"2026-09-01\"[^}]*\"done_at\":\"20").containsMatchIn(serverReminders)) { "the original is done on the server: $serverReminders" }

        // Different fields on both sides while apart: both survive.
        call("PATCH", "/objects/$garageId", """{"name":"Double garage","type":"home","parent_id":${db.objectDao().get(house)!!.serverId}}""")
        objects.update(light, ObjectDraft(name = "Main light", type = "appliance", counterUnit = null, description = "LED", parentUuid = garage))
        push.run(); pull.run()
        assertEquals("Double garage", db.objectDao().get(garage)!!.name)
        check(call("GET", "/objects/$lightId").contains("\"description\":\"LED\""))

        // The same field on both sides: the newer edit wins on both. The phone's edit is stamped
        // later than the browser's, so the phone wins even though the browser's landed first.
        call("PATCH", "/objects/$lightId", """{"name":"Browser name","type":"appliance","parent_id":$garageId}""")
        Thread.sleep(20)
        objects.update(light, ObjectDraft(name = "Phone name", type = "appliance", counterUnit = null, description = "LED", parentUuid = garage))
        push.run(); pull.run()
        assertEquals("Phone name", db.objectDao().get(light)!!.name)
        check(call("GET", "/objects/$lightId").contains("\"name\":\"Phone name\""))

        // And the other way round: a browser edit made after the phone's queued one beats it.
        objects.update(light, ObjectDraft(name = "Stale phone name", type = "appliance", counterUnit = null, description = "LED", parentUuid = garage))
        Thread.sleep(20)
        call("PATCH", "/objects/$lightId", """{"name":"Newer browser name","type":"appliance","parent_id":$garageId}""")
        push.run(); pull.run()
        assertEquals("Newer browser name", db.objectDao().get(light)!!.name)
        assertEquals(emptyList(), db.opDao().pending(), "a superseded op is done with, not retried")
        db.close()
    }

    @Test
    fun photosUploadDedupAndDownloadAsThumbnails() = runBlocking {
        call("POST", "/auth/setup", """{"username":"ben","password":"correct horse","timezone":"Europe/Berlin"}""")
        val sessions = SessionRepository(FakeServerStore(), FakeTokenStore(), ApiFactory { b, t, j -> ApiClient.create(b, t, j) })
        check(sessions.signIn(base, "ben", "correct horse").isSuccess)
        val token = (sessions.session.value as Session.SignedIn).token
        val api = ApiClient.create(base, { token })
        val store = BlobStore(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        val net = object : ConnectivityMonitor { override val isOnline = MutableStateFlow(true); override val isUnmetered = true }

        // Phone A: an object with an entry and the same photo attached twice, all offline.
        val db = TestDatabase.inMemory()
        PullEngine(db, api, "phone-a").run()
        val writer = LocalWriter(db)
        val golf = ObjectRepository(db, writer).create(ObjectDraft(name = "Golf", type = "car", counterUnit = "km"))
        val entry = ActivityRepository(db, writer).create(golf, ActivityDraft(date = "2026-09-14", category = "repair", title = "Wipers"))
        val attachments = AttachmentRepository(db, store, writer)
        val jpeg = File(javaClass.getResource("/fixtures/landscape.jpg")!!.toURI()).readBytes()
        val first = attachments.import(jpeg.inputStream(), "first.jpg", "image/jpeg", golf, entry, caption = "Receipt")
        val second = attachments.import(jpeg.inputStream(), "second.jpg", "image/jpeg", golf, null)
        PushEngine(db, api, store).run(); PullEngine(db, api, "phone-a").run()
        assertEquals(emptyList(), db.opDao().pending())
        val golfId = db.objectDao().get(golf)!!.serverId!!
        val listed = call("GET", "/objects/$golfId/attachments")
        check(listed.contains(first) && listed.contains(second)) { listed }
        val fileIds = Regex("\"file_id\":(\\d+)").findAll(listed).map { it.groupValues[1] }.toSet()
        assertEquals(1, fileIds.size, "identical bytes are one file on the server: $listed")
        val serverFileUuid = db.fileDao().get(db.attachmentDao().get(first)!!.fileUuid)!!.uuid
        assertEquals(serverFileUuid, db.attachmentDao().get(second)!!.fileUuid, "both attachments point at the server's file row")

        // Phone B: a fresh mirror bootstraps and gets the thumbnail (and, on Wi-Fi, the original).
        val storeB = BlobStore(androidx.test.core.app.ApplicationProvider.getApplicationContext()).also { it.deleteAll(db.fileDao().get(serverFileUuid)!!.sha256) }
        val dbB = TestDatabase.inMemory()
        PullEngine(dbB, api, "phone-b").run()
        BlobDownloader(dbB, api, storeB, net) { BlobSettings() }.runAfterPull()
        val sha = dbB.fileDao().get(serverFileUuid)!!.sha256
        check(storeB.hasThumb(sha) && storeB.hasOriginal(sha))
        val serverThumb = http.newCall(Request.Builder().url(base + "api/files/${fileIds.single()}/thumb").build()).execute().use { it.body.bytes() }
        assertEquals(serverThumb.size.toLong(), storeB.thumb(sha).length(), "the thumbnail is the server's, byte for byte")
        assertEquals(jpeg.size.toLong(), storeB.original(sha).length())
        db.close(); dbB.close()
    }

    @Test
    fun tagsAndOwnTypesTravelBothWays() = runBlocking {
        call("POST", "/auth/setup", """{"username":"ben","password":"correct horse","timezone":"Europe/Berlin"}""")
        val sessions = SessionRepository(FakeServerStore(), FakeTokenStore(), ApiFactory { b, t, j -> ApiClient.create(b, t, j) })
        check(sessions.signIn(base, "ben", "correct horse").isSuccess)
        val token = (sessions.session.value as Session.SignedIn).token
        val api = ApiClient.create(base, { token })
        val db = TestDatabase.inMemory()
        val pull = PullEngine(db, api, deviceId = "contract-phone")
        val push = PushEngine(db, api)
        pull.run() // empty bootstrap; the device id and clock land

        // Offline: an own type, an object of that type carrying a tag, and an entry carrying its own tag.
        val writer = LocalWriter(db)
        val types = ObjectTypeRepository(db, writer)
        val objects = ObjectRepository(db, writer)
        val activities = ActivityRepository(db, writer)
        val typeSave = types.create(TypeInput("Boat", "tool", listOf("repair"), "h"))
        val typeUuid = (typeSave as TypeSave.Saved).uuid
        val objectUuid = objects.create(ObjectDraft(name = "Sailboat", type = CustomTypes.key(typeUuid), counterUnit = "h", tags = listOf("Summer")))
        activities.create(objectUuid, ActivityDraft(date = "2026-06-01", category = "repair", title = "Hull", tags = listOf("Winter")))

        push.run(); pull.run()

        val serverTypes = call("GET", "/types")
        check(serverTypes.contains("Boat") && serverTypes.contains("custom:$typeUuid")) { serverTypes }
        val serverObjects = call("GET", "/objects?all=true")
        check(serverObjects.contains("custom:$typeUuid") && serverObjects.contains("Summer")) { serverObjects }
        val objectId = db.objectDao().serverIdFor(objectUuid)!!
        val serverActivities = call("GET", "/objects/$objectId/activities")
        check(serverActivities.contains("Winter")) { serverActivities }

        // The browser edits: renames the type, and adds a tag to the object.
        val typeId = db.objectTypeDao().serverIdFor(typeUuid)!!
        call("PATCH", "/types/$typeId", """{"name":"Yacht","icon":"tool","categories":["repair"],"counter_unit":"h"}""")
        call("PATCH", "/objects/$objectId", """{"name":"Sailboat","type":"custom:$typeUuid","counter_unit":"h","tags":["Summer","Lease"]}""")
        pull.run()

        assertEquals("Yacht", db.objectTypeDao().get(typeUuid)!!.name)
        assertEquals(listOf("Summer", "Lease"), Tags.fromJson(db.objectDao().get(objectUuid)!!.tags))
        db.close()
    }
}
