package dev.logb.android.core.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.logb.android.core.db.entity.FileEntity

@Dao
interface FileDao {
    @Upsert suspend fun upsert(vararg rows: FileEntity)

    @Query("SELECT * FROM files WHERE uuid = :uuid") suspend fun get(uuid: String): FileEntity?

    @Query("SELECT uuid FROM files WHERE server_id = :id") suspend fun uuidForServerId(id: Long): String?

    @Query("SELECT * FROM files WHERE server_id IS NOT NULL AND deleted_at IS NULL") suspend fun allFromServer(): List<FileEntity>

    @Query("DELETE FROM files WHERE server_id IS NOT NULL") suspend fun deleteServerRows()
}
