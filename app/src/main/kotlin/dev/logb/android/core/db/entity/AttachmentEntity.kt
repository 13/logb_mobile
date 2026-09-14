package dev.logb.android.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "attachments",
    indices = [Index("server_id", unique = true), Index("object_uuid"), Index("activity_uuid"), Index("file_uuid"), Index("deleted_at")],
)
data class AttachmentEntity(
    @PrimaryKey val uuid: String,
    @ColumnInfo(name = "server_id") val serverId: Long?,
    @ColumnInfo(name = "object_uuid") val objectUuid: String,
    @ColumnInfo(name = "activity_uuid") val activityUuid: String?,
    @ColumnInfo(name = "file_uuid") val fileUuid: String,
    val kind: String,
    val caption: String,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "deleted_at") val deletedAt: String?,
)
