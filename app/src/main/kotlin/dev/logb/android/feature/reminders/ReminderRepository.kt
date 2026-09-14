package dev.logb.android.feature.reminders

import dev.logb.android.core.db.LogbDatabase
import dev.logb.android.core.db.entity.ReminderEntity
import dev.logb.android.core.domain.ReminderDraft
import dev.logb.android.core.domain.ReminderRules
import dev.logb.android.core.domain.ReminderRules.parseDate
import dev.logb.android.core.sync.Clock
import dev.logb.android.core.sync.LocalWriter
import java.time.LocalDate
import java.util.UUID

class ReminderRepository(private val db: LogbDatabase, private val writer: LocalWriter, private val onWrite: () -> Unit = {}) {
    suspend fun create(objectUuid: String, d: ReminderDraft, today: LocalDate = LocalDate.now()): String {
        val uuid = UUID.randomUUID().toString()
        // A reading reminder without a start starts today, as the server's validate() fills in.
        val dueDate = if (d.kind == ReminderRules.KIND_READING && d.dueDate == null) today.toString() else d.dueDate
        writer.create("reminder", uuid) {
            db.reminderDao().upsert(
                ReminderEntity(uuid, null, objectUuid, d.title.trim(), d.notes.trim(), dueDate, d.dueCounter, d.repeatMonths, d.repeatCounter, null, null, null, d.kind, d.everyN, d.everyUnit, Clock.nowIso(), null),
            )
        }
        onWrite()
        return uuid
    }

    suspend fun update(uuid: String, d: ReminderDraft) {
        val r = db.reminderDao().get(uuid) ?: return
        val changes = buildMap<String, Any?> {
            if (d.title.trim() != r.title) put("title", d.title.trim())
            if (d.notes.trim() != r.notes) put("notes", d.notes.trim())
            if (d.dueDate != r.dueDate) put("due_date", d.dueDate)
            if (d.dueCounter != r.dueCounter) put("due_counter", d.dueCounter)
            if (d.repeatMonths != r.repeatMonths) put("repeat_months", d.repeatMonths)
            if (d.repeatCounter != r.repeatCounter) put("repeat_counter", d.repeatCounter)
            if (d.everyN != r.everyN) put("every_n", d.everyN)
            if (d.everyUnit != r.everyUnit) put("every_unit", d.everyUnit)
        }
        if (changes.isEmpty()) return
        writer.set("reminder", uuid, changes) {
            db.reminderDao().upsert(r.copy(title = d.title.trim(), notes = d.notes.trim(), dueDate = d.dueDate, dueCounter = d.dueCounter, repeatMonths = d.repeatMonths, repeatCounter = d.repeatCounter, everyN = d.everyN, everyUnit = d.everyUnit))
        }
        onWrite()
    }

    suspend fun delete(uuid: String) {
        writer.delete("reminder", uuid)
        onWrite()
    }

    /**
     * What `api::reminders::done` does, done locally: mark done (linking the entry if one is
     * named) and, when the reminder repeats, create its successor from the completion point --
     * today and the object's current counter. Returns the successor's uuid.
     */
    suspend fun done(uuid: String, activityUuid: String?, today: LocalDate = LocalDate.now()): String? {
        val r = db.reminderDao().get(uuid) ?: return null
        if (r.kind == ReminderRules.KIND_READING || r.doneAt != null) return null
        val doneAt = Clock.nowIso()
        val changes = buildMap<String, Any?> { put("done_at", doneAt); if (activityUuid != null) put("done_activity_id", activityUuid) }
        writer.set("reminder", uuid, changes) { db.reminderDao().upsert(r.copy(doneAt = doneAt, doneActivityUuid = activityUuid)) }
        val current = db.objectDao().stats(r.objectUuid).currentCounter
        val next = ReminderRules.nextDue(today, current, r.dueCounter, ReminderRules.Repeat(r.repeatMonths, r.repeatCounter))
        val successor = next?.let { (date, counter) ->
            create(r.objectUuid, ReminderDraft(r.title, r.notes, date?.toString(), counter, r.repeatMonths, r.repeatCounter), today)
        }
        onWrite()
        return successor
    }

    suspend fun snooze(uuid: String, days: Long, today: LocalDate = LocalDate.now()) {
        val r = db.reminderDao().get(uuid) ?: return
        val until = ReminderRules.snoozedDate(today, parseDate(r.dueDate), days).toString()
        writer.set("reminder", uuid, mapOf("snoozed_until" to until)) { db.reminderDao().upsert(r.copy(snoozedUntil = until)) }
        onWrite()
    }

    suspend fun unsnooze(uuid: String) {
        val r = db.reminderDao().get(uuid) ?: return
        writer.set("reminder", uuid, mapOf("snoozed_until" to null)) { db.reminderDao().upsert(r.copy(snoozedUntil = null)) }
        onWrite()
    }
}
