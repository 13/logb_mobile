package dev.logb.android.core.sync

import dev.logb.android.core.db.inTransaction
import dev.logb.android.core.db.LogbDatabase
import dev.logb.android.core.db.entity.ActivityEntity
import dev.logb.android.core.db.entity.AttachmentEntity
import dev.logb.android.core.db.entity.FileEntity
import dev.logb.android.core.db.entity.ObjectEntity
import dev.logb.android.core.db.entity.ReminderEntity
import dev.logb.android.core.db.entity.SyncStateEntity
import dev.logb.android.core.network.dto.BootstrapResult
import kotlinx.serialization.json.JsonObject

/**
 * The full snapshot into the mirror. Replaces every row the server knows (`server_id` set),
 * leaves rows born on this phone and not yet pushed, restarts the field clock -- the snapshot is
 * the truth, and queued ops carry their own `edited_at` to be judged on push -- and resets the
 * cursor and epoch. Nothing in the op queue is touched.
 */
class Bootstrap(private val db: LogbDatabase) {
    suspend fun apply(snapshot: BootstrapResult, deviceId: String, now: String = Clock.nowIso()) {
        val objectUuidById = uuidIndex(snapshot.objects)
        val activityUuidById = uuidIndex(snapshot.activities)
        val attachmentUuidById = uuidIndex(snapshot.attachments)
        val fileUuidById = uuidIndex(snapshot.files)

        val objects = snapshot.objects.map { r ->
            val m = RowMapper(r)
            ObjectEntity(
                uuid = m.str("client_uuid"), serverId = m.long("id"),
                name = m.str("name"), type = m.str("type"),
                counterUnit = m.strOrNull("counter_unit"), fuelUnit = m.strOrNull("fuel_unit"),
                description = m.strOrEmpty("description"),
                purchaseDate = m.strOrNull("purchase_date"), purchasePriceCents = m.longOrNull("purchase_price_cents"),
                archivedAt = m.strOrNull("archived_at"),
                coverAttachmentUuid = m.longOrNull("cover_attachment_id")?.let(attachmentUuidById::get),
                parentUuid = m.longOrNull("parent_id")?.let(objectUuidById::get),
                createdAt = m.str("created_at"), updatedAt = m.str("updated_at"), deletedAt = null,
            )
        }
        val files = snapshot.files.map { r ->
            val m = RowMapper(r)
            FileEntity(
                uuid = m.str("client_uuid"), serverId = m.long("id"), sha256 = m.str("sha256"),
                originalName = m.strOrEmpty("original_name"), mime = m.strOrEmpty("mime"), size = m.longOrNull("size") ?: 0,
                width = m.longOrNull("width"), height = m.longOrNull("height"), takenAt = m.strOrNull("taken_at"),
                createdAt = m.str("created_at"), deletedAt = null,
            )
        }
        val activities = snapshot.activities.mapNotNull { r ->
            val m = RowMapper(r)
            val objectUuid = objectUuidById[m.long("object_id")] ?: return@mapNotNull null
            ActivityEntity(
                uuid = m.str("client_uuid"), serverId = m.long("id"), objectUuid = objectUuid,
                date = m.str("date"), category = m.str("category"), title = m.str("title"), notes = m.strOrEmpty("notes"),
                counterValue = m.longOrNull("counter_value"), costCents = m.longOrNull("cost_cents"), quantityMilli = m.longOrNull("quantity_milli"),
                createdAt = m.str("created_at"), updatedAt = m.str("updated_at"), deletedAt = null,
            )
        }
        val attachments = snapshot.attachments.mapNotNull { r ->
            val m = RowMapper(r)
            val objectUuid = objectUuidById[m.long("object_id")] ?: return@mapNotNull null
            val fileUuid = fileUuidById[m.long("file_id")] ?: return@mapNotNull null
            AttachmentEntity(
                uuid = m.str("client_uuid"), serverId = m.long("id"), objectUuid = objectUuid,
                activityUuid = m.longOrNull("activity_id")?.let(activityUuidById::get), fileUuid = fileUuid,
                kind = m.str("kind"), caption = m.strOrEmpty("caption"), createdAt = m.str("created_at"), deletedAt = null,
            )
        }
        val reminders = snapshot.reminders.mapNotNull { r ->
            val m = RowMapper(r)
            val objectUuid = objectUuidById[m.long("object_id")] ?: return@mapNotNull null
            ReminderEntity(
                uuid = m.str("client_uuid"), serverId = m.long("id"), objectUuid = objectUuid,
                title = m.str("title"), notes = m.strOrEmpty("notes"),
                dueDate = m.strOrNull("due_date"), dueCounter = m.longOrNull("due_counter"),
                repeatMonths = m.longOrNull("repeat_months"), repeatCounter = m.longOrNull("repeat_counter"),
                snoozedUntil = m.strOrNull("snoozed_until"), doneAt = m.strOrNull("done_at"),
                doneActivityUuid = m.longOrNull("done_activity_id")?.let(activityUuidById::get),
                kind = m.strOrNull("kind") ?: "service", everyN = m.longOrNull("every_n"), everyUnit = m.strOrNull("every_unit"),
                createdAt = m.str("created_at"), deletedAt = null,
            )
        }

        db.inTransaction {
            db.attachmentDao().deleteServerRows()
            db.reminderDao().deleteServerRows()
            db.activityDao().deleteServerRows()
            db.fileDao().deleteServerRows()
            db.objectDao().deleteServerRows()
            db.objectDao().upsert(*objects.toTypedArray())
            db.fileDao().upsert(*files.toTypedArray())
            db.activityDao().upsert(*activities.toTypedArray())
            db.attachmentDao().upsert(*attachments.toTypedArray())
            db.reminderDao().upsert(*reminders.toTypedArray())
            db.fieldClockDao().clear()
            val previous = db.syncStateDao().get()
            db.syncStateDao().upsert(
                SyncStateEntity(
                    cursorSeq = snapshot.seq,
                    epoch = snapshot.epoch,
                    clockOffsetMs = Clock.offsetMs(snapshot.serverTime),
                    deviceId = previous?.deviceId ?: deviceId,
                    lastSyncedAt = now,
                    bootstrapNeeded = false,
                ),
            )
        }
    }

    private fun uuidIndex(rows: List<JsonObject>): Map<Long, String> =
        rows.associate { r -> RowMapper(r).let { it.long("id") to it.str("client_uuid") } }
}
