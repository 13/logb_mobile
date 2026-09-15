package dev.logb.android.core.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.logb.android.core.db.entity.ObjectTypeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ObjectTypeDao {
    @Upsert suspend fun upsert(vararg rows: ObjectTypeEntity)

    @Query("SELECT * FROM object_types WHERE uuid = :uuid") suspend fun get(uuid: String): ObjectTypeEntity?

    @Query("SELECT * FROM object_types WHERE deleted_at IS NULL ORDER BY name COLLATE NOCASE")
    fun live(): Flow<List<ObjectTypeEntity>>

    @Query("SELECT uuid FROM object_types WHERE server_id = :id") suspend fun uuidForServerId(id: Long): String?

    @Query("SELECT server_id FROM object_types WHERE uuid = :uuid") suspend fun serverIdFor(uuid: String): Long?

    @Query("UPDATE object_types SET deleted_at = :now, updated_at = :now WHERE uuid IN (:uuids) AND deleted_at IS NULL")
    suspend fun tombstone(uuids: List<String>, now: String)

    @Query("DELETE FROM object_types WHERE uuid IN (:uuids)") suspend fun hardDelete(uuids: List<String>)

    @Query("DELETE FROM object_types WHERE server_id IS NOT NULL") suspend fun deleteServerRows()

    /** Live objects whose `type` is [key] (`custom:<uuid>`); a type is deletable only at 0. */
    @Query("SELECT COUNT(*) FROM objects WHERE deleted_at IS NULL AND type = :key") suspend fun usage(key: String): Int
}
