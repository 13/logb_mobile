package dev.logb.android.core.db

import dev.logb.android.core.db.dao.SearchDao
import dev.logb.android.core.domain.Tags
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class ReminderDaoTest {
    private val db = TestDatabase.inMemory()

    @After fun close() = db.close()

    @Test
    fun `open reminders come first, done after, tombstones never`() = runTest {
        db.objectDao().upsert(obj("o1", "Golf"))
        db.reminderDao().upsert(
            rem("r-done", "o1", title = "Done", doneAt = T0),
            rem("r-open", "o1", title = "Open", dueDate = "2026-12-01"),
            rem("r-gone", "o1", title = "Gone").copy(deletedAt = T0),
        )
        assertEquals(listOf("Open", "Done"), db.reminderDao().forObject("o1").first().map { it.title })
        assertEquals(listOf("Open"), db.reminderDao().allOpen().first().map { it.title })
    }
}

@RunWith(RobolectricTestRunner::class)
class SearchDaoTest {
    private val db = TestDatabase.inMemory()

    @After fun close() = db.close()

    @Test
    fun `objects match on name or description and carry their parent's name`() = runTest {
        db.objectDao().upsert(obj("house", "House"), obj("garage", "Garage", parent = "house"), obj("light", "Main light", parent = "garage"))
        val hits = db.searchDao().objects(SearchDao.likePattern("light"), SearchDao.tagsPattern("light"))
        assertEquals(listOf("Main light"), hits.map { it.name })
        assertEquals("Garage", hits.single().parentName)
        assertEquals(null, db.searchDao().objects(SearchDao.likePattern("house"), SearchDao.tagsPattern("house")).single().parentName)
    }

    @Test
    fun `wildcards in the query are taken literally`() = runTest {
        db.objectDao().upsert(obj("a", "50% off"), obj("b", "500 off"))
        assertEquals(listOf("50% off"), db.searchDao().objects(SearchDao.likePattern("50%"), SearchDao.tagsPattern("50%")).map { it.name })
        db.activityDao().upsert(act("x", "a", "2026-01-01", title = "under_score"), act("y", "a", "2026-01-02", title = "underscore"))
        assertEquals(listOf("under_score"), db.searchDao().activities(SearchDao.likePattern("er_s"), SearchDao.tagsPattern("er_s")).map { it.title })
    }

    @Test
    fun `a tag on an object or an entry is found by a partial, case-insensitive match`() = runTest {
        db.objectDao().upsert(obj("a", "Sailboat").copy(tags = Tags.toJson(listOf("Winter"))))
        db.objectDao().upsert(obj("b", "Golf"))
        db.activityDao().upsert(act("x", "b", "2026-01-01", title = "Storage").copy(tags = Tags.toJson(listOf("Winter"))))
        assertEquals(listOf("Sailboat"), db.searchDao().objects(SearchDao.likePattern("wint"), SearchDao.tagsPattern("wint")).map { it.name })
        assertEquals(listOf("Storage"), db.searchDao().activities(SearchDao.likePattern("wint"), SearchDao.tagsPattern("wint")).map { it.title })
    }

    @Test
    fun `pure punctuation never matches every row through the tags column's own JSON syntax`() = runTest {
        db.objectDao().upsert(obj("a", "Sailboat").copy(tags = Tags.toJson(listOf("Winter"))))
        db.objectDao().upsert(obj("b", "Golf"))
        assertEquals(emptyList(), db.searchDao().objects(SearchDao.likePattern("\"["), SearchDao.tagsPattern("\"[")).map { it.name })
    }
}
