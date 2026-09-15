package dev.logb.android.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A mirror row of the server's `object_types`: one of the user's own object types. */
@Entity(tableName = "object_types", indices = [Index("server_id", unique = true), Index("deleted_at")])
data class ObjectTypeEntity(
    @PrimaryKey val uuid: String,
    @ColumnInfo(name = "server_id") val serverId: Long?,
    val name: String,
    val icon: String,
    /** JSON array text of category keys, normalised (`CustomTypes.normalizeCategories`). */
    val categories: String,
    @ColumnInfo(name = "counter_unit") val counterUnit: String?,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
    @ColumnInfo(name = "deleted_at") val deletedAt: String?,
)
