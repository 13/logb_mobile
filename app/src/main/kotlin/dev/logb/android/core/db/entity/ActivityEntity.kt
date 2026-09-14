package dev.logb.android.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "activities",
    indices = [Index("server_id", unique = true), Index("object_uuid", "date"), Index("deleted_at")],
)
data class ActivityEntity(
    @PrimaryKey val uuid: String,
    @ColumnInfo(name = "server_id") val serverId: Long?,
    @ColumnInfo(name = "object_uuid") val objectUuid: String,
    val date: String,
    val category: String,
    val title: String,
    val notes: String,
    @ColumnInfo(name = "counter_value") val counterValue: Long?,
    @ColumnInfo(name = "cost_cents") val costCents: Long?,
    @ColumnInfo(name = "quantity_milli") val quantityMilli: Long?,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
    @ColumnInfo(name = "deleted_at") val deletedAt: String?,
)
