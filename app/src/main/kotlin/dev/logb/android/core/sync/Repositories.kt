package dev.logb.android.core.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.logb.android.core.auth.ActiveAccount
import dev.logb.android.core.db.LogbDatabase
import dev.logb.android.feature.entries.ActivityRepository
import dev.logb.android.feature.objects.ObjectRepository
import dev.logb.android.feature.reminders.ReminderRepository
import javax.inject.Inject
import javax.inject.Singleton

/** The write side for whoever is signed in; every write ends in a debounced sync request. */
@Singleton
class Repositories @Inject constructor(@ApplicationContext private val context: Context, private val accounts: ActiveAccount, private val syncManager: SyncManager) {
    private var forDb: LogbDatabase? = null
    private var objects: ObjectRepository? = null
    private var activities: ActivityRepository? = null
    private var reminders: ReminderRepository? = null

    private fun refresh() = synchronized(this) {
        val db = accounts.db
        if (db === forDb) return
        val writer = LocalWriter(db)
        val onWrite = { syncManager.requestSync(SyncReason.AfterWrite); SyncWorker.runWhenConnected(context) }
        objects = ObjectRepository(db, writer, onWrite)
        activities = ActivityRepository(db, writer, onWrite)
        reminders = ReminderRepository(db, writer, onWrite)
        forDb = db
    }

    val objectRepository: ObjectRepository get() { refresh(); return objects!! }
    val activityRepository: ActivityRepository get() { refresh(); return activities!! }
    val reminderRepository: ReminderRepository get() { refresh(); return reminders!! }
}
