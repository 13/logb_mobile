package dev.logb.android.core.db

import androidx.room.Database
import androidx.room.RoomDatabase
import dev.logb.android.core.db.dao.ActivityDao
import dev.logb.android.core.db.dao.AttachmentDao
import dev.logb.android.core.db.dao.BlobDao
import dev.logb.android.core.db.dao.FieldClockDao
import dev.logb.android.core.db.dao.FileDao
import dev.logb.android.core.db.dao.ObjectDao
import dev.logb.android.core.db.dao.OpDao
import dev.logb.android.core.db.dao.ReminderDao
import dev.logb.android.core.db.dao.SearchDao
import dev.logb.android.core.db.dao.SyncStateDao
import dev.logb.android.core.db.entity.ActivityEntity
import dev.logb.android.core.db.entity.AttachmentEntity
import dev.logb.android.core.db.entity.BlobEntity
import dev.logb.android.core.db.entity.FieldClockEntity
import dev.logb.android.core.db.entity.FileEntity
import dev.logb.android.core.db.entity.ObjectEntity
import dev.logb.android.core.db.entity.OpEntity
import dev.logb.android.core.db.entity.ReminderEntity
import dev.logb.android.core.db.entity.SyncStateEntity

/** The mirror plus the local-only tables. One file per (server, user); see [DatabaseProvider]. */
@Database(
    entities = [
        ObjectEntity::class, ActivityEntity::class, AttachmentEntity::class, FileEntity::class, ReminderEntity::class,
        OpEntity::class, SyncStateEntity::class, FieldClockEntity::class, BlobEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class LogbDatabase : RoomDatabase() {
    abstract fun objectDao(): ObjectDao
    abstract fun activityDao(): ActivityDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun fileDao(): FileDao
    abstract fun reminderDao(): ReminderDao
    abstract fun opDao(): OpDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun fieldClockDao(): FieldClockDao
    abstract fun blobDao(): BlobDao
    abstract fun searchDao(): SearchDao
}
