package dev.logb.android.core.sync

import dev.logb.android.core.db.LogbDatabase

/**
 * What a delete takes with it, spelled out once and used by both doors -- a pulled `delete`
 * and (phase 2) a local one -- exactly as `record::cascade_object` and `cascade_activity` on
 * the server. One timestamp for the whole cascade. Must run inside a transaction.
 */
object Cascade {
    /** The uuids of every row tombstoned, the root included, so a local delete can drop their queued ops. */
    suspend fun tombstone(db: LogbDatabase, entity: String, uuid: String, now: String): List<String> = when (entity) {
        "object" -> tombstoneObject(db, uuid, now)
        "activity" -> tombstoneActivity(db, uuid, now)
        "attachment" -> tombstoneAttachment(db, uuid, now)
        "reminder" -> { db.reminderDao().tombstone(listOf(uuid), now); listOf(uuid) }
        "object_type" -> { db.objectTypeDao().tombstone(listOf(uuid), now); listOf(uuid) }
        else -> emptyList() // files are never deleted over sync; the server refuses the op too
    }

    private suspend fun tombstoneObject(db: LogbDatabase, uuid: String, now: String): List<String> {
        val objects = listOf(uuid) + db.objectDao().descendantUuids(uuid)
        val activities = db.activityDao().liveUuidsForObjects(objects)
        val attachments = db.attachmentDao().liveUuidsForObjects(objects)
        val reminders = db.reminderDao().liveUuidsForObjects(objects)
        db.attachmentDao().tombstone(attachments, now)
        db.reminderDao().tombstone(reminders, now)
        db.activityDao().tombstone(activities, now)
        db.objectDao().tombstone(objects, now)
        // A reminder elsewhere may have been marked done against one of these entries.
        if (activities.isNotEmpty()) db.reminderDao().unlinkDoneActivities(activities)
        return objects + activities + attachments + reminders
    }

    private suspend fun tombstoneActivity(db: LogbDatabase, uuid: String, now: String): List<String> {
        val attachments = db.attachmentDao().liveUuidsForActivities(listOf(uuid))
        if (attachments.isNotEmpty()) db.attachmentDao().clearCoversPointingAt(attachments)
        db.reminderDao().unlinkDoneActivities(listOf(uuid))
        db.attachmentDao().tombstone(attachments, now)
        db.activityDao().tombstone(listOf(uuid), now)
        return listOf(uuid) + attachments
    }

    private suspend fun tombstoneAttachment(db: LogbDatabase, uuid: String, now: String): List<String> {
        db.attachmentDao().clearCoversPointingAt(listOf(uuid))
        db.attachmentDao().tombstone(listOf(uuid), now)
        return listOf(uuid)
    }
}
