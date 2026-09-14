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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject

data class DueItem(val view: ReminderView, val objectUuid: String, val objectName: String, val objectType: String, val counterUnit: String?)

/** Every reminder that is due or comes due within 30 days, across every object: what the banner opens. */
class DueListModel(private val db: LogbDatabase, private val today: () -> LocalDate = { LocalDate.now() }) {
    fun items(withinDays: Long = 30): Flow<List<DueItem>> = db.reminderDao().allOpen().map { open ->
        val t = today()
        open.groupBy { it.objectUuid }.flatMap { (objectUuid, rs) ->
            val obj = db.objectDao().get(objectUuid)?.takeIf { it.deletedAt == null } ?: return@flatMap emptyList()
            val stats = db.objectDao().stats(objectUuid)
            val lastReading = ReminderPresenter.clampLastReading(stats.lastReadingDate, t)
            rs.map { ReminderPresenter.present(it, stats.currentCounter, lastReading, t) }
                .filter { ReminderRules.isUpcoming(t, it.due, it.daysUntil, withinDays, ReminderRules.parseDate(it.reminder.snoozedUntil)) }
                .map { DueItem(it, obj.uuid, obj.name, obj.type, obj.counterUnit) }
        }.sortedWith(compareByDescending<DueItem> { it.view.due }.thenBy { it.view.daysUntil ?: Long.MAX_VALUE }.thenBy { it.objectName })
    }
}

@HiltViewModel
class DueListViewModel @Inject constructor(accounts: ActiveAccount) : ViewModel() {
    val items: StateFlow<List<DueItem>?> = DueListModel(accounts.db).items().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun DueListScreen(onBack: () -> Unit, onOpen: (String) -> Unit, viewModel: DueListViewModel = hiltViewModel()) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        LogbTopBar(title = stringResource(R.string.due_title), onBack = onBack)
        val list = items ?: return@Column
        if (list.isEmpty()) {
            EmptyState(rememberVectorPainter(Icons.Outlined.Notifications), stringResource(R.string.due_empty), stringResource(R.string.due_empty_body))
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(vertical = 8.dp), modifier = Modifier.fillMaxSize()) {
            items(list, key = { it.view.reminder.uuid }) { item ->
                ListItem(
                    headlineContent = { Text(item.view.reminder.title) },
                    supportingContent = { Text("${item.objectName} · ${reminderSubtitle(item.view, item.counterUnit)}") },
                    leadingContent = { ObjectTypeIcon(item.objectType) },
                    trailingContent = { if (item.view.due) DueBadge(1) },
                    modifier = Modifier.clickable { onOpen(item.objectUuid) },
                )
            }
        }
    }
}
