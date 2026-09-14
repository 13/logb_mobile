package dev.logb.android.feature.objects

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.logb.android.R
import dev.logb.android.core.design.components.LogbTopBar
import dev.logb.android.core.design.components.StatFigure
import dev.logb.android.core.format.NO_VALUE
import dev.logb.android.core.format.currentLocale
import dev.logb.android.core.format.formatCents
import dev.logb.android.core.format.formatCounter
import dev.logb.android.core.format.formatDate
import dev.logb.android.feature.objects.tabs.DocumentsTab
import dev.logb.android.feature.objects.tabs.InfoTab
import dev.logb.android.feature.objects.tabs.RemindersTab
import dev.logb.android.feature.objects.tabs.TimelineTab

private val TABS = listOf("timeline", "documents", "reminders", "info")

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ObjectDetailScreen(onBack: () -> Unit, onOpen: (String) -> Unit, viewModel: ObjectDetailViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(TABS.indexOf(viewModel.route.tab).coerceAtLeast(0)) }
    val locale = currentLocale()
    val obj = state.obj
    Column(Modifier.fillMaxSize()) {
        LogbTopBar(title = obj?.name ?: "", onBack = onBack)
        if (obj == null) return@Column
        if (state.ancestors.isNotEmpty()) {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                state.ancestors.forEachIndexed { i, a ->
                    Text(a.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { onOpen(a.uuid) })
                    if (i < state.ancestors.lastIndex) Text("›", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        val stats = state.stats
        if (stats != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
            ) {
                FlowRow(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp), maxItemsInEachRow = 4) {
                    StatFigure(stringResource(R.string.stat_spent), formatCents(stats.totalCostCents, state.currency, locale))
                    StatFigure(stringResource(R.string.stat_entries), stats.activityCount.toString())
                    if (obj.counterUnit != null) StatFigure(stringResource(R.string.stat_counter), formatCounter(stats.currentCounter, obj.counterUnit, locale))
                    StatFigure(stringResource(R.string.stat_owned_since), obj.purchaseDate?.let { formatDate(it, locale) } ?: NO_VALUE)
                }
            }
        }
        PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
            listOf(R.string.tab_timeline, R.string.tab_documents, R.string.tab_reminders, R.string.tab_info).forEachIndexed { i, label ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(label), maxLines = 1) })
            }
        }
        when (TABS[tab]) {
            "timeline" -> TimelineTab(state, onFilter = viewModel::setFilter, thumbUrl = { viewModel.fileUrl(it, thumb = true) })
            "documents" -> DocumentsTab(state, thumbUrl = { viewModel.fileUrl(it, thumb = true) })
            "reminders" -> RemindersTab(state, obj.counterUnit)
            "info" -> InfoTab(state, onOpen = onOpen)
        }
    }
}
