package dev.logb.android.feature.entries

import dev.logb.android.core.db.LogbDatabase
import dev.logb.android.core.db.entity.ActivityEntity
import dev.logb.android.core.domain.ActivityDraft
import dev.logb.android.core.sync.Clock
import dev.logb.android.core.sync.LocalWriter
import java.util.UUID

class ActivityRepository(private val db: LogbDatabase, private val writer: LocalWriter, private val onWrite: () -> Unit = {}) {
    suspend fun create(objectUuid: String, d: ActivityDraft): String {
        val uuid = UUID.randomUUID().toString()
        val now = Clock.nowIso()
        writer.create("activity", uuid) {
            db.activityDao().upsert(ActivityEntity(uuid, null, objectUuid, d.date, d.category, d.title.trim(), d.notes.trim(), d.counterValue, d.costCents, d.quantityMilli, now, now, null))
        }
        onWrite()
        return uuid
    }

    suspend fun update(uuid: String, d: ActivityDraft) {
        val a = db.activityDao().get(uuid) ?: return
        val changes = buildMap<String, Any?> {
            if (d.date != a.date) put("date", d.date)
            if (d.category != a.category) put("category", d.category)
            if (d.title.trim() != a.title) put("title", d.title.trim())
            if (d.notes.trim() != a.notes) put("notes", d.notes.trim())
            if (d.counterValue != a.counterValue) put("counter_value", d.counterValue)
            if (d.costCents != a.costCents) put("cost_cents", d.costCents)
            if (d.quantityMilli != a.quantityMilli) put("quantity_milli", d.quantityMilli)
        }
        if (changes.isEmpty()) return
        writer.set("activity", uuid, changes) {
            db.activityDao().upsert(a.copy(date = d.date, category = d.category, title = d.title.trim(), notes = d.notes.trim(), counterValue = d.counterValue, costCents = d.costCents, quantityMilli = d.quantityMilli, updatedAt = Clock.nowIso()))
        }
        onWrite()
    }

    suspend fun delete(uuid: String) {
        writer.delete("activity", uuid)
        onWrite()
    }
}
