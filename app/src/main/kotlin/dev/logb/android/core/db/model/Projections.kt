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

/** `api::stats::read`'s grouping: spend per object, month and category; readings and costless entries left out. */
data class SpendRow(val objectUuid: String, val month: String, val category: String, val costCents: Long)

/** `api::insights::Bucket`: a year or a category with its spend and entry count. */
data class Bucket(val bucket: String, val costCents: Long, val count: Int)

data class MonthTotal(val month: String, val costCents: Long)

data class CounterSpan(val minCounter: Long?, val maxCounter: Long?, val totalCostCents: Long)

/** A fuel entry with an odometer reading and a quantity, as the consumption figures need it. */
data class FillRow(val date: String, val counterValue: Long, val quantityMilli: Long, val costCents: Long?)

data class ReadingRow(val date: String, val counterValue: Long)
