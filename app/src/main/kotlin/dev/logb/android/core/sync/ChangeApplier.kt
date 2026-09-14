package dev.logb.android.core.sync

import androidx.room.withTransaction
import dev.logb.android.core.db.LogbDatabase
import dev.logb.android.core.db.entity.ActivityEntity
import dev.logb.android.core.db.entity.AttachmentEntity
import dev.logb.android.core.db.entity.FieldClockEntity
import dev.logb.android.core.db.entity.FileEntity
import dev.logb.android.core.db.entity.ObjectEntity
import dev.logb.android.core.db.entity.ReminderEntity
import dev.logb.android.core.network.dto.ChangeRow
import dev.logb.android.core.network.ApiClient.normalizeBaseUrl
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Applies one page of the change feed to the mirror, inside one transaction, with the server's
 * own rule for each row: a `create` announces a row (placeholder if unknown, otherwise just its
 * server id); a `set` is compared against the field clock and written only if it wins; a
 * `delete` tombstones with the same cascade a local delete uses.
 */
class ChangeApplier(private val db: LogbDatabase) {
    private val json = dev.logb.android.core.network.LogbJson

    suspend fun apply(rows: List<ChangeRow>) {
        if (rows.isEmpty()) return
        db.withTransaction {
            for (row in rows) applyOne(row)
        }
    }

    private suspend fun applyOne(r: ChangeRow) {
        if (r.entity !in FieldSpecs.entities) return
        when (r.op) {
            "create" -> ensureRow(r)
            "delete" -> if (exists(r.entity, r.entityUuid)) Cascade.tombstone(db, r.entity, r.entityUuid, Clock.canonical(r.editedAt) ?: Clock.nowIso())
            "set" -> applySet(r)
        }
    }

    private suspend fun applySet(r: ChangeRow) {
        val field = r.field ?: return
        val spec = FieldSpecs.of(r.entity, field) ?: return
        if (!exists(r.entity, r.entityUuid)) return
        val current = db.fieldClockDao().get(r.entity, r.entityUuid, field)
        if (!Lww.wins(r.editedAt, r.deviceId, current?.editedAt, current?.deviceId)) return
        val canonical = Clock.canonical(r.editedAt) ?: return

        // Double-encoded: the wire string is itself JSON. SQL NULL and the JSON null both clear.
        val element = r.value?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }
        val primitive = (element as? JsonPrimitive)?.takeUnless { it is JsonNull }
        val bound: Any? = when (spec) {
            FieldType.Text -> primitive?.contentOrNull
            FieldType.Integer -> primitive?.longOrNull
            is FieldType.Ref -> primitive?.longOrNull?.let { id ->
                resolve(spec.table, id) ?: run { db.syncStateDao().requestBootstrap(); null }
            }
        }
        FieldWriter.write(db, r.entity, r.entityUuid, field, bound, canonical)
        db.fieldClockDao().upsert(FieldClockEntity(r.entity, r.entityUuid, field, canonical, r.deviceId))
    }

    private suspend fun resolve(table: String, id: Long): String? = when (table) {
        "objects" -> db.objectDao().uuidForServerId(id)
        "activities" -> db.activityDao().uuidForServerId(id)
        "attachments" -> db.attachmentDao().uuidForServerId(id)
        "files" -> db.fileDao().uuidForServerId(id)
        else -> null
    }

    private suspend fun exists(entity: String, uuid: String): Boolean = when (entity) {
        "object" -> db.objectDao().get(uuid) != null
        "activity" -> db.activityDao().get(uuid) != null
        "reminder" -> db.reminderDao().get(uuid) != null
        "attachment" -> db.attachmentDao().get(uuid) != null
        "file" -> db.fileDao().get(uuid) != null
        else -> false
    }

    /**
     * A `create` for a uuid the phone made just confirms the server id. For an unknown uuid it
     * inserts a placeholder that the `set` rows following it fill in. An activity, attachment
     * or reminder placeholder needs a parent the feed has not told us yet -- the REST create
     * logged no `set` for `object_id` -- so it is inserted under a sentinel and healed by the
     * next bootstrap; the create-through-REST path (phase 2) and bootstrap are the normal way
     * these rows arrive with their references intact.
     */
    private suspend fun ensureRow(r: ChangeRow) {
        val now = Clock.canonical(r.editedAt) ?: Clock.nowIso()
        when (r.entity) {
            "object" -> {
                val existing = db.objectDao().get(r.entityUuid)
                if (existing != null) { if (existing.serverId == null && r.entityId != null) db.objectDao().upsert(existing.copy(serverId = r.entityId)) }
                else db.objectDao().upsert(ObjectEntity(r.entityUuid, r.entityId, "", "other", null, null, "", null, null, null, null, null, now, now, null))
            }
            "activity" -> {
                val existing = db.activityDao().get(r.entityUuid)
                if (existing != null) { if (existing.serverId == null && r.entityId != null) db.activityDao().upsert(existing.copy(serverId = r.entityId)) }
                else { db.activityDao().upsert(ActivityEntity(r.entityUuid, r.entityId, UNKNOWN_PARENT, now.take(10), "other", "", "", null, null, null, now, now, null)); db.syncStateDao().requestBootstrap() }
            }
            "reminder" -> {
                val existing = db.reminderDao().get(r.entityUuid)
                if (existing != null) { if (existing.serverId == null && r.entityId != null) db.reminderDao().upsert(existing.copy(serverId = r.entityId)) }
                else { db.reminderDao().upsert(ReminderEntity(r.entityUuid, r.entityId, UNKNOWN_PARENT, "", "", null, null, null, null, null, null, null, "service", null, null, now, null)); db.syncStateDao().requestBootstrap() }
            }
            "attachment" -> {
                val existing = db.attachmentDao().get(r.entityUuid)
                if (existing != null) { if (existing.serverId == null && r.entityId != null) db.attachmentDao().upsert(existing.copy(serverId = r.entityId)) }
                else { db.attachmentDao().upsert(AttachmentEntity(r.entityUuid, r.entityId, UNKNOWN_PARENT, null, UNKNOWN_PARENT, "document", "", now, null)); db.syncStateDao().requestBootstrap() }
            }
            "file" -> {
                val existing = db.fileDao().get(r.entityUuid)
                if (existing != null) { if (existing.serverId == null && r.entityId != null) db.fileDao().upsert(existing.copy(serverId = r.entityId)) }
                else { db.fileDao().upsert(FileEntity(r.entityUuid, r.entityId, "", "", "", 0, null, null, null, now, null)); db.syncStateDao().requestBootstrap() }
            }
        }
    }

    companion object {
        /** A parent the feed has not named. Filtered out of every screen by the join on the real row; healed by bootstrap. */
        const val UNKNOWN_PARENT = "?"
    }
}
