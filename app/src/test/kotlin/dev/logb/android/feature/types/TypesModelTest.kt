package dev.logb.android.feature.types

import dev.logb.android.core.db.TestDatabase
import dev.logb.android.core.db.entity.ObjectTypeEntity
import dev.logb.android.core.db.obj
import dev.logb.android.core.domain.CustomTypes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class TypesModelTest {
    private val db = TestDatabase.inMemory()
    private val model = TypesModel(db)
    private val boat = ObjectTypeEntity("u1", 1, "Boat", "tool", """["repair","other"]""", "h", "t", "t", null)

    @After fun close() = db.close()

    @Test
    fun `usage recomputes when an object of that type is created, retyped away, or deleted`() = runTest {
        db.objectTypeDao().upsert(boat)
        assertEquals(0, model.rows().first().single().usage, "no objects of this type yet")

        db.objectDao().upsert(obj("o1", "Sailboat", type = CustomTypes.key("u1")))
        assertEquals(1, model.rows().first().single().usage, "a new object of the type counts")

        db.objectDao().upsert(db.objectDao().get("o1")!!.copy(type = "other"))
        assertEquals(0, model.rows().first().single().usage, "retyping away drops the count")

        db.objectDao().upsert(obj("o2", "Dinghy", type = CustomTypes.key("u1")))
        assertEquals(1, model.rows().first().single().usage)
        db.objectDao().tombstone(listOf("o2"), "2026-09-15T00:00:00.000Z")
        assertEquals(0, model.rows().first().single().usage, "a deleted object drops the count too")
    }
}
