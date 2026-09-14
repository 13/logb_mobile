package dev.logb.android.feature.objects

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.logb.android.R
import dev.logb.android.core.design.components.DueBadge
import dev.logb.android.core.design.components.EmptyState
import dev.logb.android.core.design.components.LogbTopBar
import dev.logb.android.core.design.components.ObjectTypeIcon
import dev.logb.android.core.design.components.SyncLine
import dev.logb.android.core.design.theme.LocalWarnColor
import dev.logb.android.core.design.theme.figureSmall
import dev.logb.android.core.format.formatCents
import dev.logb.android.core.format.formatCounter
import dev.logb.android.core.format.currentLocale
import dev.logb.android.core.format.formatDate
import dev.logb.android.core.sync.SyncStatus

@Composable
fun ObjectsScreen(onOpen: (String) -> Unit, onOpenSync: () -> Unit = {}, onNew: () -> Unit = {}, onOpenDue: () -> Unit = {}, onLog: (String) -> Unit = {}, onReading: (String) -> Unit = {}, viewModel: ObjectsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ObjectsContent(state, onOpen = onOpen, onOpenSync = onOpenSync, onRefresh = viewModel::refresh, onToggleArchived = viewModel::toggleArchived, onNew = onNew, onOpenDue = onOpenDue, onLog = onLog, onReading = onReading)
}

/** The screen without its view model, so a UI test can hand it a state. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObjectsContent(state: ObjectsUiState, onOpen: (String) -> Unit, onOpenSync: () -> Unit, onRefresh: () -> Unit, onToggleArchived: () -> Unit, onNew: () -> Unit = {}, onOpenDue: () -> Unit = {}, onLog: (String) -> Unit = {}, onReading: (String) -> Unit = {}) {
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        LogbTopBar(title = stringResource(R.string.nav_objects))
        SyncLine(state.sync, onOpenSync, failed = state.failed)
        PullToRefreshBox(isRefreshing = state.sync is SyncStatus.Syncing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
                if (state.dueCount > 0) item { DueBanner(state.dueCount, onOpenDue) }
                item {
                    FilterChip(selected = state.archived, onClick = onToggleArchived, label = { Text(stringResource(R.string.filter_archived)) })
                }
                if (state.loaded && state.cards.isEmpty()) {
                    item {
                        EmptyState(
                            icon = painterResource(R.drawable.ic_type_object),
                            title = stringResource(if (state.archived) R.string.objects_empty_archived else R.string.objects_empty),
                            body = stringResource(R.string.objects_empty_body),
                        )
                    }
                }
                items(state.cards, key = { it.uuid }) { card -> ObjectCardRow(card, state.currency, onClick = { onOpen(card.uuid) }, onLog = { onLog(card.uuid) }, onReading = if (card.counterUnit != null) ({ onReading(card.uuid) }) else null) }
                item { Spacer(Modifier.height(72.dp)) }
            }
        }
    }
    FloatingActionButton(onClick = onNew, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
        Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.object_new))
    }
    }
}

@Composable
private fun DueBanner(count: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).background(LocalWarnColor.current.copy(alpha = 0.12f), MaterialTheme.shapes.medium).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.NotificationsActive, contentDescription = null, tint = LocalWarnColor.current)
        Spacer(Modifier.width(12.dp))
        Text(pluralStringResource(R.plurals.reminders_due, count, count), style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ObjectCardRow(card: ObjectCard, currency: String, onClick: () -> Unit, onLog: (() -> Unit)? = null, onReading: (() -> Unit)? = null) {
    val locale = currentLocale()
    var menu by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = { if (onLog != null) menu = true }),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape), contentAlignment = Alignment.Center) {
                ObjectTypeIcon(card.type, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(card.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                    if (card.dueCount > 0) { Spacer(Modifier.width(8.dp)); DueBadge(card.dueCount) }
                }
                val figures = listOfNotNull(
                    card.counter?.let { formatCounter(it, card.counterUnit, locale) },
                    formatCents(card.totalCostCents, currency, locale).takeIf { card.totalCostCents > 0 },
                )
                if (figures.isNotEmpty()) {
                    Text(figures.joinToString(" · "), style = MaterialTheme.typography.figureSmall, color = MaterialTheme.colorScheme.onSurface)
                }
                Text(
                    card.lastActivityDate?.let { stringResource(R.string.last_entry, formatDate(it, locale)) } ?: stringResource(R.string.no_entries_yet),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onLog != null) {
                Box {
                    IconButton(onClick = onLog) { Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.entry_new)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.entry_new)) }, onClick = { menu = false; onLog() })
                        if (onReading != null) DropdownMenuItem(text = { Text(stringResource(R.string.reading_new)) }, onClick = { menu = false; onReading() })
                    }
                }
            }
        }
    }
}
