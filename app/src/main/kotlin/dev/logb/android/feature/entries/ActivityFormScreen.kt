package dev.logb.android.feature.entries

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.logb.android.R
import dev.logb.android.core.design.components.LogbTopBar
import dev.logb.android.core.design.components.errorText
import dev.logb.android.core.design.theme.LocalWarnColor
import dev.logb.android.core.format.currentLocale
import dev.logb.android.core.format.formatCounter
import dev.logb.android.feature.objects.DateField
import dev.logb.android.feature.objects.tabs.categoryLabel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActivityFormScreen(onBack: () -> Unit, viewModel: ActivityFormViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(state.saved) { if (state.saved) onBack() }
    val locale = currentLocale()
    val unit = state.obj?.counterUnit
    Column(Modifier.fillMaxSize()) {
        LogbTopBar(
            title = stringResource(if (state.editing) R.string.entry_edit else R.string.entry_new),
            onBack = onBack,
            actions = {
                if (state.editing) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.delete)) }
                TextButton(onClick = viewModel::save, enabled = !state.saving) { Text(stringResource(R.string.save)) }
            },
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DateField(stringResource(R.string.field_date), state.date, viewModel::onDate, error = errorText(state.errors["date"]), clearable = false)
            Text(stringResource(R.string.field_category), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                state.categories.forEach { c -> FilterChip(selected = state.category == c, onClick = { viewModel.onCategory(c) }, label = { Text(categoryLabel(c)) }) }
            }
            OutlinedTextField(state.title, viewModel::onTitle, label = { Text(stringResource(R.string.field_title)) }, singleLine = true, isError = "title" in state.errors, supportingText = errorText(state.errors["title"])?.let { { Text(it) } }, modifier = Modifier.fillMaxWidth())
            if (state.suggestions.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.suggestions.forEach { t -> SuggestionChip(onClick = { viewModel.onTitle(t) }, label = { Text(t) }) }
                }
            }
            if (unit != null) {
                OutlinedTextField(
                    state.counter, viewModel::onCounter,
                    label = { Text(stringResource(R.string.field_counter, unit)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = "counterValue" in state.errors,
                    supportingText = {
                        val err = errorText(state.errors["counterValue"])
                        when {
                            err != null -> Text(err)
                            state.counterLowerThanCurrent -> Text(stringResource(R.string.counter_lower_warning, formatCounter(state.currentCounter, unit, locale)), color = LocalWarnColor.current)
                            state.currentCounter != null -> Text(stringResource(R.string.counter_current_hint, formatCounter(state.currentCounter, unit, locale)))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(state.cost, viewModel::onCost, label = { Text(stringResource(R.string.field_cost)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = "costCents" in state.errors, supportingText = errorText(state.errors["costCents"])?.let { { Text(it) } }, modifier = Modifier.fillMaxWidth())
            if (state.showsQuantity) {
                OutlinedTextField(state.quantity, viewModel::onQuantity, label = { Text(stringResource(R.string.field_quantity, state.obj?.fuelUnit ?: "")) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = "quantityMilli" in state.errors, supportingText = errorText(state.errors["quantityMilli"])?.let { { Text(it) } }, modifier = Modifier.fillMaxWidth())
            }
            OutlinedTextField(state.notes, viewModel::onNotes, label = { Text(stringResource(R.string.field_notes)) }, minLines = 2, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.photos_next_release), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Button(onClick = viewModel::save, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.save)) }
            Spacer(Modifier.height(48.dp))
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_entry_title)) },
            text = { Text(stringResource(R.string.delete_entry_body)) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; viewModel.delete(onBack) }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
