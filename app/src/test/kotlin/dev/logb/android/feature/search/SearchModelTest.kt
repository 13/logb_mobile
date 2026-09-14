package dev.logb.android.feature.search

import dev.logb.android.core.db.TestDatabase
import dev.logb.android.core.db.act
import dev.logb.android.core.db.obj
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class SearchModelTest {
    private val db = TestDatabase.inMemory()

    @After fun close() = db.close()

    @Test
    fun `a blank query yields nothing, a word finds objects and entries with their object's name`() = runTest {
        db.objectDao().upsert(obj("house", "House", type = "home"), obj("garage", "Garage", parent = "house", type = "home"))
        db.activityDao().upsert(act("a1", "garage", "2026-08-20", title = "Bulb replaced"))
        val model = SearchModel(db)
        assertTrue(model.results(flowOf("")).first().objects.isEmpty())
        val r = model.results(flowOf("bulb")).first()
        assertEquals(listOf("Bulb replaced" to "Garage"), r.entries.map { it.first.title to it.second })
        assertEquals(listOf("Garage"), model.results(flowOf("gar")).first().objects.map { it.name })
    }
}
