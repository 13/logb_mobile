package dev.logb.android.core.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.logb.android.core.db.entity.ActivityEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ActivityDao {
    @Upsert suspend fun upsert(vararg rows: ActivityEntity)

    @Query("SELECT * FROM activities WHERE uuid = :uuid") suspend fun get(uuid: String): ActivityEntity?

    @Query("SELECT uuid FROM activities WHERE server_id = :id") suspend fun uuidForServerId(id: Long): String?

    @Query("SELECT server_id FROM activities WHERE uuid = :uuid") suspend fun serverIdFor(uuid: String): Long?

    /** Newest first, as the timeline reads. */
    @Query("SELECT * FROM activities WHERE object_uuid = :objectUuid AND deleted_at IS NULL ORDER BY date DESC, created_at DESC")
    fun timeline(objectUuid: String): Flow<List<ActivityEntity>>

    /** Counter readings oldest first, for usage and reminder projections. */
    @Query("SELECT * FROM activities WHERE object_uuid = :objectUuid AND deleted_at IS NULL AND counter_value IS NOT NULL ORDER BY date, created_at")
    suspend fun readings(objectUuid: String): List<ActivityEntity>

    /** `api::activities::recent_titles`: distinct recent titles, newest use first. */
    @Query(
        """SELECT title FROM activities WHERE object_uuid = :objectUuid AND deleted_at IS NULL
           GROUP BY title ORDER BY MAX(date) DESC, MAX(created_at) DESC LIMIT :limit""",
    )
    suspend fun recentTitles(objectUuid: String, limit: Int = 20): List<String>

    @Query("SELECT uuid FROM activities WHERE object_uuid IN (:objectUuids) AND deleted_at IS NULL")
    suspend fun liveUuidsForObjects(objectUuids: List<String>): List<String>

    @Query("UPDATE activities SET deleted_at = :now, updated_at = :now WHERE uuid IN (:uuids) AND deleted_at IS NULL")
    suspend fun tombstone(uuids: List<String>, now: String)

    @Query("DELETE FROM activities WHERE server_id IS NOT NULL") suspend fun deleteServerRows()
}
