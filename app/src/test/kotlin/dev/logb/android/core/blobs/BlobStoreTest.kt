package dev.logb.android.core.blobs

import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class BlobStoreTest {
    private val store = BlobStore(ApplicationProvider.getApplicationContext())
    private fun fixture(name: String): File = File(javaClass.getResource("/fixtures/$name")!!.toURI())

    @Test
    fun `writing bytes yields their sha and one file, twice over`() = runTest {
        val bytes = "hello logb".toByteArray()
        val w = store.writeOriginal(bytes.inputStream())
        assertEquals("9d0b8fb8a13ca8df2ce1a51e7de1a8e5f24b1ef6d8f7c0d8c5be2a5b0b8b7ab4".length, w.sha256.length)
        assertEquals(bytes.size.toLong(), w.size)
        assertTrue(store.original(w.sha256).isFile)
        assertTrue(store.original(w.sha256).path.contains("/blobs/${w.sha256.take(2)}/"))
        val again = store.writeOriginal(bytes.inputStream())
        assertEquals(w.sha256, again.sha256)
        assertEquals(1, File(store.original(w.sha256).parent!!).listFiles()!!.size)
        assertEquals(bytes.size.toLong(), store.usageBytes())
    }

    @Test
    fun `the thumbnail is 400 px on the long side and rotates an orientation-6 photo upright`() = runTest {
        val landscape = Thumbnails.render(fixture("landscape.jpg"))!!
        val l = BitmapFactory.decodeByteArray(landscape, 0, landscape.size)
        assertEquals(400, l.width); assertEquals(267, l.height)
        val rotated = Thumbnails.render(fixture("rotated6.jpg"))!!
        val r = BitmapFactory.decodeByteArray(rotated, 0, rotated.size)
        assertEquals(267, r.width); assertEquals(400, r.height)
        assertNull(Thumbnails.render(fixture("bootstrap.json")), "not an image")
    }

    @Test
    fun `meta reads the displayed size and the capture time`() {
        val m = Thumbnails.meta(fixture("rotated6.jpg"))
        assertEquals(800, m.width); assertEquals(1200, m.height)
        assertEquals("2026-09-01T10:30:00Z", m.takenAt)
        assertNull(Thumbnails.meta(fixture("small.png")).takenAt)
    }

    @Test
    fun `makeThumb stores the thumbnail beside the original and a server thumb is stored as given`() = runTest {
        val w = store.writeOriginal(fixture("small.png").inputStream())
        assertTrue(store.makeThumb(w.sha256))
        assertTrue(store.hasThumb(w.sha256))
        assertNotNull(BitmapFactory.decodeFile(store.thumb(w.sha256).path))
        store.writeThumb("abc", byteArrayOf(1, 2, 3))
        assertEquals(3, store.thumb("abc").length())
        assertFalse(store.makeThumb("missing"))
    }

    @Test
    fun `eviction removes least recently used originals until under budget, never thumbs or kept ones`() = runTest {
        val a = store.writeOriginal(ByteArray(1000) { 1 }.inputStream()).sha256
        val b = store.writeOriginal(ByteArray(1000) { 2 }.inputStream()).sha256
        val c = store.writeOriginal(ByteArray(1000) { 3 }.inputStream()).sha256
        store.writeThumb(a, byteArrayOf(9))
        val evicted = store.evictOriginalsOver(budget = 2500, keep = setOf(a), lru = listOf(a, b, c))
        assertEquals(listOf(b), evicted, "a is kept, b is the least recently used candidate, and one eviction gets under budget")
        assertTrue(store.hasOriginal(a)); assertFalse(store.hasOriginal(b)); assertTrue(store.hasOriginal(c)); assertTrue(store.hasThumb(a))
        assertEquals(2000, store.usageBytes())
        assertEquals(listOf(c), store.evictOriginalsOver(budget = 1500, keep = setOf(a), lru = listOf(a, c)))
        assertEquals(1000, store.usageBytes())
    }

    @Test
    fun `bytes from the server that do not hash to the expected sha are refused`() = runTest {
        assertFalse(store.writeOriginalFromServer("0".repeat(64), "wrong".toByteArray().inputStream()))
        assertEquals(0, store.usageBytes())
    }
}
