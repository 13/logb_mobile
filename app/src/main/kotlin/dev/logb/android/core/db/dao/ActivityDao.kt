package dev.logb.android.core.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.logb.android.core.db.entity.ActivityEntity
import dev.logb.android.core.db.model.Bucket
import dev.logb.android.core.db.model.CounterSpan
import dev.logb.android.core.db.model.FillRow
import dev.logb.android.core.db.model.MonthTotal
import dev.logb.android.core.db.model.ReadingRow
import dev.logb.android.core.db.model.SpendRow
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

    @Query("DELETE FROM activities WHERE uuid IN (:uuids)") suspend fun hardDelete(uuids: List<String>)

    @Query("SELECT uuid FROM activities WHERE object_uuid IN (:objectUuids)") suspend fun allUuidsForObjects(objectUuids: List<String>): List<String>

    @Query("DELETE FROM activities WHERE server_id IS NOT NULL") suspend fun deleteServerRows()

    // --- Statistics (`api::stats::read`) ---

    /** Readings are excluded as in insights: they never carry a cost. Deleted objects take their entries with them. */
    @Query(
        """SELECT a.object_uuid AS objectUuid, substr(a.date, 1, 7) AS month, a.category AS category, SUM(a.cost_cents) AS costCents
           FROM activities a JOIN objects o ON o.uuid = a.object_uuid
           WHERE a.deleted_at IS NULL AND o.deleted_at IS NULL AND a.cost_cents IS NOT NULL AND a.category <> 'reading'
           GROUP BY a.object_uuid, substr(a.date, 1, 7), a.category""",
    )
    suspend fun spendByObjectMonthCategory(): List<SpendRow>

    /** Objects with a costed `purchase` entry: that entry is the purchase, so the price field is not counted again. */
    @Query(
        """SELECT DISTINCT a.object_uuid FROM activities a JOIN objects o ON o.uuid = a.object_uuid
           WHERE a.deleted_at IS NULL AND o.deleted_at IS NULL AND a.category = 'purchase' AND a.cost_cents > 0""",
    )
    suspend fun purchasedObjectUuids(): List<String>

    /** A change counter for the statistics screen: any live entry written or removed moves it. */
    @Query("SELECT COUNT(*) || ':' || COALESCE(MAX(updated_at), '') FROM activities")
    fun version(): Flow<String>

    // --- Insights (`api::insights::read`); `uuids` is the object alone or with its contents ---

    @Query(
        """SELECT substr(date, 1, 4) AS bucket, COALESCE(SUM(cost_cents), 0) AS costCents, COUNT(*) AS count
           FROM activities WHERE object_uuid IN (:uuids) AND deleted_at IS NULL GROUP BY bucket ORDER BY bucket DESC""",
    )
    suspend fun byYear(uuids: List<String>): List<Bucket>

    /** Readings are left out: a monthly reading habit would put an empty "Reading" bar at the bottom of every car's breakdown. */
    @Query(
        """SELECT category AS bucket, COALESCE(SUM(cost_cents), 0) AS costCents, COUNT(*) AS count
           FROM activities WHERE object_uuid IN (:uuids) AND deleted_at IS NULL AND category <> 'reading'
           GROUP BY category ORDER BY costCents DESC""",
    )
    suspend fun byCategory(uuids: List<String>): List<Bucket>

    @Query(
        """SELECT substr(date, 1, 7) AS month, SUM(cost_cents) AS costCents FROM activities
           WHERE object_uuid IN (:uuids) AND deleted_at IS NULL AND cost_cents IS NOT NULL GROUP BY substr(date, 1, 7)""",
    )
    suspend fun monthTotals(uuids: List<String>): List<MonthTotal>

    @Query("SELECT COALESCE(SUM(cost_cents), 0) FROM activities WHERE object_uuid IN (:uuids) AND deleted_at IS NULL")
    suspend fun runningCents(uuids: List<String>): Long

    /** The object's own entries only, as the server's: a house's counter is not its boiler's. */
    @Query(
        """SELECT MIN(counter_value) AS minCounter, MAX(counter_value) AS maxCounter, COALESCE(SUM(cost_cents), 0) AS totalCostCents
           FROM activities WHERE object_uuid = :uuid AND deleted_at IS NULL""",
    )
    suspend fun counterSpan(uuid: String): CounterSpan

    @Query(
        """SELECT date, counter_value AS counterValue, quantity_milli AS quantityMilli, cost_cents AS costCents FROM activities
           WHERE object_uuid = :uuid AND deleted_at IS NULL AND category = 'fuel' AND counter_value IS NOT NULL AND quantity_milli IS NOT NULL
           ORDER BY counter_value""",
    )
    suspend fun fills(uuid: String): List<FillRow>

    /** Every reading up to `upTo` (the server's `reading_horizon`, tomorrow): a typo'd year must not become the latest reading. */
    @Query(
        """SELECT date, counter_value AS counterValue FROM activities
           WHERE object_uuid = :uuid AND deleted_at IS NULL AND counter_value IS NOT NULL AND date <= :upTo ORDER BY date, counter_value""",
    )
    suspend fun readingRows(uuid: String, upTo: String): List<ReadingRow>
}
