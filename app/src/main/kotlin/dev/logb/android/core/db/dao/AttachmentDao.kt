package dev.logb.android.core.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import dev.logb.android.core.db.entity.AttachmentEntity
import dev.logb.android.core.db.model.AttachmentWithFile
import kotlinx.coroutines.flow.Flow

@Dao
interface AttachmentDao {
    @Upsert suspend fun upsert(vararg rows: AttachmentEntity)

    @Query("SELECT * FROM attachments WHERE uuid = :uuid") suspend fun get(uuid: String): AttachmentEntity?

    @Query("SELECT uuid FROM attachments WHERE server_id = :id") suspend fun uuidForServerId(id: Long): String?

    @Transaction
    @Query("SELECT * FROM attachments WHERE object_uuid = :objectUuid AND deleted_at IS NULL ORDER BY created_at DESC")
    fun forObject(objectUuid: String): Flow<List<AttachmentWithFile>>

    @Transaction
    @Query("SELECT * FROM attachments WHERE activity_uuid IN (:activityUuids) AND deleted_at IS NULL ORDER BY created_at")
    fun forActivities(activityUuids: List<String>): Flow<List<AttachmentWithFile>>

    @Query("SELECT uuid FROM attachments WHERE activity_uuid IN (:activityUuids) AND deleted_at IS NULL")
    suspend fun liveUuidsForActivities(activityUuids: List<String>): List<String>

    @Query("SELECT uuid FROM attachments WHERE object_uuid IN (:objectUuids) AND deleted_at IS NULL")
    suspend fun liveUuidsForObjects(objectUuids: List<String>): List<String>

    @Query("UPDATE attachments SET deleted_at = :now WHERE uuid IN (:uuids) AND deleted_at IS NULL")
    suspend fun tombstone(uuids: List<String>, now: String)

    /** `record::clear_cover_of`: an object must not keep pointing at an attachment that is gone. */
    @Query("UPDATE objects SET cover_attachment_uuid = NULL WHERE cover_attachment_uuid IN (:attachmentUuids)")
    suspend fun clearCoversPointingAt(attachmentUuids: List<String>)

    @Query("DELETE FROM attachments WHERE server_id IS NOT NULL") suspend fun deleteServerRows()
}
