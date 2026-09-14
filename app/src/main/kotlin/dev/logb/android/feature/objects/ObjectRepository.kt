package dev.logb.android.feature.objects

import dev.logb.android.core.db.LogbDatabase
import dev.logb.android.core.db.entity.ObjectEntity
import dev.logb.android.core.domain.ActivityDraft
import dev.logb.android.core.domain.ObjectDraft
import dev.logb.android.core.domain.ReminderDraft
import dev.logb.android.core.sync.Clock
import dev.logb.android.core.sync.LocalWriter
import dev.logb.android.feature.entries.ActivityRepository
import dev.logb.android.feature.reminders.ReminderRepository
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.util.UUID

/** Writes to objects, through the op queue. `onWrite` asks for a sync afterwards. */
class ObjectRepository(private val db: LogbDatabase, private val writer: LocalWriter, private val onWrite: () -> Unit = {}) {
    /**
     * Creates the object and, for a new one, the reminders its ticked templates become; a known
     * current reading is also logged as the first reading, so a distance-based reminder has
     * something to count from.
     */
    suspend fun create(d: ObjectDraft, templates: List<ReminderDraft> = emptyList(), currentReading: Long? = null, today: LocalDate = LocalDate.now()): String {
        val uuid = UUID.randomUUID().toString()
        val now = Clock.nowIso()
        writer.create("object", uuid) {
            db.objectDao().upsert(
                ObjectEntity(uuid, null, d.name.trim(), d.type, d.counterUnit, d.fuelUnit, d.description.trim(), d.purchaseDate, d.purchasePriceCents, null, null, d.parentUuid, now, now, null),
            )
        }
        if (currentReading != null && d.counterUnit != null) {
            ActivityRepository(db, writer).create(uuid, ActivityDraft(date = today.toString(), category = "reading", title = "Reading", counterValue = currentReading))
        }
        val reminders = ReminderRepository(db, writer)
        templates.forEach { reminders.create(uuid, it) }
        onWrite()
        return uuid
    }

    suspend fun update(uuid: String, d: ObjectDraft) {
        val o = db.objectDao().get(uuid) ?: return
        val changes = buildMap<String, Any?> {
            if (d.name.trim() != o.name) put("name", d.name.trim())
            if (d.type != o.type) put("type", d.type)
            if (d.counterUnit != o.counterUnit) put("counter_unit", d.counterUnit)
            if (d.fuelUnit != o.fuelUnit) put("fuel_unit", d.fuelUnit)
            if (d.description.trim() != o.description) put("description", d.description.trim())
            if (d.purchaseDate != o.purchaseDate) put("purchase_date", d.purchaseDate)
            if (d.purchasePriceCents != o.purchasePriceCents) put("purchase_price_cents", d.purchasePriceCents)
            if (d.parentUuid != o.parentUuid) put("parent_id", d.parentUuid)
        }
        if (changes.isEmpty()) return
        writer.set("object", uuid, changes) {
            db.objectDao().upsert(
                o.copy(name = d.name.trim(), type = d.type, counterUnit = d.counterUnit, fuelUnit = d.fuelUnit, description = d.description.trim(), purchaseDate = d.purchaseDate, purchasePriceCents = d.purchasePriceCents, parentUuid = d.parentUuid, updatedAt = Clock.nowIso()),
            )
        }
        onWrite()
    }

    suspend fun setArchived(uuid: String, archived: Boolean) {
        val o = db.objectDao().get(uuid) ?: return
        val at = if (archived) Clock.nowIso() else null
        writer.set("object", uuid, mapOf("archived_at" to at)) { db.objectDao().upsert(o.copy(archivedAt = at, updatedAt = Clock.nowIso())) }
        onWrite()
    }

    suspend fun delete(uuid: String) {
        writer.delete("object", uuid)
        onWrite()
    }

    /** Every live object that may become `uuid`'s parent: not itself, not one of its descendants. */
    suspend fun candidatesForParent(uuid: String?): List<ObjectEntity> {
        val all = db.objectDao().all().first()
        if (uuid == null) return all
        val excluded = setOf(uuid) + db.objectDao().descendantUuids(uuid)
        return all.filter { it.uuid !in excluded }
    }
}
