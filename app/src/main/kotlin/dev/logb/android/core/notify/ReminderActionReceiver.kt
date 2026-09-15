package dev.logb.android.core.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReminderActionsEntryPoint { fun actions(): ReminderActions }

/** Done / Snooze tapped on a notification: written offline through the op queue, no activity, no lock. */
class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val uuid = intent.getStringExtra(EXTRA_REMINDER) ?: return
        val actions = EntryPointAccessors.fromApplication(context.applicationContext, ReminderActionsEntryPoint::class.java).actions()
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                dispatch(actions, intent.action, uuid)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_DONE = "dev.logb.android.REMINDER_DONE"
        const val ACTION_SNOOZE = "dev.logb.android.REMINDER_SNOOZE"
        const val EXTRA_REMINDER = "reminder"

        /** The actual dispatch, pulled out of `onReceive` so it is testable without a Hilt entry point. */
        suspend fun dispatch(actions: ReminderActions, action: String?, reminderUuid: String) {
            when (action) {
                ACTION_DONE -> actions.done(reminderUuid)
                ACTION_SNOOZE -> actions.snooze(reminderUuid)
            }
        }
    }
}
