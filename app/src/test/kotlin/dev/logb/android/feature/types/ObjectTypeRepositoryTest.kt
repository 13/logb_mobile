package dev.logb.android.feature.types

import dev.logb.android.core.db.TestDatabase
import dev.logb.android.core.domain.CustomTypes
import dev.logb.android.core.domain.ObjectDraft
import dev.logb.android.core.domain.TypeInput
import dev.logb.android.core.sync.LocalWriter
import dev.logb.android.feature.objects.ObjectRepository
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs

@RunWith(RobolectricTestRunner::class)
class ObjectTypeRepositoryTest {
    private val db = TestDatabase.inMemory()
    private val writer = LocalWriter(db)
    private val repo = ObjectTypeRepository(db, writer)

    @Test fun `create stores the normalised type and queues a create`() = runBlocking {
        val saved = assertIs<TypeSave.Saved>(repo.create(TypeInput("  Boat ", "tool", listOf("repair", "repair"), "h")))
        val row = db.objectTypeDao().get(saved.uuid)!!
        assertEquals("Boat", row.name)
        assertEquals(listOf("repair", "other"), CustomTypes.categoriesFromJson(row.categories))
        assertEquals("create", db.opDao().pending().single().kind)
        assertEquals(saved.uuid, saved.uuid.lowercase())
    }

    @Test fun `names are unique ignoring case and accents`() = runBlocking {
        repo.create(TypeInput("Gerät", "tool", listOf("repair"), null))
        assertEquals(TypeSave.Refused("name_taken"), repo.create(TypeInput("GERAT", "tool", listOf("repair"), null)))
    }

    @Test fun `an invalid type is refused with the server's code`() = runBlocking {
        assertEquals(TypeSave.Refused("icon_invalid"), repo.create(TypeInput("Boat", "rocket", listOf("repair"), null)))
    }

    @Test fun `a type in use cannot be deleted`() = runBlocking {
        val uuid = (repo.create(TypeInput("Boat", "tool", listOf("repair"), null)) as TypeSave.Saved).uuid
        ObjectRepository(db, writer).create(ObjectDraft(name = "Sailboat", type = CustomTypes.key(uuid), counterUnit = null))
        assertEquals(TypeSave.Refused("in_use"), repo.delete(uuid))
    }

    @Test fun `an update queues only changed fields`() = runBlocking {
        val uuid = (repo.create(TypeInput("Boat", "tool", listOf("repair"), null)) as TypeSave.Saved).uuid
        db.opDao().pending().forEach { db.opDao().delete(it.id) }
        db.objectTypeDao().upsert(db.objectTypeDao().get(uuid)!!.copy(serverId = 3))
        repo.update(uuid, TypeInput("Boat", "home", listOf("repair"), null))
        assertEquals(listOf("icon"), db.opDao().pending().map { it.field })
    }
}
