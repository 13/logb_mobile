package dev.logb.android.feature.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.logb.android.MainActivity
import dev.logb.android.R
import dev.logb.android.core.auth.ActiveAccount
import dev.logb.android.core.auth.LockPrefs
import dev.logb.android.core.auth.Session
import dev.logb.android.core.auth.SessionRepository
import dev.logb.android.core.notify.ReminderActions
import dev.logb.android.feature.reminders.DueListModel
import dev.logb.android.feature.share.LaunchTarget
import kotlinx.coroutines.flow.first

/** What the widget needs from the graph: nothing it doesn't already share with the rest of the app. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun accounts(): ActiveAccount
    fun sessions(): SessionRepository
    fun lockPrefs(): LockPrefs
    fun reminderActions(): ReminderActions
}

class DueWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(SMALL, MEDIUM))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val ep = EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)
        val sessions = ep.sessions()
        // A process started just for this update may not have restored its session yet, as
        // ReminderActions and DigestWorker both account for.
        if (sessions.session.value is Session.Loading) sessions.restore()
        val signedIn = sessions.session.value is Session.SignedIn
        val locked = DueWidgetStates.resolveLocked(runCatching { ep.lockPrefs().current() }.getOrNull())
        // The count still reaches a locked widget (like the digest's group summary); only the
        // per-row detail -- object names, titles -- is privacy-sensitive, and DueWidgetStates
        // strips exactly that.
        val items = if (signedIn) DueListModel(ep.accounts().db).items(WITHIN_DAYS).first() else emptyList()
        val res = context.resources
        val state = DueWidgetStates.from(
            items,
            locked = locked,
            signedIn = signedIn,
            dueWord = res.getString(R.string.notify_due),
            upcomingWord = { days -> res.getQuantityString(R.plurals.notify_in_days, days.toInt(), days) },
        )
        provideContent { DueWidgetContent(state) }
    }

    companion object {
        val SMALL = DpSize(110.dp, 50.dp)
        val MEDIUM = DpSize(250.dp, 180.dp)
        private const val WITHIN_DAYS = 7L
    }
}

@Composable
fun DueWidgetContent(state: DueWidgetState) {
    GlanceTheme {
        Box(
            GlanceModifier.fillMaxSize().background(GlanceTheme.colors.background).cornerRadius(16.dp).padding(12.dp),
        ) {
            if (!state.signedIn) {
                Text(
                    LocalContext.current.getString(R.string.widget_signed_out),
                    style = TextStyle(color = GlanceTheme.colors.onBackground, fontSize = 13.sp),
                )
            } else {
                val isMedium = LocalSize.current.width >= DueWidget.MEDIUM.width
                Column(GlanceModifier.fillMaxSize()) {
                    Text(
                        LocalContext.current.resources.getQuantityString(R.plurals.widget_due_count, state.count, state.count),
                        style = TextStyle(color = GlanceTheme.colors.onBackground, fontWeight = FontWeight.Medium, fontSize = 15.sp),
                    )
                    when {
                        state.locked -> Unit
                        state.rows.isEmpty() -> {
                            Spacer(GlanceModifier.height(4.dp))
                            Text(
                                LocalContext.current.getString(R.string.widget_nothing_due),
                                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
                            )
                        }
                        !isMedium -> {
                            Spacer(GlanceModifier.height(4.dp))
                            Text(
                                state.rows.first().title,
                                maxLines = 1,
                                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
                            )
                        }
                        else -> state.rows.forEach { row ->
                            Spacer(GlanceModifier.height(6.dp))
                            WidgetRowContent(row)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetRowContent(row: WidgetRow) {
    val context = LocalContext.current
    // The data (not just the extras) is unique per row so the platform can never fold two rows'
    // PendingIntents into one and merge their extras -- the same failure the notification taps
    // avoid with a distinct request code.
    val intent = Intent(context, MainActivity::class.java)
        .setAction(LaunchTarget.ACTION)
        .putExtra(LaunchTarget.EXTRA, LaunchTarget.Reminders.name)
        .putExtra(LaunchTarget.EXTRA_OBJECT, row.objectUuid)
        .setData("logb://widget/reminder/${row.reminderUuid}".toUri())
    Row(GlanceModifier.fillMaxWidth().clickable(actionStartActivity(intent)), verticalAlignment = Alignment.CenterVertically) {
        Column(GlanceModifier.defaultWeight()) {
            Text(
                "${row.objectName}: ${row.title}",
                maxLines = 1,
                style = TextStyle(color = GlanceTheme.colors.onBackground, fontSize = 13.sp),
            )
            Text(row.whenText, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp))
        }
        if (row.canMarkDone) {
            Spacer(GlanceModifier.width(8.dp))
            Image(
                ImageProvider(R.drawable.ic_widget_check),
                contentDescription = context.getString(R.string.widget_mark_done),
                modifier = GlanceModifier.size(24.dp)
                    .clickable(actionRunCallback<MarkDoneAction>(actionParametersOf(MarkDoneAction.REMINDER to row.reminderUuid))),
            )
        }
    }
}
