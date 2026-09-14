package dev.logb.android.feature.objects.tabs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.logb.android.R
import dev.logb.android.core.design.components.DueBadge
import dev.logb.android.core.design.components.EmptyState
import dev.logb.android.core.domain.ReminderRules
import dev.logb.android.core.domain.ReminderView
import dev.logb.android.core.domain.TimelineRow
import dev.logb.android.core.format.currentLocale
import dev.logb.android.core.format.formatCounter
import dev.logb.android.core.format.formatDate
import dev.logb.android.feature.objects.ObjectDetailUiState
import java.time.LocalDate

/** Everything the tab can do to a reminder; the screen wires each to the view model or the navigator. */
data class ReminderActions(
    val onAdd: () -> Unit,
    val onEdit: (String) -> Unit,
    val onDone: (String, String?) -> Unit,
    val onLogNow: (String, String) -> Unit,
    val onSnooze: (String, Long) -> Unit,
    val onUnsnooze: (String) -> Unit,
    val onDelete: (String) -> Unit,
    val onSkip: (String) -> Unit = {},
    val onRecordReading: () -> Unit = {},
    val onAddReadingReminder: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemindersTab(state: ObjectDetailUiState, counterUnit: String?, actions: ReminderActions? = null) {
    var showDone by remember { mutableStateOf(false) }
    var doneFor by remember { mutableStateOf<ReminderView?>(null) }
    var snoozeFor by remember { mutableStateOf<ReminderView?>(null) }
    var deleteFor by remember { mutableStateOf<ReminderView?>(null) }
    LazyColumn(contentPadding = PaddingValues(16.dp), modifier = Modifier.fillMaxSize()) {
        if (actions != null) item { OutlinedButton(onClick = actions.onAdd, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.reminder_new)) } }
        if (actions != null && counterUnit != null) item { ReadingBlock(state, counterUnit, actions) }
        if (state.loaded && state.openReminders.isEmpty() && state.doneReminders.isEmpty()) {
            item { EmptyState(icon = rememberVectorPainter(Icons.Outlined.Notifications), title = stringResource(R.string.reminders_empty), body = stringResource(R.string.reminders_empty_body)) }
        }
        items(state.openReminders, key = { it.reminder.uuid }) { v ->
            ReminderRow(v, counterUnit, actions, onDoneRequest = { doneFor = v }, onSnoozeRequest = { snoozeFor = v }, onDeleteRequest = { deleteFor = v })
        }
        if (state.doneReminders.isNotEmpty()) {
            item {
                Text(
                    pluralStringResource(R.plurals.reminders_done, state.doneReminders.size, state.doneReminders.size),
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().clickable { showDone = !showDone }.padding(vertical = 12.dp),
                )
            }
            if (showDone) items(state.doneReminders, key = { it.reminder.uuid }) { v -> ReminderRow(v, counterUnit, actions, {}, {}, onDeleteRequest = { deleteFor = v }) }
        }
        item { Spacer(Modifier.height(72.dp)) }
    }
    doneFor?.let { v ->
        val entries = state.years.flatMap { y -> y.rows.flatMap { r -> when (r) { is TimelineRow.Entry -> listOf(r.activity); is TimelineRow.Readings -> r.readings } } }.take(20)
        ModalBottomSheet(onDismissRequest = { doneFor = null }) {
            Text(stringResource(R.string.done_title, v.reminder.title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            ListItem(headlineContent = { Text(stringResource(R.string.done_log_now)) }, modifier = Modifier.clickable { doneFor = null; actions?.onLogNow?.invoke(v.reminder.uuid, v.reminder.title) })
            ListItem(headlineContent = { Text(stringResource(R.string.done_just_done)) }, modifier = Modifier.clickable { doneFor = null; actions?.onDone?.invoke(v.reminder.uuid, null) })
            if (entries.isNotEmpty()) {
                Text(stringResource(R.string.done_link_entry), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                val locale = currentLocale()
                entries.forEach { a ->
                    ListItem(
                        headlineContent = { Text(a.title) },
                        supportingContent = { Text(formatDate(a.date, locale)) },
                        modifier = Modifier.clickable { doneFor = null; actions?.onDone?.invoke(v.reminder.uuid, a.uuid) },
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    snoozeFor?.let { v ->
        ModalBottomSheet(onDismissRequest = { snoozeFor = null }) {
            Text(stringResource(R.string.snooze_title, v.reminder.title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            listOf(1L to R.string.snooze_day, 7L to R.string.snooze_week, 30L to R.string.snooze_month).forEach { (days, label) ->
                ListItem(headlineContent = { Text(stringResource(label)) }, modifier = Modifier.clickable { snoozeFor = null; actions?.onSnooze?.invoke(v.reminder.uuid, days) })
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    deleteFor?.let { v ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text(stringResource(R.string.delete_reminder_title, v.reminder.title)) },
            confirmButton = { TextButton(onClick = { deleteFor = null; actions?.onDelete?.invoke(v.reminder.uuid) }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** The web's reading block: where the counter stands, a way to record it, and the reading reminder when none exists. */
@Composable
private fun ReadingBlock(state: ObjectDetailUiState, counterUnit: String, actions: ReminderActions) {
    val locale = currentLocale()
    val stats = state.stats
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(
            if (stats?.currentCounter != null && stats.lastReadingDate != null) stringResource(R.string.reminder_last_reading, formatCounter(stats.currentCounter, counterUnit, locale), formatDate(stats.lastReadingDate, locale))
            else stringResource(R.string.reminder_no_reading),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = actions.onRecordReading) { Text(stringResource(R.string.reading_new)) }
            if (state.openReminders.none { it.reminder.kind == ReminderRules.KIND_READING }) {
                TextButton(onClick = actions.onAddReadingReminder) { Text(stringResource(R.string.reminder_new_reading)) }
            }
        }
    }
}

@Composable
fun reminderSubtitle(v: ReminderView, counterUnit: String?, today: LocalDate = LocalDate.now()): String {
    val locale = currentLocale()
    val r = v.reminder
    val snoozed = ReminderRules.parseDate(r.snoozedUntil)?.let { it > today } == true
    val base = when {
        r.doneAt != null -> stringResource(R.string.reminder_done_on, formatDate(r.doneAt.take(10), locale))
        snoozed -> stringResource(R.string.reminder_snoozed_until, formatDate(r.snoozedUntil!!, locale))
        v.due -> stringResource(R.string.reminder_due_now)
        v.daysUntil != null && v.daysUntil >= 0 -> pluralStringResource(R.plurals.reminder_in_days, v.daysUntil.toInt(), v.daysUntil)
        v.counterUntil != null && v.counterUntil > 0 -> stringResource(R.string.reminder_in_counter, formatCounter(v.counterUntil, counterUnit, locale))
        else -> ""
    }
    // The usage projection rides along, as the web's reminder row shows it: never instead of the real terms.
    val estimate = v.estimatedDueDate?.takeIf { r.doneAt == null && !snoozed && !v.due }?.let { stringResource(R.string.reminder_estimated, formatDate(it.toString(), locale)) }
    return listOfNotNull(base.takeIf { it.isNotEmpty() }, estimate).joinToString(" · ")
}

@Composable
private fun ReminderRow(v: ReminderView, counterUnit: String?, actions: ReminderActions?, onDoneRequest: () -> Unit, onSnoozeRequest: () -> Unit, onDeleteRequest: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val r = v.reminder
    val snoozed = ReminderRules.parseDate(r.snoozedUntil)?.let { it > LocalDate.now() } == true
    Row(Modifier.fillMaxWidth().clickable(enabled = actions != null) { menu = true }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(r.title, style = MaterialTheme.typography.bodyLarge)
            val sub = reminderSubtitle(v, counterUnit)
            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (v.due) { Spacer(Modifier.width(8.dp)); DueBadge(1) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (r.doneAt == null && r.kind == ReminderRules.KIND_SERVICE) DropdownMenuItem(text = { Text(stringResource(R.string.reminder_done)) }, onClick = { menu = false; onDoneRequest() })
            if (r.doneAt == null && !snoozed) DropdownMenuItem(text = { Text(stringResource(R.string.reminder_snooze)) }, onClick = { menu = false; onSnoozeRequest() })
            if (r.doneAt == null && v.due) DropdownMenuItem(text = { Text(stringResource(R.string.reminder_skip)) }, onClick = { menu = false; actions?.onSkip?.invoke(r.uuid) })
            if (r.doneAt == null && snoozed) DropdownMenuItem(text = { Text(stringResource(R.string.reminder_unsnooze)) }, onClick = { menu = false; actions?.onUnsnooze?.invoke(r.uuid) })
            if (r.doneAt == null) DropdownMenuItem(text = { Text(stringResource(R.string.edit)) }, onClick = { menu = false; actions?.onEdit?.invoke(r.uuid) })
            DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = { menu = false; onDeleteRequest() })
        }
    }
}
