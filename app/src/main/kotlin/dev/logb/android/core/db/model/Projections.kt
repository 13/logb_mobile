package dev.logb.android.core.db.model

import androidx.room.Embedded
import androidx.room.Relation
import dev.logb.android.core.db.entity.AttachmentEntity
import dev.logb.android.core.db.entity.FileEntity

/** Ports `api::objects::stats` minus `due_reminder_count`, which needs the reminder rules and is computed in Kotlin. */
data class ObjectStats(
    val totalCostCents: Long,
    val activityCount: Int,
    val currentCounter: Long?,
    val lastActivityDate: String?,
    val lastReadingDate: String?,
)

/** One link of a breadcrumb. */
data class Ancestor(val uuid: String, val name: String)

/** An attachment with the file row it references, as every screen wants it. */
data class AttachmentWithFile(
    @Embedded val attachment: AttachmentEntity,
    @Relation(parentColumn = "file_uuid", entityColumn = "uuid") val file: FileEntity,
)
