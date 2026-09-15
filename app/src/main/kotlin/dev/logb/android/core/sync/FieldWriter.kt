package dev.logb.android.core.sync

import dev.logb.android.core.db.LogbDatabase

/**
 * Writes one whitelisted field. One branch per field rather than SQL built from a string, so a
 * field the mirror does not have is a compile error here, not a runtime surprise. Objects and
 * activities also take the server's `updated_at` bump.
 */
object FieldWriter {
    suspend fun write(db: LogbDatabase, entity: String, uuid: String, field: String, value: Any?, now: String) {
        when (entity) {
            "object" -> {
                val o = db.objectDao().get(uuid) ?: return
                val updated = when (field) {
                    "name" -> o.copy(name = value as? String ?: "")
                    "type" -> o.copy(type = value as? String ?: "other")
                    "counter_unit" -> o.copy(counterUnit = value as? String)
                    "fuel_unit" -> o.copy(fuelUnit = value as? String)
                    "description" -> o.copy(description = value as? String ?: "")
                    "purchase_date" -> o.copy(purchaseDate = value as? String)
                    "purchase_price_cents" -> o.copy(purchasePriceCents = value as? Long)
                    "archived_at" -> o.copy(archivedAt = value as? String)
                    "cover_attachment_id" -> o.copy(coverAttachmentUuid = value as? String)
                    "parent_id" -> o.copy(parentUuid = value as? String)
                    "tags" -> o.copy(tags = value as? String ?: "[]")
                    else -> return
                }
                db.objectDao().upsert(updated.copy(updatedAt = now))
            }
            "activity" -> {
                val a = db.activityDao().get(uuid) ?: return
                val updated = when (field) {
                    "date" -> a.copy(date = value as? String ?: a.date)
                    "category" -> a.copy(category = value as? String ?: a.category)
                    "title" -> a.copy(title = value as? String ?: "")
                    "notes" -> a.copy(notes = value as? String ?: "")
                    "counter_value" -> a.copy(counterValue = value as? Long)
                    "cost_cents" -> a.copy(costCents = value as? Long)
                    "quantity_milli" -> a.copy(quantityMilli = value as? Long)
                    "tags" -> a.copy(tags = value as? String ?: "[]")
                    else -> return
                }
                db.activityDao().upsert(updated.copy(updatedAt = now))
            }
            "reminder" -> {
                val r = db.reminderDao().get(uuid) ?: return
                val updated = when (field) {
                    "title" -> r.copy(title = value as? String ?: "")
                    "notes" -> r.copy(notes = value as? String ?: "")
                    "due_date" -> r.copy(dueDate = value as? String)
                    "due_counter" -> r.copy(dueCounter = value as? Long)
                    "repeat_months" -> r.copy(repeatMonths = value as? Long)
                    "repeat_counter" -> r.copy(repeatCounter = value as? Long)
                    "done_at" -> r.copy(doneAt = value as? String)
                    "done_activity_id" -> r.copy(doneActivityUuid = value as? String)
                    "snoozed_until" -> r.copy(snoozedUntil = value as? String)
                    "every_n" -> r.copy(everyN = value as? Long)
                    "every_unit" -> r.copy(everyUnit = value as? String)
                    else -> return
                }
                db.reminderDao().upsert(updated)
            }
            "attachment" -> {
                val t = db.attachmentDao().get(uuid) ?: return
                val updated = when (field) {
                    "kind" -> t.copy(kind = value as? String ?: t.kind)
                    "caption" -> t.copy(caption = value as? String ?: "")
                    else -> return
                }
                db.attachmentDao().upsert(updated)
            }
            "object_type" -> {
                val t = db.objectTypeDao().get(uuid) ?: return
                val updated = when (field) {
                    "name" -> t.copy(name = value as? String ?: t.name)
                    "icon" -> t.copy(icon = value as? String ?: t.icon)
                    "categories" -> t.copy(categories = value as? String ?: t.categories)
                    "counter_unit" -> t.copy(counterUnit = value as? String)
                    else -> return
                }
                db.objectTypeDao().upsert(updated.copy(updatedAt = now))
            }
        }
    }
}
