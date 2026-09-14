package dev.logb.android.core.sync

import dev.logb.android.core.db.LogbDatabase
import dev.logb.android.core.db.entity.OpEntity
import dev.logb.android.core.network.ApiException
import dev.logb.android.core.network.LogbApi
import dev.logb.android.core.network.LogbJson
import dev.logb.android.core.network.UnauthorizedException
import dev.logb.android.core.network.dto.ActivityInput
import dev.logb.android.core.network.dto.ObjectInput
import dev.logb.android.core.network.dto.Op
import dev.logb.android.core.network.dto.PushBody
import dev.logb.android.core.network.dto.ReminderInput
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Drains the op queue in order. A `create` is the REST create carrying the row's current
 * values and its `client_uuid` (the server inserts; the feed announces). A run of `set`/`delete`
 * ops is one `/sync/push` batch. A reference the server does not know yet stops the run at that
 * op; FIFO order means the target's own create is earlier in the queue and usually already
 * done, and a stop is retried on the next run.
 */
class PushEngine(private val db: LogbDatabase, private val api: LogbApi) {
    /** Thrown internally when an op refers to a row the server has not confirmed yet. */
    private class NotYet : Exception()

    suspend fun run() {
        // A pass works through a snapshot of the queue; a create can append follow-up sets (see
        // `pushCreate`), so another pass runs while a pass made progress and left work behind.
        var passes = 0
        while (passes++ < MAX_PASSES) {
            val ops = db.opDao().pending()
            if (ops.isEmpty()) return
            var i = 0
            var progressed = false
            while (i < ops.size) {
                val op = ops[i]
                if (op.kind == "create") {
                    if (op.entity == "attachment") { i++; continue } // phase 3
                    if (!pushCreate(op)) return
                    progressed = true
                    i++
                } else {
                    val batch = mutableListOf<OpEntity>()
                    while (i < ops.size && ops[i].kind != "create") { batch += ops[i]; i++ }
                    if (!pushBatch(batch)) return
                    progressed = true
                }
            }
            if (!progressed) return
        }
    }

    private companion object {
        /** Follow-up sets appended by a create are pushed by the next pass; a few passes cover any chain of them. */
        const val MAX_PASSES = 4
    }

    private suspend fun pushCreate(op: OpEntity): Boolean {
        try {
            when (op.entity) {
                "object" -> {
                    val o = db.objectDao().get(op.entityUuid) ?: return dropped(op)
                    val parentId = o.parentUuid?.let { db.objectDao().serverIdFor(it) ?: throw NotYet() }
                    val dto = api.createObject(
                        ObjectInput(o.name, o.type, o.counterUnit, o.fuelUnit, o.description, o.purchaseDate, o.purchasePriceCents, archived = o.archivedAt != null, parentId = parentId, clientUuid = o.uuid),
                    )
                    db.objectDao().upsert(o.copy(serverId = dto.id))
                }
                "activity" -> {
                    val a = db.activityDao().get(op.entityUuid) ?: return dropped(op)
                    val objectId = db.objectDao().serverIdFor(a.objectUuid) ?: throw NotYet()
                    val dto = api.createActivity(objectId, ActivityInput(a.date, a.category, a.title, a.notes, a.counterValue, a.costCents, a.quantityMilli, clientOpId = op.id, clientUuid = a.uuid))
                    db.activityDao().upsert(a.copy(serverId = dto.id))
                }
                "reminder" -> {
                    val r = db.reminderDao().get(op.entityUuid) ?: return dropped(op)
                    val objectId = db.objectDao().serverIdFor(r.objectUuid) ?: throw NotYet()
                    val dto = api.createReminder(objectId, ReminderInput(r.title, r.notes, r.dueDate, r.dueCounter, r.repeatMonths, r.repeatCounter, r.kind, r.everyN, r.everyUnit, clientUuid = r.uuid))
                    db.reminderDao().upsert(r.copy(serverId = dto.id))
                    // The create input has no done or snooze fields; what happened to the row
                    // before its push travels as sets, now that the server knows the uuid.
                    val followUps = buildMap<String, Any?> {
                        r.doneAt?.let { put("done_at", it) }
                        r.doneActivityUuid?.let { put("done_activity_id", it) }
                        r.snoozedUntil?.let { put("snoozed_until", it) }
                    }
                    if (followUps.isNotEmpty()) {
                        db.opDao().delete(op.id)
                        LocalWriter(db).set("reminder", r.uuid, followUps) { }
                        return true
                    }
                }
                else -> return dropped(op)
            }
            db.opDao().delete(op.id)
            return true
        } catch (e: NotYet) {
            return false
        } catch (e: UnauthorizedException) {
            throw e
        } catch (e: ApiException) {
            // 4xx: the server will never take this row as it is. Leave it for the person to
            // retry or discard from Settings > Sync; 5xx: the server's day, try again later.
            if (e.status in 400..499) { db.opDao().markDead(op.id, e.message); return true }
            return false
        }
    }

    private suspend fun dropped(op: OpEntity): Boolean { db.opDao().delete(op.id); return true }

    private suspend fun pushBatch(batch: List<OpEntity>): Boolean {
        if (batch.isEmpty()) return true
        val deviceId = db.syncStateDao().get()?.deviceId ?: return false
        val wire = mutableListOf<Op>()
        for (op in batch) {
            val value = if (op.kind == "set") translate(op) ?: return false else null
            wire += Op(op.id, op.entity, op.entityUuid, op.kind, op.field, value, op.editedAt, deviceId)
        }
        val result = try { api.push(PushBody(wire)) } catch (e: ApiException) { if (e.status in 400..499) { batch.forEach { db.opDao().markDead(it.id, e.message) }; return true } else return false }
        for (r in result.results) {
            when (r.outcome) {
                "accepted", "superseded" -> db.opDao().delete(r.clientOpId)
                else -> db.opDao().markDead(r.clientOpId, r.reason ?: r.outcome)
            }
        }
        return true
    }

    /** The op's JSON value on the wire; a reference field's uuid becomes the server's integer, or null (stop) if unknown. */
    private suspend fun translate(op: OpEntity): kotlinx.serialization.json.JsonElement? {
        val element = op.valueJson?.let { LogbJson.parseToJsonElement(it) } ?: kotlinx.serialization.json.JsonNull
        val spec = FieldSpecs.of(op.entity, op.field ?: return kotlinx.serialization.json.JsonNull)
        if (spec !is FieldType.Ref || element is kotlinx.serialization.json.JsonNull) return element
        val targetUuid = (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return element.takeIf { (element as? JsonPrimitive)?.longOrNull != null }
        val id = when (spec.table) {
            "objects" -> db.objectDao().serverIdFor(targetUuid)
            "activities" -> db.activityDao().serverIdFor(targetUuid)
            "attachments" -> db.attachmentDao().get(targetUuid)?.serverId
            else -> null
        } ?: return null
        return JsonPrimitive(id)
    }
}
