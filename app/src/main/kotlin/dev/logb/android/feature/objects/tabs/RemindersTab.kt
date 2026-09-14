package dev.logb.android.feature.objects.tabs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.logb.android.R
import dev.logb.android.core.design.components.DueBadge
import dev.logb.android.core.design.components.EmptyState
import dev.logb.android.core.domain.ReminderView
import dev.logb.android.core.format.currentLocale
import dev.logb.android.core.format.formatCounter
import dev.logb.android.core.format.formatDate
import dev.logb.android.feature.objects.ObjectDetailUiState

@Composable
fun RemindersTab(state: ObjectDetailUiState, counterUnit: String?) {
    var showDone by remember { mutableStateOf(false) }
    if (state.loaded && state.openReminders.isEmpty() && state.doneReminders.isEmpty()) {
        EmptyState(icon = rememberVectorPainter(Icons.Outlined.Notifications), title = stringResource(R.string.reminders_empty), body = stringResource(R.string.reminders_empty_body))
        return
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), modifier = Modifier.fillMaxSize()) {
        items(state.openReminders, key = { it.reminder.uuid }) { ReminderRow(it, counterUnit) }
        if (state.doneReminders.isNotEmpty()) {
            item {
                Text(
                    pluralStringResource(R.plurals.reminders_done, state.doneReminders.size, state.doneReminders.size),
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().clickable { showDone = !showDone }.padding(vertical = 12.dp),
                )
            }
            if (showDone) items(state.doneReminders, key = { it.reminder.uuid }) { ReminderRow(it, counterUnit) }
        }
    }
}

@Composable
private fun ReminderRow(v: ReminderView, counterUnit: String?) {
    val locale = currentLocale()
    val r = v.reminder
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(r.title, style = MaterialTheme.typography.bodyLarge)
            val sub = when {
                r.doneAt != null -> stringResource(R.string.reminder_done_on, formatDate(r.doneAt.take(10), locale))
                r.snoozedUntil != null && (v.daysUntil == null || !v.due) && r.snoozedUntil > java.time.LocalDate.now().toString() -> stringResource(R.string.reminder_snoozed_until, formatDate(r.snoozedUntil, locale))
                v.due -> stringResource(R.string.reminder_due_now)
                v.daysUntil != null && v.daysUntil >= 0 -> pluralStringResource(R.plurals.reminder_in_days, v.daysUntil.toInt(), v.daysUntil)
                v.counterUntil != null && v.counterUntil > 0 -> stringResource(R.string.reminder_in_counter, formatCounter(v.counterUntil, counterUnit, locale))
                else -> ""
            }
            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (v.due) { Spacer(Modifier.width(8.dp)); DueBadge(1) }
    }
}
