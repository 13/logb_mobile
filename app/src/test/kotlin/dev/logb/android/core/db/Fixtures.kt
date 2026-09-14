package dev.logb.android.core.db

import dev.logb.android.core.db.entity.ActivityEntity
import dev.logb.android.core.db.entity.ObjectEntity
import dev.logb.android.core.db.entity.ReminderEntity

const val T0 = "2026-01-01T00:00:00.000Z"

fun obj(uuid: String, name: String, parent: String? = null, archived: String? = null, type: String = "car", serverId: Long? = null) =
    ObjectEntity(uuid, serverId, name, type, "km", null, "", null, null, archived, null, parent, T0, T0, null)

fun act(uuid: String, obj: String, date: String, cost: Long? = null, counter: Long? = null, title: String = "x", category: String = "maintenance") =
    ActivityEntity(uuid, null, obj, date, category, title, "", counter, cost, null, T0, T0, null)

fun rem(uuid: String, obj: String, title: String = "Oil", dueDate: String? = null, dueCounter: Long? = null, doneAt: String? = null) =
    ReminderEntity(uuid, null, obj, title, "", dueDate, dueCounter, null, null, null, doneAt, null, "service", null, null, T0, null)
