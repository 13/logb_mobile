package dev.logb.android.core.sync

import dev.logb.android.core.db.LogbDatabase
import dev.logb.android.core.db.entity.FieldClockEntity
import dev.logb.android.core.db.entity.OpEntity
import dev.logb.android.core.db.entity.SyncStateEntity
import dev.logb.android.core.db.inTransaction
import dev.logb.android.core.network.LogbJson
import kotlinx.serialization.builtins.serializer
import java.util.UUID

/**
 * The one way the app writes its own data: change the mirror, queue what the server must hear,
 * stamp the clock, in one transaction. The rules are the spec's:
 *
 * - a `create` op carries no value; the push reads the row's current values, so edits made
 *   before the push change the row and queue nothing;
 * - a `set` op per changed field, stamped with the corrected clock, mirrored into `field_clock`
 *   so a pulled older edit loses to it;
 * - deleting a row whose create is still queued removes the row and its ops -- the server
 *   never hears of it; deleting a server row tombstones the same cascade a pulled delete does,
 *   drops the queued ops of everything tombstoned, and queues one `delete`.
 */
class LocalWriter(private val db: LogbDatabase) {
    suspend fun deviceId(): String {
        db.syncStateDao().get()?.let { return it.deviceId }
        val id = UUID.randomUUID().toString()
        db.syncStateDao().upsert(SyncStateEntity(deviceId = id))
        return id
    }

    /** Local time corrected by the last known server offset. */
    suspend fun now(): String = Clock.correctedNowIso(db.syncStateDao().get()?.clockOffsetMs ?: 0)

    suspend fun create(entity: String, uuid: String, insert: suspend () -> Unit) = db.inTransaction {
        insert()
        db.opDao().insert(OpEntity(id = UUID.randomUUID().toString(), kind = "create", entity = entity, entityUuid = uuid, field = null, valueJson = null, editedAt = now()))
    }

    /**
     * `changes` maps field name to the new value: `String?`, `Long?`/`Int?`, or for a reference
     * field the target's uuid as `String?`. `apply` is the caller's DAO write.
     */
    suspend fun set(entity: String, uuid: String, changes: Map<String, Any?>, apply: suspend () -> Unit) = db.inTransaction {
        apply()
        if (db.opDao().pendingCreate(uuid) != null) return@inTransaction
        val at = now()
        val device = deviceId()
        for ((field, value) in changes) {
            requireNotNull(FieldSpecs.of(entity, field)) { "$entity.$field is not a syncable field" }
            val json = when (value) {
                null -> "null"
                is String -> LogbJson.encodeToString(String.serializer(), value)
                is Number -> value.toLong().toString()
                else -> error("unsupported value for $field: $value")
            }
            db.opDao().insert(OpEntity(id = UUID.randomUUID().toString(), kind = "set", entity = entity, entityUuid = uuid, field = field, valueJson = json, editedAt = at))
            db.fieldClockDao().upsert(FieldClockEntity(entity, uuid, field, at, device))
        }
    }

    suspend fun delete(entity: String, uuid: String) = db.inTransaction {
        if (db.opDao().pendingCreate(uuid) != null) {
            val removed = hardDelete(entity, uuid)
            db.opDao().deleteForEntities(removed)
            return@inTransaction
        }
        val tombstoned = Cascade.tombstone(db, entity, uuid, now())
        db.opDao().deleteForEntities(tombstoned)
        db.opDao().insert(OpEntity(id = UUID.randomUUID().toString(), kind = "delete", entity = entity, entityUuid = uuid, field = null, valueJson = null, editedAt = now()))
    }

    /**
     * Removes a never-pushed row and everything under it. A child of an unpushed object cannot
     * itself have been pushed (its create waits behind the parent's), so the whole subtree is
     * local-only and can simply go.
     */
    private suspend fun hardDelete(entity: String, uuid: String): List<String> = when (entity) {
        "object" -> {
            val objects = listOf(uuid) + db.objectDao().descendantUuids(uuid)
            val activities = db.activityDao().allUuidsForObjects(objects)
            val attachments = db.attachmentDao().allUuidsUnder(objects, activities)
            val reminders = db.reminderDao().allUuidsForObjects(objects)
            db.attachmentDao().hardDelete(attachments)
            db.reminderDao().hardDelete(reminders)
            db.activityDao().hardDelete(activities)
            db.objectDao().hardDelete(objects)
            objects + activities + attachments + reminders
        }
        "activity" -> {
            val attachments = db.attachmentDao().allUuidsUnder(emptyList(), listOf(uuid))
            db.attachmentDao().hardDelete(attachments)
            db.reminderDao().unlinkDoneActivities(listOf(uuid))
            db.activityDao().hardDelete(listOf(uuid))
            listOf(uuid) + attachments
        }
        "reminder" -> { db.reminderDao().hardDelete(listOf(uuid)); listOf(uuid) }
        "attachment" -> { db.attachmentDao().clearCoversPointingAt(listOf(uuid)); db.attachmentDao().hardDelete(listOf(uuid)); listOf(uuid) }
        "object_type" -> { db.objectTypeDao().hardDelete(listOf(uuid)); listOf(uuid) }
        else -> emptyList()
    }
}
