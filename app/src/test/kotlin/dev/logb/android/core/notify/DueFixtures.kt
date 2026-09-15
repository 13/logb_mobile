package dev.logb.android.core.notify

import dev.logb.android.core.db.entity.ReminderEntity
import dev.logb.android.core.domain.ReminderRules
import dev.logb.android.core.domain.ReminderView
import dev.logb.android.feature.reminders.DueItem

/**
 * `DueItem`s built directly with the given `due`/`soonestDays`, without going through
 * `ReminderPresenter`: deterministic, and the plan under test only reads those two fields
 * plus the reminder's kind and title.
 */
object DueFixtures {
    private const val T0 = "2026-01-01T00:00:00.000Z"

    private fun item(uuid: String, objectName: String, title: String, kind: String, due: Boolean, soonestDays: Long?): DueItem {
        val reminder = ReminderEntity(
            uuid = uuid, serverId = null, objectUuid = "o-$uuid", title = title, notes = "",
            dueDate = null, dueCounter = null, repeatMonths = null, repeatCounter = null,
            snoozedUntil = null, doneAt = null, doneActivityUuid = null, kind = kind,
            everyN = null, everyUnit = null, createdAt = T0, deletedAt = null,
        )
        val view = ReminderView(reminder, due = due, daysUntil = soonestDays, counterUntil = null, nextDueDate = null, soonestDays = soonestDays)
        return DueItem(view, objectUuid = "o-$uuid", objectName = objectName, objectType = "car", counterUnit = "km")
    }

    fun service(uuid: String, objectName: String, title: String, due: Boolean, soonestDays: Long? = null) =
        item(uuid, objectName, title, ReminderRules.KIND_SERVICE, due, soonestDays)

    fun reading(uuid: String, objectName: String, title: String, due: Boolean, soonestDays: Long? = null) =
        item(uuid, objectName, title, ReminderRules.KIND_READING, due, soonestDays)
}
