package dev.logb.android.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "reminders",
    indices = [Index("server_id", unique = true), Index("object_uuid"), Index("deleted_at")],
)
data class ReminderEntity(
    @PrimaryKey val uuid: String,
    @ColumnInfo(name = "server_id") val serverId: Long?,
    @ColumnInfo(name = "object_uuid") val objectUuid: String,
    val title: String,
    val notes: String,
    @ColumnInfo(name = "due_date") val dueDate: String?,
    @ColumnInfo(name = "due_counter") val dueCounter: Long?,
    @ColumnInfo(name = "repeat_months") val repeatMonths: Long?,
    @ColumnInfo(name = "repeat_counter") val repeatCounter: Long?,
    @ColumnInfo(name = "snoozed_until") val snoozedUntil: String?,
    @ColumnInfo(name = "done_at") val doneAt: String?,
    @ColumnInfo(name = "done_activity_uuid") val doneActivityUuid: String?,
    /** `service` or `reading`; fixed at create, never set over sync. */
    val kind: String,
    @ColumnInfo(name = "every_n") val everyN: Long?,
    @ColumnInfo(name = "every_unit") val everyUnit: String?,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "deleted_at") val deletedAt: String?,
)
