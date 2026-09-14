package dev.logb.android.feature.entries

import androidx.test.core.app.ApplicationProvider
import dev.logb.android.core.blobs.BlobStore
import dev.logb.android.core.db.TestDatabase
import dev.logb.android.core.db.act
import dev.logb.android.core.db.obj
import dev.logb.android.core.sync.LocalWriter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class AttachmentRepositoryTest {
    private val db = TestDatabase.inMemory()
    private val store = BlobStore(ApplicationProvider.getApplicationContext())
    private val writer = LocalWriter(db)
    private val repo = AttachmentRepository(db, store, writer)
    private fun fixture(name: String): File = File(javaClass.getResource("/fixtures/$name")!!.toURI())

    @After fun close() = db.close()

    @Test
    fun `import stores the blob, the thumb, the file and attachment rows, and one create op`() = runTest {
        db.objectDao().upsert(obj("golf", "Golf", serverId = 4)); db.activityDao().upsert(act("a1", "golf", "2026-09-01").copy(serverId = 7))
        val uuid = repo.import(fixture("rotated6.jpg").inputStream(), "photo.jpg", "image/jpeg", "golf", "a1", caption = " Receipt ")
        val a = assertNotNull(db.attachmentDao().get(uuid))
        assertEquals("photo", a.kind); assertEquals("Receipt", a.caption); assertEquals("a1", a.activityUuid)
        val f = assertNotNull(db.fileDao().get(a.fileUuid))
        assertEquals("image/jpeg", f.mime); assertEquals(800, f.width); assertEquals(1200, f.height); assertEquals("2026-09-01T10:30:00Z", f.takenAt)
        assertTrue(store.hasOriginal(f.sha256)); assertTrue(store.hasThumb(f.sha256))
        assertEquals(listOf("create" to "attachment"), db.opDao().pending().map { it.kind to it.entity })
        assertEquals("2026-09-01", repo.takenDate(uuid))
        assertEquals(1, db.attachmentDao().forActivities(listOf("a1")).first().size)
    }

    @Test
    fun `the same bytes twice reuse the file row, and a document is a document`() = runTest {
        db.objectDao().upsert(obj("golf", "Golf", serverId = 4))
        val first = repo.import(fixture("small.png").inputStream(), "a.png", "image/png", "golf", null)
        val second = repo.import(fixture("small.png").inputStream(), "b.png", "image/png", "golf", null)
        assertEquals(db.attachmentDao().get(first)!!.fileUuid, db.attachmentDao().get(second)!!.fileUuid)
        val doc = repo.import("%PDF-1.4".toByteArray().inputStream(), "manual.pdf", "application/pdf", "golf", null)
        assertEquals("document", db.attachmentDao().get(doc)!!.kind)
        assertNull(db.fileDao().get(db.attachmentDao().get(doc)!!.fileUuid)!!.width)
    }

    @Test
    fun `delete queues a delete for a pushed attachment and removes an unpushed one outright`() = runTest {
        db.objectDao().upsert(obj("golf", "Golf", serverId = 4))
        val unpushed = repo.import(fixture("small.png").inputStream(), "a.png", "image/png", "golf", null)
        repo.delete(unpushed)
        assertNull(db.attachmentDao().get(unpushed)); assertTrue(db.opDao().pending().isEmpty())
        val pushed = repo.import(fixture("landscape.jpg").inputStream(), "b.jpg", "image/jpeg", "golf", null)
        db.attachmentDao().upsert(db.attachmentDao().get(pushed)!!.copy(serverId = 11)); db.opDao().deleteForEntities(listOf(pushed))
        repo.setCover("golf", pushed)
        assertEquals(pushed, db.objectDao().get("golf")!!.coverAttachmentUuid)
        repo.delete(pushed)
        assertNotNull(db.attachmentDao().get(pushed)!!.deletedAt)
        assertNull(db.objectDao().get("golf")!!.coverAttachmentUuid, "the cover is cleared locally as the server's cascade does")
        assertEquals(listOf("set", "delete"), db.opDao().pending().map { it.kind })
    }
}
