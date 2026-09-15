package dev.logb.android.core.db.dao

import androidx.room.Dao
import androidx.room.Query
import dev.logb.android.core.db.entity.ActivityEntity

/** An object hit with the name of the object it sits inside, so four things called "Filter" can be told apart. */
data class ObjectHit(
    val uuid: String,
    val name: String,
    val type: String,
    val description: String,
    val parentName: String?,
    val archivedAt: String? = null,
)

@Dao
interface SearchDao {
    @Query(
        """SELECT o.uuid AS uuid, o.name AS name, o.type AS type, o.description AS description, p.name AS parentName, o.archived_at AS archivedAt
           FROM objects o LEFT JOIN objects p ON p.uuid = o.parent_uuid
           WHERE o.deleted_at IS NULL AND (o.name LIKE :pattern ESCAPE '\' OR o.description LIKE :pattern ESCAPE '\' OR o.tags LIKE :pattern ESCAPE '\')
           ORDER BY o.name COLLATE NOCASE LIMIT 50""",
    )
    suspend fun objects(pattern: String): List<ObjectHit>

    @Query(
        """SELECT a.* FROM activities a JOIN objects o ON o.uuid = a.object_uuid
           WHERE a.deleted_at IS NULL AND o.deleted_at IS NULL
           AND (a.title LIKE :pattern ESCAPE '\' OR a.notes LIKE :pattern ESCAPE '\' OR a.tags LIKE :pattern ESCAPE '\')
           ORDER BY a.date DESC LIMIT 100""",
    )
    suspend fun activities(pattern: String): List<ActivityEntity>

    companion object {
        /** `api::search::like_pattern`: the query as a substring, with `%`, `_` and `\` taken literally. */
        fun likePattern(query: String): String {
            val escaped = query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            return "%$escaped%"
        }
    }
}
