package dev.logb.android.core.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.logb.android.core.db.entity.ReminderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderDao {
    @Upsert suspend fun upsert(vararg rows: ReminderEntity)

    @Query("SELECT * FROM reminders WHERE uuid = :uuid") suspend fun get(uuid: String): ReminderEntity?

    @Query("SELECT uuid FROM reminders WHERE server_id = :id") suspend fun uuidForServerId(id: Long): String?

    @Query("SELECT * FROM reminders WHERE object_uuid = :objectUuid AND deleted_at IS NULL ORDER BY done_at IS NOT NULL, due_date, created_at")
    fun forObject(objectUuid: String): Flow<List<ReminderEntity>>

    /** Every open reminder across every object: what the due banner and the object cards read. */
    @Query("SELECT * FROM reminders WHERE deleted_at IS NULL AND done_at IS NULL")
    fun allOpen(): Flow<List<ReminderEntity>>

    @Query("SELECT uuid FROM reminders WHERE object_uuid IN (:objectUuids) AND deleted_at IS NULL")
    suspend fun liveUuidsForObjects(objectUuids: List<String>): List<String>

    @Query("UPDATE reminders SET deleted_at = :now WHERE uuid IN (:uuids) AND deleted_at IS NULL")
    suspend fun tombstone(uuids: List<String>, now: String)

    /** `cascade_activity`'s unlink: a reminder must not keep naming an entry that is gone. */
    @Query("UPDATE reminders SET done_activity_uuid = NULL WHERE done_activity_uuid IN (:activityUuids)")
    suspend fun unlinkDoneActivities(activityUuids: List<String>)

    @Query("DELETE FROM reminders WHERE server_id IS NOT NULL") suspend fun deleteServerRows()
}
