package dev.logb.android.feature.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import dev.logb.android.core.design.components.EmptyState
import dev.logb.android.core.design.components.LogbTopBar
import dev.logb.android.core.design.components.StatFigure
import dev.logb.android.core.domain.SpendStats
import dev.logb.android.core.format.currentLocale
import dev.logb.android.core.format.formatCents
import dev.logb.android.feature.objects.tabs.categoryLabel
import dev.logb.android.feature.objects.typeLabel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StatsUiState(
    /** Four digits, or null for all years. */
    val year: String? = null,
    /** Every year with spend, newest first; a selected year that has no spend any more stays offered. */
    val years: List<String> = emptyList(),
    val purchases: Boolean = false,
    val stats: SpendStats.Stats? = null,
    val currency: String = "EUR",
) {
    val loaded: Boolean get() = stats != null
}

@HiltViewModel
class StatsViewModel @Inject constructor(accounts: ActiveAccount, private val prefs: StatsPrefs) : ViewModel() {
    private val model = StatsModel(accounts.db)
    private val year = MutableStateFlow<String?>(null)
    private val expanded = MutableStateFlow<Set<String>>(emptySet())
    private val currency = accounts.signedIn?.currency ?: "EUR"

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<StatsUiState> = combine(year, prefs.includePurchases, accounts.db.activityDao().version(), accounts.db.objectDao().all()) { y, p, _, _ -> y to p }
        .mapLatest { (y, p) ->
            val s = model.stats(y?.toIntOrNull(), p)
            val years = if (y != null && y !in s.years) listOf(y) + s.years else s.years
            StatsUiState(year = y, years = years, purchases = p, stats = s, currency = currency)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    val expandedIds: StateFlow<Set<String>> = expanded

    fun setYear(y: String?) { year.value = y }
    fun setPurchases(on: Boolean) = viewModelScope.launch { prefs.setIncludePurchases(on) }
    fun toggle(id: String) { expanded.value = if (id in expanded.value) expanded.value - id else expanded.value + id }
}

@Composable
fun StatsScreen(onBack: () -> Unit, onOpenObject: (String) -> Unit, viewModel: StatsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val expanded by viewModel.expandedIds.collectAsStateWithLifecycle()
    StatsContent(state, expanded, onBack = onBack, onYear = viewModel::setYear, onPurchases = viewModel::setPurchases, onToggle = viewModel::toggle, onOpenObject = onOpenObject)
}

/** The screen without its view model. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsContent(
    state: StatsUiState, expanded: Set<String>, onBack: () -> Unit, onYear: (String?) -> Unit, onPurchases: (Boolean) -> Unit,
    onToggle: (String) -> Unit, onOpenObject: (String) -> Unit,
) {
    val locale = currentLocale()
    val fmt = { cents: Long -> formatCents(cents, state.currency, locale) }
    val total = state.stats?.totalCents ?: 0
    val share = { cents: Long -> "${fmt(cents)} · ${sharePct(cents, total)}%" }
    Column(Modifier.fillMaxSize()) {
        LogbTopBar(title = stringResource(R.string.stats_title), onBack = onBack)
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxSize()) {
            item { YearPicker(state.year, state.years, onYear) }
            item {
                SwitchRow(stringResource(R.string.stats_purchases), state.purchases, onPurchases)
            }
            val stats = state.stats ?: return@LazyColumn
            if (stats.totalCents == 0L) {
                item { EmptyState(rememberVectorPainter(Icons.Outlined.BarChart), stringResource(R.string.stats_none), "") }
                return@LazyColumn
            }
            item { StatFigure(stringResource(R.string.stats_total), fmt(stats.totalCents)) }
            item {
                Section(stringResource(R.string.stats_over_time)) {
                    // Months show the amount alone: a share of the year on every one of twelve bars is noise.
                    BarList(stats.overTime.map { Bar(it.bucket, Labels.periodLabel(it.bucket, locale), it.costCents, fmt(it.costCents)) })
                }
            }
            item {
                Section(stringResource(R.string.stats_by_object)) {
                    val archived = stringResource(R.string.stats_archived)
                    BarList(
                        flattenTree(stats.byObject, expanded).map { r ->
                            Bar(
                                key = r.node.id, label = r.node.name, value = r.node.costCents, display = share(r.node.costCents), depth = r.depth,
                                note = if (r.node.archived) archived else null,
                                expanded = if (r.hasChildren) r.expanded else null, onToggle = { onToggle(r.node.id) }, onLabel = { onOpenObject(r.node.id) },
                            )
                        },
                        labelWidth = 128.dp,
                    )
                }
            }
            item {
                Section(stringResource(R.string.stats_by_type)) {
                    BarList(stats.byType.map { Bar(it.bucket, typeLabel(it.bucket), it.costCents, share(it.costCents)) }, labelWidth = 128.dp)
                }
            }
            item {
                Section(stringResource(R.string.stats_by_category)) {
                    BarList(
                        stats.byCategory.map { Bar(it.bucket, if (it.bucket == SpendStats.PURCHASE_PRICE) stringResource(R.string.stats_purchase_price) else categoryLabel(it.bucket), it.costCents, share(it.costCents)) },
                        labelWidth = 128.dp,
                    )
                }
            }
        }
    }
}

/** A labelled switch whose whole row toggles, as a settings row does. */
@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun YearPicker(year: String?, years: List<String>, onYear: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val allYears = stringResource(R.string.stats_all_years)
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = year ?: allYears, onValueChange = {}, readOnly = true,
            label = { Text(stringResource(R.string.stats_year)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(allYears) }, onClick = { onYear(null); open = false })
            years.forEach { y -> DropdownMenuItem(text = { Text(y) }, onClick = { onYear(y); open = false }) }
        }
    }
}

/** The web's `sharePct`. */
fun sharePct(part: Long, total: Long): Int = if (total > 0) Math.round(part.toDouble() / total * 100).toInt() else 0

data class TreeRow(val node: SpendStats.ObjectNode, val depth: Int, val hasChildren: Boolean, val expanded: Boolean)

/** The rows on screen: roots, plus the children of every expanded node whose ancestors are all expanded too. Collapsing a parent hides its whole subtree without forgetting what was open. */
fun flattenTree(nodes: List<SpendStats.ObjectNode>, expanded: Set<String>, depth: Int = 0): List<TreeRow> = nodes.flatMap { node ->
    val open = node.id in expanded
    val row = TreeRow(node, depth, node.children.isNotEmpty(), open)
    if (open) listOf(row) + flattenTree(node.children, expanded, depth + 1) else listOf(row)
}
