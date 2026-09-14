package dev.logb.android.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A mirror row of the server's `objects`. Keyed by the sync uuid; `server_id` is null until the
 * server has confirmed the row. References are by uuid, translated from the server's integer
 * ids at apply time.
 */
@Entity(
    tableName = "objects",
    indices = [Index("server_id", unique = true), Index("parent_uuid"), Index("deleted_at")],
)
data class ObjectEntity(
    @PrimaryKey val uuid: String,
    @ColumnInfo(name = "server_id") val serverId: Long?,
    val name: String,
    val type: String,
    @ColumnInfo(name = "counter_unit") val counterUnit: String?,
    @ColumnInfo(name = "fuel_unit") val fuelUnit: String?,
    val description: String,
    @ColumnInfo(name = "purchase_date") val purchaseDate: String?,
    @ColumnInfo(name = "purchase_price_cents") val purchasePriceCents: Long?,
    @ColumnInfo(name = "archived_at") val archivedAt: String?,
    @ColumnInfo(name = "cover_attachment_uuid") val coverAttachmentUuid: String?,
    @ColumnInfo(name = "parent_uuid") val parentUuid: String?,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
    @ColumnInfo(name = "deleted_at") val deletedAt: String?,
)
