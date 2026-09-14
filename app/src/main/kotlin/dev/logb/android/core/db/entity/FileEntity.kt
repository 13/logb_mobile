package dev.logb.android.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Content-addressed and written once, as on the server; `sha256` is what the blob store is keyed on. */
@Entity(tableName = "files", indices = [Index("server_id", unique = true), Index("sha256")])
data class FileEntity(
    @PrimaryKey val uuid: String,
    @ColumnInfo(name = "server_id") val serverId: Long?,
    val sha256: String,
    @ColumnInfo(name = "original_name") val originalName: String,
    val mime: String,
    val size: Long,
    val width: Long?,
    val height: Long?,
    @ColumnInfo(name = "taken_at") val takenAt: String?,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "deleted_at") val deletedAt: String?,
)
