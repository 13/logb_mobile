package dev.logb.android.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The queue of writes not yet confirmed by the server. `seq` is the push order; `id` is the
 * `client_op_id` the server deduplicates on. A `create` op carries no value: at push time it reads
 * the row's current values, so edits made before the push change the row and queue nothing.
 */
@Entity(tableName = "ops", indices = [Index("id", unique = true), Index("entity_uuid")])
data class OpEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val id: String,
    val kind: String,
    val entity: String,
    @ColumnInfo(name = "entity_uuid") val entityUuid: String,
    val field: String?,
    @ColumnInfo(name = "value_json") val valueJson: String?,
    @ColumnInfo(name = "edited_at") val editedAt: String,
    val attempts: Int = 0,
    val dead: Boolean = false,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
)

/** One row: where this device stands against the server's change feed. */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val id: Int = 1,
    @ColumnInfo(name = "cursor_seq") val cursorSeq: Long = 0,
    val epoch: String? = null,
    /** `server_time - local_now` at the last response, in milliseconds; stamps every `edited_at`. */
    @ColumnInfo(name = "clock_offset_ms") val clockOffsetMs: Long = 0,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "last_synced_at") val lastSyncedAt: String? = null,
    @ColumnInfo(name = "bootstrap_needed") val bootstrapNeeded: Boolean = true,
)

/** The winning edit per field, compared against exactly as the server's `field_clock` is. */
@Entity(tableName = "field_clock", primaryKeys = ["entity", "entity_uuid", "field"])
data class FieldClockEntity(
    val entity: String,
    @ColumnInfo(name = "entity_uuid") val entityUuid: String,
    val field: String,
    @ColumnInfo(name = "edited_at") val editedAt: String,
    @ColumnInfo(name = "device_id") val deviceId: String,
)

/** What the blob store holds for a sha256: the original, the thumbnail, both or neither. */
@Entity(tableName = "blobs")
data class BlobEntity(
    @PrimaryKey val sha256: String,
    val size: Long,
    val mime: String,
    @ColumnInfo(name = "original_present") val originalPresent: Boolean,
    @ColumnInfo(name = "thumb_present") val thumbPresent: Boolean,
    @ColumnInfo(name = "last_access_at") val lastAccessAt: String?,
)
