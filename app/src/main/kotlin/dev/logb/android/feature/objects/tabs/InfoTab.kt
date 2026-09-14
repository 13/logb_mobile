package dev.logb.android.feature.objects.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.logb.android.R
import dev.logb.android.core.format.currentLocale
import dev.logb.android.core.format.formatCents
import dev.logb.android.core.format.formatDate
import dev.logb.android.feature.objects.ObjectCardRow
import dev.logb.android.feature.objects.ObjectDetailUiState
import dev.logb.android.feature.objects.typeLabel
import dev.logb.android.feature.stats.InsightsSection
import dev.logb.android.feature.stats.ObjectInsights

@Composable
fun InfoTab(
    state: ObjectDetailUiState, onOpen: (String) -> Unit, onEdit: () -> Unit, onAddChild: () -> Unit, onArchive: (Boolean) -> Unit, onDelete: () -> Unit,
    insights: ObjectInsights? = null, includeContents: Boolean = false, onIncludeContents: (Boolean) -> Unit = {},
) {
    val obj = state.obj ?: return
    val locale = currentLocale()
    var confirmDelete by remember { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        if (obj.description.isNotBlank()) item { Text(obj.description, style = MaterialTheme.typography.bodyLarge) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                InfoLine(stringResource(R.string.info_type), typeLabel(obj.type))
                obj.purchaseDate?.let { InfoLine(stringResource(R.string.info_purchased), formatDate(it, locale)) }
                obj.purchasePriceCents?.let { InfoLine(stringResource(R.string.info_price), formatCents(it, state.currency, locale)) }
                obj.counterUnit?.let { InfoLine(stringResource(R.string.info_counter_unit), it) }
                obj.fuelUnit?.let { InfoLine(stringResource(R.string.info_fuel_unit), it) }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.edit)) }
                OutlinedButton(onClick = { onArchive(obj.archivedAt == null) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(if (obj.archivedAt == null) R.string.archive else R.string.unarchive))
                }
            }
        }
        item {
            InsightsSection(insights, state.obj.counterUnit, state.currency, includeContents, onIncludeContents)
        }
        item {
            Text(stringResource(R.string.info_contents), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        }
        items(state.children, key = { it.uuid }) { card -> ObjectCardRow(card, state.currency, onClick = { onOpen(card.uuid) }) }
        item { OutlinedButton(onClick = onAddChild, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.add_here)) } }
        item {
            TextButton(onClick = { confirmDelete = true }, modifier = Modifier.padding(top = 16.dp)) {
                Text(stringResource(R.string.delete_object), color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_object_title, obj.name)) },
            text = { Text(pluralStringResource(R.plurals.delete_object_body, state.stats?.activityCount ?: 0, state.stats?.activityCount ?: 0)) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
