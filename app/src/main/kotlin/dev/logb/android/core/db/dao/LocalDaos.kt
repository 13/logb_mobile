package dev.logb.android.core.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import dev.logb.android.core.db.entity.BlobEntity
import dev.logb.android.core.db.entity.FieldClockEntity
import dev.logb.android.core.db.entity.OpEntity
import dev.logb.android.core.db.entity.SyncStateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface OpDao {
    @Insert suspend fun insert(op: OpEntity): Long

    @Query("SELECT * FROM ops WHERE dead = 0 ORDER BY seq") suspend fun pending(): List<OpEntity>

    @Query("SELECT COUNT(*) FROM ops WHERE dead = 0") fun pendingCount(): Flow<Int>

    @Query("SELECT * FROM ops WHERE dead = 1 ORDER BY seq") fun dead(): Flow<List<OpEntity>>

    @Query("SELECT * FROM ops WHERE kind = 'create' AND entity_uuid = :uuid AND dead = 0 LIMIT 1") suspend fun pendingCreate(uuid: String): OpEntity?

    @Query("DELETE FROM ops WHERE id = :id") suspend fun delete(id: String)

    @Query("DELETE FROM ops WHERE entity_uuid IN (:uuids)") suspend fun deleteForEntities(uuids: List<String>)

    @Query("UPDATE ops SET dead = 1, last_error = :reason WHERE id = :id") suspend fun markDead(id: String, reason: String)

    @Query("UPDATE ops SET dead = 0, attempts = 0, last_error = NULL WHERE id = :id") suspend fun revive(id: String)

    @Query("UPDATE ops SET attempts = attempts + 1 WHERE id = :id") suspend fun bumpAttempts(id: String)
}

@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state WHERE id = 1") suspend fun get(): SyncStateEntity?

    @Query("SELECT * FROM sync_state WHERE id = 1") fun observe(): Flow<SyncStateEntity?>

    @Upsert suspend fun upsert(state: SyncStateEntity)

    @Query("UPDATE sync_state SET bootstrap_needed = 1 WHERE id = 1") suspend fun requestBootstrap()
}

@Dao
interface FieldClockDao {
    @Query("SELECT * FROM field_clock WHERE entity = :entity AND entity_uuid = :uuid AND field = :field")
    suspend fun get(entity: String, uuid: String, field: String): FieldClockEntity?

    @Upsert suspend fun upsert(clock: FieldClockEntity)

    @Query("DELETE FROM field_clock") suspend fun clear()
}

@Dao
interface BlobDao {
    @Upsert suspend fun upsert(blob: BlobEntity)

    @Query("SELECT * FROM blobs WHERE sha256 = :sha256") suspend fun get(sha256: String): BlobEntity?

    @Query("SELECT sha256 FROM blobs WHERE original_present = 1 ORDER BY last_access_at ASC") suspend fun originalsLeastRecentlyUsed(): List<String>

    @Query("UPDATE blobs SET last_access_at = :at WHERE sha256 = :sha256") suspend fun touch(sha256: String, at: String)

    @Query("UPDATE blobs SET original_present = :present WHERE sha256 = :sha256") suspend fun setOriginalPresent(sha256: String, present: Boolean)
}
