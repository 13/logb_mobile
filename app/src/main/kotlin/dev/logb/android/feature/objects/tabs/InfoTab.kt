package dev.logb.android.feature.objects.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.logb.android.R
import dev.logb.android.core.format.currentLocale
import dev.logb.android.core.format.formatCents
import dev.logb.android.core.format.formatDate
import dev.logb.android.feature.objects.ObjectCardRow
import dev.logb.android.feature.objects.ObjectDetailUiState

@Composable
fun InfoTab(state: ObjectDetailUiState, onOpen: (String) -> Unit) {
    val obj = state.obj ?: return
    val locale = currentLocale()
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        if (obj.description.isNotBlank()) item { Text(obj.description, style = MaterialTheme.typography.bodyLarge) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                InfoLine(stringResource(R.string.info_type), obj.type)
                obj.purchaseDate?.let { InfoLine(stringResource(R.string.info_purchased), formatDate(it, locale)) }
                obj.purchasePriceCents?.let { InfoLine(stringResource(R.string.info_price), formatCents(it, state.currency, locale)) }
                obj.counterUnit?.let { InfoLine(stringResource(R.string.info_counter_unit), it) }
                obj.fuelUnit?.let { InfoLine(stringResource(R.string.info_fuel_unit), it) }
            }
        }
        if (state.children.isNotEmpty()) {
            item { Text(stringResource(R.string.info_contents), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
            items(state.children, key = { it.uuid }) { card -> ObjectCardRow(card, state.currency, onClick = { onOpen(card.uuid) }) }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
