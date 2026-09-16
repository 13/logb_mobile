package dev.logb.android.feature.objects

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import dev.logb.android.core.blobs.BlobImage
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
import dev.logb.android.core.design.components.TagChips
import dev.logb.android.core.design.components.TagFilterRow
import dev.logb.android.core.design.theme.LocalWarnColor
import dev.logb.android.core.design.theme.figureSmall
import dev.logb.android.core.format.formatCents
import dev.logb.android.core.format.formatCounter
import dev.logb.android.core.format.currentLocale
import dev.logb.android.core.format.formatDate
import dev.logb.android.core.sync.SyncStatus

@Composable
fun ObjectsScreen(onOpen: (String) -> Unit, onOpenSync: () -> Unit = {}, onNew: () -> Unit = {}, onOpenDue: () -> Unit = {}, onLog: (String) -> Unit = {}, onReading: (String) -> Unit = {}, onOpenStats: () -> Unit = {}, viewModel: ObjectsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ObjectsContent(
        state, onOpen = onOpen, onOpenSync = onOpenSync, onRefresh = viewModel::refresh, onToggleArchived = viewModel::toggleArchived, onNew = onNew, onOpenDue = onOpenDue,
        onLog = onLog, onReading = onReading, onOpenStats = onOpenStats, onQuery = viewModel::setQuery, onSort = viewModel::setSort, onTag = viewModel::setTagFilter,
    )
}

/** The screen without its view model, so a UI test can hand it a state. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObjectsContent(
    state: ObjectsUiState, onOpen: (String) -> Unit, onOpenSync: () -> Unit, onRefresh: () -> Unit, onToggleArchived: () -> Unit, onNew: () -> Unit = {}, onOpenDue: () -> Unit = {},
    onLog: (String) -> Unit = {}, onReading: (String) -> Unit = {}, onOpenStats: () -> Unit = {}, onQuery: (String) -> Unit = {}, onSort: (SortKey) -> Unit = {}, onTag: (String?) -> Unit = {},
) {
    var sortMenu by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        BackHandler(enabled = state.archived) { onToggleArchived() }
        LogbTopBar(title = stringResource(if (state.archived) R.string.filter_archived else R.string.nav_objects), actions = {
            IconToggleButton(checked = state.archived, onCheckedChange = { onToggleArchived() }) {
                Icon(
                    if (state.archived) Icons.Filled.Inventory2 else Icons.Outlined.Inventory2,
                    contentDescription = stringResource(R.string.filter_archived),
                )
            }
            Box {
                IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = stringResource(R.string.sort)) }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    SortKey.entries.forEach { key ->
                        DropdownMenuItem(
                            text = { Text(sortLabel(key)) },
                            leadingIcon = { if (key == state.sort) Icon(Icons.Outlined.Check, contentDescription = null) },
                            onClick = { sortMenu = false; onSort(key) },
                        )
                    }
                }
            }
            IconButton(onClick = onOpenStats) { Icon(Icons.Outlined.BarChart, contentDescription = stringResource(R.string.stats_title)) }
        })
        SyncLine(state.sync, onOpenSync, failed = state.failed)
        OutlinedTextField(
            value = state.query, onValueChange = onQuery, singleLine = true,
            placeholder = { Text(stringResource(R.string.objects_search_hint)) },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = { if (state.query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.clear)) } },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
        if (state.tagFilter != null) {
            TagFilterRow(state.tagFilter, onClear = { onTag(null) })
        }
        PullToRefreshBox(isRefreshing = state.sync is SyncStatus.Syncing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
                if (state.dueCount > 0) item { DueBanner(state.dueCount, onOpenDue) }
                if (state.loaded && state.cards.isEmpty()) {
                    item {
                        EmptyState(
                            icon = painterResource(R.drawable.ic_type_object),
                            title = stringResource(if (state.archived) R.string.objects_empty_archived else R.string.objects_empty),
                            body = stringResource(R.string.objects_empty_body),
                        )
                    }
                }
                items(state.cards, key = { it.uuid }) { card -> ObjectCardRow(card, state.currency, onClick = { onOpen(card.uuid) }, onLog = { onLog(card.uuid) }, onReading = if (card.counterUnit != null) ({ onReading(card.uuid) }) else null, parentName = state.parentNames[card.uuid], onTag = { tag -> onTag(tag) }, activeTag = state.tagFilter) }
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
fun sortLabel(key: SortKey): String = stringResource(
    when (key) { SortKey.Name -> R.string.sort_name; SortKey.LastActivity -> R.string.sort_last_activity; SortKey.Changed -> R.string.sort_changed; SortKey.Cost -> R.string.sort_cost; SortKey.Counter -> R.string.sort_counter },
)

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
fun ObjectCardRow(card: ObjectCard, currency: String, onClick: () -> Unit, onLog: (() -> Unit)? = null, onReading: (() -> Unit)? = null, parentName: String? = null, onTag: ((String) -> Unit)? = null, activeTag: String? = null) {
    val locale = currentLocale()
    var menu by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = { if (onLog != null) menu = true }),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape).clip(CircleShape), contentAlignment = Alignment.Center) {
                if (card.coverSha != null) {
                    AsyncImage(model = BlobImage(card.coverSha), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    ObjectTypeIcon(card.type, tint = MaterialTheme.colorScheme.primary)
                }
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
                // A month is the unit people think in for mileage; 30.44 days is the average one.
                val usage = card.counterPerDayMilli?.takeIf { card.counterUnit != null }?.let { stringResource(R.string.insights_per_month, formatCounter(Math.round(it * 30.44 / 1000), card.counterUnit, locale)) }
                val meta = listOfNotNull(
                    parentName?.let { stringResource(R.string.search_in, it) },
                    card.lastActivityDate?.let { stringResource(R.string.last_entry, formatDate(it, locale)) } ?: stringResource(R.string.no_entries_yet),
                    usage,
                )
                Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TagChips(card.tags, Modifier.padding(top = 4.dp), onSelect = onTag, active = activeTag)
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
