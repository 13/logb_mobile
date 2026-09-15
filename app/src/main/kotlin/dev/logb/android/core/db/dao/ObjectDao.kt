package dev.logb.android.core.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.logb.android.core.db.entity.ObjectEntity
import dev.logb.android.core.db.model.Ancestor
import dev.logb.android.core.db.model.ObjectStats
import dev.logb.android.core.db.model.ObjectStatsRow
import kotlinx.coroutines.flow.Flow

@Dao
interface ObjectDao {
    @Upsert suspend fun upsert(vararg rows: ObjectEntity)

    @Query("SELECT * FROM objects WHERE uuid = :uuid") suspend fun get(uuid: String): ObjectEntity?

    @Query("SELECT * FROM objects WHERE uuid = :uuid AND deleted_at IS NULL") fun observe(uuid: String): Flow<ObjectEntity?>

    @Query("SELECT uuid FROM objects WHERE server_id = :id") suspend fun uuidForServerId(id: Long): String?

    @Query("SELECT server_id FROM objects WHERE uuid = :uuid") suspend fun serverIdFor(uuid: String): Long?

    @Query(
        """SELECT * FROM objects WHERE deleted_at IS NULL AND parent_uuid IS NULL
           AND ((:archived = 0 AND archived_at IS NULL) OR (:archived = 1 AND archived_at IS NOT NULL))
           ORDER BY name COLLATE NOCASE""",
    )
    fun roots(archived: Boolean): Flow<List<ObjectEntity>>

    @Query("SELECT * FROM objects WHERE deleted_at IS NULL AND parent_uuid = :parent ORDER BY name COLLATE NOCASE")
    fun children(parent: String): Flow<List<ObjectEntity>>

    @Query("SELECT * FROM objects WHERE deleted_at IS NULL ORDER BY name COLLATE NOCASE")
    fun all(): Flow<List<ObjectEntity>>

    /** `api::objects::stats`: totals never stored, always summed on read. */
    @Query(
        """SELECT COALESCE(SUM(cost_cents), 0) AS totalCostCents, COUNT(*) AS activityCount,
           MAX(counter_value) AS currentCounter, MAX(date) AS lastActivityDate,
           (SELECT MAX(date) FROM activities WHERE object_uuid = :uuid AND deleted_at IS NULL AND counter_value IS NOT NULL) AS lastReadingDate
           FROM activities WHERE object_uuid = :uuid AND deleted_at IS NULL""",
    )
    suspend fun stats(uuid: String): ObjectStats

    /** `stats(uuid)` for every live object at once: the objects list, due list and digest read this instead of looping. */
    @Query(
        """SELECT o.uuid AS objectUuid,
                  COALESCE(SUM(a.cost_cents), 0) AS totalCostCents,
                  COUNT(a.uuid) AS activityCount,
                  MAX(a.counter_value) AS currentCounter,
                  MAX(a.date) AS lastActivityDate,
                  MAX(CASE WHEN a.counter_value IS NOT NULL THEN a.date END) AS lastReadingDate
           FROM objects o LEFT JOIN activities a ON a.object_uuid = o.uuid AND a.deleted_at IS NULL
           WHERE o.deleted_at IS NULL
           GROUP BY o.uuid""",
    )
    suspend fun statsForAll(): List<ObjectStatsRow>

    /** Root first, nearest ancestor last, the object itself excluded. */
    @Query(
        """WITH RECURSIVE up(uuid, name, parent_uuid, depth) AS (
             SELECT o.uuid, o.name, o.parent_uuid, 0 FROM objects o
             WHERE o.uuid = (SELECT parent_uuid FROM objects WHERE uuid = :uuid)
             UNION ALL
             SELECT o.uuid, o.name, o.parent_uuid, up.depth + 1 FROM objects o JOIN up ON o.uuid = up.parent_uuid
           )
           SELECT uuid, name FROM up ORDER BY depth DESC""",
    )
    suspend fun ancestors(uuid: String): List<Ancestor>

    /** Every live object underneath this one, any depth. */
    @Query(
        """WITH RECURSIVE down(uuid) AS (
             SELECT uuid FROM objects WHERE parent_uuid = :uuid AND deleted_at IS NULL
             UNION ALL
             SELECT o.uuid FROM objects o JOIN down ON o.parent_uuid = down.uuid WHERE o.deleted_at IS NULL
           )
           SELECT uuid FROM down""",
    )
    suspend fun descendantUuids(uuid: String): List<String>

    @Query("UPDATE objects SET deleted_at = :now, updated_at = :now WHERE uuid IN (:uuids) AND deleted_at IS NULL")
    suspend fun tombstone(uuids: List<String>, now: String)

    /** A row born on this phone and never pushed: removed outright, not tombstoned. */
    @Query("DELETE FROM objects WHERE uuid IN (:uuids)") suspend fun hardDelete(uuids: List<String>)

    @Query("DELETE FROM objects WHERE server_id IS NOT NULL") suspend fun deleteServerRows()

    /** Every live `tags` column of objects and of entries under live objects, for suggestions. */
    @Query("SELECT tags FROM objects WHERE deleted_at IS NULL UNION ALL SELECT a.tags FROM activities a JOIN objects o ON o.uuid = a.object_uuid WHERE a.deleted_at IS NULL AND o.deleted_at IS NULL")
    fun tagColumns(): Flow<List<String>>
}
