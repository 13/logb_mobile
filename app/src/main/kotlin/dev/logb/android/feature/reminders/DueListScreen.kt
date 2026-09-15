package dev.logb.android.feature.reminders

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.ListItem
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Row
import dev.logb.android.core.format.currentLocale
import dev.logb.android.core.format.formatCounter
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.logb.android.R
import dev.logb.android.core.auth.ActiveAccount
import dev.logb.android.core.db.LogbDatabase
import dev.logb.android.core.design.components.DueBadge
import dev.logb.android.core.design.components.EmptyState
import dev.logb.android.core.design.components.LogbTopBar
import dev.logb.android.core.design.components.ObjectTypeIcon
import dev.logb.android.core.domain.ReminderPresenter
import dev.logb.android.core.domain.ReminderRules
import dev.logb.android.core.domain.ReminderView
import dev.logb.android.feature.objects.tabs.reminderSubtitle
import dev.logb.android.feature.stats.InsightsModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class DueItem(val view: ReminderView, val objectUuid: String, val objectName: String, val objectType: String, val counterUnit: String?)

/** Every reminder that is due or comes due within 30 days, across every object: what the banner opens. */
class DueListModel(private val db: LogbDatabase, private val today: () -> LocalDate = { LocalDate.now() }) {
    private val insights = InsightsModel(db, today)

    /**
     * As the server's lookahead: a mileage-only service shows up once its usage estimate is near.
     * Objects, stats and readings are fetched once, not once per reminder's object, so the query
     * count does not grow with how many objects or reminders there are.
     */
    fun items(withinDays: Long = 30): Flow<List<DueItem>> = db.reminderDao().allOpen().map { open ->
        val t = today()
        val objects = db.objectDao().all().first().associateBy { it.uuid }
        val stats = db.objectDao().statsForAll().associateBy { it.objectUuid }
        val readings = db.activityDao().readingRowsForAll(t.plusDays(1).toString()).groupBy { it.objectUuid }
        open.groupBy { it.objectUuid }.flatMap { (objectUuid, rs) ->
            val obj = objects[objectUuid] ?: return@flatMap emptyList()
            val s = stats[objectUuid]
            val lastReading = ReminderPresenter.clampLastReading(s?.lastReadingDate, t)
            val usage = insights.usageFrom(readings[objectUuid].orEmpty(), t)
            rs.map { ReminderPresenter.present(it, s?.currentCounter, lastReading, t, usage) }
                .filter { ReminderRules.isUpcoming(t, it.due, it.soonestDays, withinDays, ReminderRules.parseDate(it.reminder.snoozedUntil)) }
                .map { DueItem(it, obj.uuid, obj.name, obj.type, obj.counterUnit) }
        }.sortedWith(compareByDescending<DueItem> { it.view.due }.thenBy { it.view.soonestDays ?: Long.MAX_VALUE }.thenBy { it.objectName })
    }
}

@HiltViewModel
class DueListViewModel @Inject constructor(accounts: ActiveAccount, private val repos: dev.logb.android.core.sync.Repositories) : ViewModel() {
    val items: StateFlow<List<DueItem>?> = DueListModel(accounts.db).items().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The web dashboard's inline snooze: a week. */
    fun snooze(uuid: String) = viewModelScope.launch { repos.reminderRepository.snooze(uuid, 7) }
}

@Composable
fun DueListScreen(onBack: () -> Unit, onOpen: (String) -> Unit, onReading: (String) -> Unit = {}, viewModel: DueListViewModel = hiltViewModel()) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    DueListContent(items, onBack, onOpen, onSnooze = viewModel::snooze, onReading = onReading)
}

/** The screen without its view model; `items` null while loading. */
@Composable
fun DueListContent(items: List<DueItem>?, onBack: () -> Unit, onOpen: (String) -> Unit, onSnooze: (String) -> Unit = {}, onReading: (String) -> Unit = {}) {
    Column(Modifier.fillMaxSize()) {
        LogbTopBar(title = stringResource(R.string.due_title), onBack = onBack)
        val list = items ?: return@Column
        if (list.isEmpty()) {
            EmptyState(rememberVectorPainter(Icons.Outlined.Notifications), stringResource(R.string.due_empty), stringResource(R.string.due_empty_body))
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(vertical = 8.dp), modifier = Modifier.fillMaxSize()) {
            items(list, key = { it.view.reminder.uuid }) { item ->
                val v = item.view
                val locale = currentLocale()
                // As the web's dashboard line: when, then how far by counter, then the usage estimate.
                val counterPart = v.counterUntil?.takeIf { it > 0 && v.daysUntil != null && !v.due }?.let { stringResource(R.string.reminder_in_counter, formatCounter(it, item.counterUnit, locale)) }
                ListItem(
                    headlineContent = { Text(v.reminder.title) },
                    supportingContent = {
                        Column {
                            Text(listOfNotNull(item.objectName, reminderSubtitle(v, item.counterUnit).takeIf { it.isNotEmpty() }, counterPart).joinToString(" · "))
                            Row {
                                if (v.reminder.kind == ReminderRules.KIND_READING && item.counterUnit != null) {
                                    TextButton(onClick = { onReading(item.objectUuid) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(stringResource(R.string.reading_new)) }
                                } else {
                                    TextButton(onClick = { onSnooze(v.reminder.uuid) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(stringResource(R.string.reminder_snooze)) }
                                }
                            }
                        }
                    },
                    leadingContent = { ObjectTypeIcon(item.objectType) },
                    trailingContent = { if (v.due) DueBadge(1) },
                    modifier = Modifier.clickable { onOpen(item.objectUuid) },
                )
            }
        }
    }
}
