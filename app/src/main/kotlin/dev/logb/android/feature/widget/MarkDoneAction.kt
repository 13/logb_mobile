package dev.logb.android.feature.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.updateAll
import dagger.hilt.android.EntryPointAccessors

/** The widget's check tap: the same write a notification's Done action makes, then every placed widget catches up. */
class MarkDoneAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val uuid = parameters[REMINDER] ?: return
        val ep = EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)
        ep.reminderActions().done(uuid)
        DueWidget().updateAll(context)
    }

    companion object {
        val REMINDER = ActionParameters.Key<String>("reminderUuid")
    }
}
