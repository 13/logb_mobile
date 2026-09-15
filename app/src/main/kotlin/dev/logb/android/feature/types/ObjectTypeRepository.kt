package dev.logb.android.feature.types

import dev.logb.android.core.db.LogbDatabase
import dev.logb.android.core.db.entity.ObjectTypeEntity
import dev.logb.android.core.domain.CustomTypes
import dev.logb.android.core.domain.Tags
import dev.logb.android.core.domain.TypeCheck
import dev.logb.android.core.domain.TypeInput
import dev.logb.android.core.sync.Clock
import dev.logb.android.core.sync.LocalWriter
import kotlinx.coroutines.flow.first
import java.util.UUID

sealed interface TypeSave {
    data class Saved(val uuid: String) : TypeSave
    /** A `CustomTypes` code, or `name_taken` / `in_use`. */
    data class Refused(val code: String) : TypeSave
}

/** Own types, written through the op queue like objects; the same rules the server holds. */
class ObjectTypeRepository(private val db: LogbDatabase, private val writer: LocalWriter, private val onWrite: () -> Unit = {}) {
    private suspend fun nameTaken(name: String, except: String?): Boolean =
        db.objectTypeDao().live().first().any { it.uuid != except && Tags.fold(it.name) == Tags.fold(name) }

    suspend fun create(input: TypeInput): TypeSave {
        val valid = when (val c = CustomTypes.normalize(input)) { is TypeCheck.Invalid -> return TypeSave.Refused(c.code); is TypeCheck.Valid -> c.input }
        if (nameTaken(valid.name, null)) return TypeSave.Refused("name_taken")
        val uuid = UUID.randomUUID().toString().lowercase()
        val now = Clock.nowIso()
        writer.create("object_type", uuid) {
            db.objectTypeDao().upsert(ObjectTypeEntity(uuid, null, valid.name, valid.icon, CustomTypes.categoriesToJson(valid.categories), valid.counterUnit, now, now, null))
        }
        onWrite()
        return TypeSave.Saved(uuid)
    }

    suspend fun update(uuid: String, input: TypeInput): TypeSave {
        val t = db.objectTypeDao().get(uuid) ?: return TypeSave.Refused("name_invalid")
        val valid = when (val c = CustomTypes.normalize(input)) { is TypeCheck.Invalid -> return TypeSave.Refused(c.code); is TypeCheck.Valid -> c.input }
        if (nameTaken(valid.name, uuid)) return TypeSave.Refused("name_taken")
        val categories = CustomTypes.categoriesToJson(valid.categories)
        val changes = buildMap<String, Any?> {
            if (valid.name != t.name) put("name", valid.name)
            if (valid.icon != t.icon) put("icon", valid.icon)
            if (categories != t.categories) put("categories", categories)
            if (valid.counterUnit != t.counterUnit) put("counter_unit", valid.counterUnit)
        }
        if (changes.isNotEmpty()) {
            writer.set("object_type", uuid, changes) {
                db.objectTypeDao().upsert(t.copy(name = valid.name, icon = valid.icon, categories = categories, counterUnit = valid.counterUnit, updatedAt = Clock.nowIso()))
            }
            onWrite()
        }
        return TypeSave.Saved(uuid)
    }

    suspend fun delete(uuid: String): TypeSave {
        if (db.objectTypeDao().usage(CustomTypes.key(uuid)) > 0) return TypeSave.Refused("in_use")
        writer.delete("object_type", uuid)
        onWrite()
        return TypeSave.Saved(uuid)
    }
}
