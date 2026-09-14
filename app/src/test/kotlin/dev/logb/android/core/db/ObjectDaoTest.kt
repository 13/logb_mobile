package dev.logb.android.core.db

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class ObjectDaoTest {
    private val db = TestDatabase.inMemory()

    @After fun close() = db.close()

    @Test
    fun `stats sum cost and take the highest counter and the newest dates`() = runTest {
        db.objectDao().upsert(obj("o1", "Golf"))
        db.activityDao().upsert(act("a1", "o1", "2026-03-01", cost = 18900, counter = 84210))
        db.activityDao().upsert(act("a2", "o1", "2026-05-01", counter = 84000))
        db.activityDao().upsert(act("a3", "o1", "2026-06-01", cost = 5000))
        val s = db.objectDao().stats("o1")
        assertEquals(23900, s.totalCostCents)
        assertEquals(3, s.activityCount)
        assertEquals(84210, s.currentCounter)
        assertEquals("2026-06-01", s.lastActivityDate)
        assertEquals("2026-05-01", s.lastReadingDate)
    }

    @Test
    fun `a tombstoned activity counts for nothing`() = runTest {
        db.objectDao().upsert(obj("o1", "Golf"))
        db.activityDao().upsert(act("a1", "o1", "2026-03-01", cost = 18900, counter = 84210).copy(deletedAt = T0))
        val s = db.objectDao().stats("o1")
        assertEquals(0, s.activityCount)
        assertEquals(0, s.totalCostCents)
        assertEquals(null, s.currentCounter)
    }

    @Test
    fun `roots exclude children and archived unless asked`() = runTest {
        db.objectDao().upsert(obj("house", "House"), obj("garage", "Garage", parent = "house"), obj("old", "Old bike", archived = T0))
        assertEquals(listOf("House"), db.objectDao().roots(archived = false).first().map { it.name })
        assertEquals(listOf("Old bike"), db.objectDao().roots(archived = true).first().map { it.name })
        assertEquals(listOf("Garage"), db.objectDao().children("house").first().map { it.name })
    }

    @Test
    fun `ancestors run root first and descendants include every level`() = runTest {
        db.objectDao().upsert(obj("house", "House"), obj("garage", "Garage", parent = "house"), obj("light", "Light", parent = "garage"))
        assertEquals(listOf("House", "Garage"), db.objectDao().ancestors("light").map { it.name })
        assertEquals(emptyList(), db.objectDao().ancestors("house"))
        assertEquals(setOf("garage", "light"), db.objectDao().descendantUuids("house").toSet())
    }

    @Test
    fun `server ids map to uuids`() = runTest {
        db.objectDao().upsert(obj("u1", "Golf", serverId = 42))
        assertEquals("u1", db.objectDao().uuidForServerId(42))
        assertEquals(null, db.objectDao().uuidForServerId(43))
        assertEquals(42, db.objectDao().serverIdFor("u1"))
    }
}
