package dev.logb.android.feature.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import dagger.hilt.android.EntryPointAccessors

/** The widget's check tap: the same write a notification's Done action makes, which refreshes every placed widget. */
class MarkDoneAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val uuid = parameters[REMINDER] ?: return
        val ep = EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)
        // ReminderActions refreshes every placed widget itself, before this callback returns.
        ep.reminderActions().done(uuid)
    }

    companion object {
        val REMINDER = ActionParameters.Key<String>("reminderUuid")
    }
}
