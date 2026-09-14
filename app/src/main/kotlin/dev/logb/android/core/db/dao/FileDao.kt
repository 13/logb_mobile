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

    @Query("SELECT * FROM files WHERE sha256 = :sha AND deleted_at IS NULL LIMIT 1") suspend fun liveBySha(sha: String): FileEntity?

    @Query("DELETE FROM files WHERE uuid = :uuid") suspend fun hardDelete(uuid: String)

    /** Files still referenced by a live attachment, newest attachment first: the download order. */
    @Query("""SELECT DISTINCT f.* FROM files f JOIN attachments a ON a.file_uuid = f.uuid
              WHERE f.deleted_at IS NULL AND a.deleted_at IS NULL AND f.server_id IS NOT NULL ORDER BY a.created_at DESC""")
    suspend fun referencedFromServer(): List<FileEntity>

    @Query("SELECT * FROM files WHERE sha256 = :sha LIMIT 1") suspend fun bySha(sha: String): FileEntity?

    @Query("DELETE FROM files WHERE server_id IS NOT NULL") suspend fun deleteServerRows()
}
