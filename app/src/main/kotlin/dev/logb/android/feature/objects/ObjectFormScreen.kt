package dev.logb.android.feature.objects

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.logb.android.R
import dev.logb.android.core.design.components.LogbTopBar
import dev.logb.android.core.design.components.ObjectTypeIcon
import dev.logb.android.core.design.components.TagInput
import dev.logb.android.core.design.components.errorText
import dev.logb.android.core.design.components.templateTitle
import dev.logb.android.core.domain.ObjectTypes
import dev.logb.android.core.domain.Validation
import dev.logb.android.core.format.currentLocale
import dev.logb.android.core.format.formatDate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

fun typeLabelRes(type: String): Int = when (type) {
    "car" -> R.string.type_car; "e_bike" -> R.string.type_e_bike; "bike" -> R.string.type_bike; "motorcycle" -> R.string.type_motorcycle
    "home" -> R.string.type_home; "appliance" -> R.string.type_appliance; "tool" -> R.string.type_tool; "body" -> R.string.type_body
    else -> R.string.type_other
}

@Composable
fun typeLabel(type: String): String = stringResource(typeLabelRes(type))

/** A field label plus the shared date-picker dialog: the value is an ISO date or null. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(label: String, value: String?, onChange: (String?) -> Unit, modifier: Modifier = Modifier, error: String? = null, clearable: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    val locale = currentLocale()
    OutlinedTextField(
        value = value?.let { formatDate(it, locale) } ?: "",
        onValueChange = {},
        readOnly = true,
        label = { Text(label) },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        trailingIcon = { if (clearable && value != null) TextButton(onClick = { onChange(null) }) { Text(stringResource(R.string.clear)) } },
        modifier = modifier.fillMaxWidth().clickable { open = true },
        enabled = false,
        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
            disabledTextColor = MaterialTheme.colorScheme.onSurface, disabledBorderColor = MaterialTheme.colorScheme.outline,
            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant, disabledTrailingIconColor = MaterialTheme.colorScheme.primary,
        ),
    )
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = value?.let { LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() })
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }
                    open = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } },
        ) { DatePicker(state = state) }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ObjectFormScreen(onBack: () -> Unit, onSaved: (String) -> Unit, onDeleted: () -> Unit, viewModel: ObjectFormViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pickParent by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(state.savedUuid) { state.savedUuid?.let(onSaved) }
    val titles = state.templates.associate { it.id to templateTitle(it) }
    Column(Modifier.fillMaxSize()) {
        LogbTopBar(
            title = stringResource(if (state.editing) R.string.object_edit else R.string.object_new),
            onBack = onBack,
            actions = {
                if (state.editing) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.delete)) }
                TextButton(onClick = { viewModel.save(titles) }, enabled = !state.saving) { Text(stringResource(R.string.save)) }
            },
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(state.name, viewModel::onName, label = { Text(stringResource(R.string.field_name)) }, singleLine = true, isError = "name" in state.errors, supportingText = errorText(state.errors["name"])?.let { { Text(it) } }, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.field_type), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ObjectTypes.ALL.forEach { t ->
                    FilterChip(selected = state.type == t, onClick = { viewModel.onType(t) }, label = { Text(typeLabel(t)) }, leadingIcon = { ObjectTypeIcon(t, size = 18.dp) })
                }
            }
            Text(stringResource(R.string.field_counter_unit), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf<String?>(null) + Validation.COUNTER_UNITS).forEach { u ->
                    FilterChip(selected = state.counterUnit == u, onClick = { viewModel.onCounterUnit(u) }, label = { Text(u ?: stringResource(R.string.none)) })
                }
            }
            if (ObjectTypes.hasFuel(state.type)) {
                Text(stringResource(R.string.field_fuel_unit), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (listOf<String?>(null) + Validation.FUEL_UNITS).forEach { u ->
                        FilterChip(selected = state.fuelUnit == u, onClick = { viewModel.onFuelUnit(u) }, label = { Text(u ?: stringResource(R.string.none)) })
                    }
                }
            }
            OutlinedTextField(state.description, viewModel::onDescription, label = { Text(stringResource(R.string.field_description)) }, minLines = 2, modifier = Modifier.fillMaxWidth())
            val showTags by viewModel.showTags.collectAsStateWithLifecycle()
            val tagSuggestions by viewModel.tagSuggestions.collectAsStateWithLifecycle()
            if (showTags) TagInput(state.tags, viewModel::onTags, tagSuggestions, Modifier.fillMaxWidth())
            DateField(stringResource(R.string.field_purchase_date), state.purchaseDate, viewModel::onPurchaseDate, error = errorText(state.errors["purchaseDate"]))
            OutlinedTextField(state.price, viewModel::onPrice, label = { Text(stringResource(R.string.field_purchase_price)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = "purchasePriceCents" in state.errors, supportingText = errorText(state.errors["purchasePriceCents"])?.let { { Text(it) } }, modifier = Modifier.fillMaxWidth())
            ListItem(
                headlineContent = { Text(stringResource(R.string.field_parent)) },
                supportingContent = { Text(state.parent?.name ?: stringResource(R.string.parent_none)) },
                leadingContent = { state.parent?.let { ObjectTypeIcon(it.type) } },
                modifier = Modifier.clickable { pickParent = true },
            )
            if (state.templates.isNotEmpty()) {
                Text(stringResource(R.string.templates_title), style = MaterialTheme.typography.labelLarge)
                Text(stringResource(R.string.templates_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.templates.forEach { t ->
                    Row(Modifier.fillMaxWidth().clickable { viewModel.toggleTemplate(t.id) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = t.id in state.ticked, onCheckedChange = { viewModel.toggleTemplate(t.id) })
                        Text(titles[t.id] ?: t.id)
                    }
                }
                if (state.asksCurrentReading) {
                    OutlinedTextField(state.currentReading, viewModel::onCurrentReading, label = { Text(stringResource(R.string.field_current_reading, state.counterUnit ?: "")) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), supportingText = { Text(stringResource(R.string.current_reading_hint)) }, modifier = Modifier.fillMaxWidth())
                }
            }
            Spacer(Modifier.height(24.dp))
            Button(onClick = { viewModel.save(titles) }, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.save)) }
        }
    }
    if (pickParent) {
        ModalBottomSheet(onDismissRequest = { pickParent = false }) {
            ListItem(headlineContent = { Text(stringResource(R.string.parent_none)) }, modifier = Modifier.clickable { viewModel.onParent(null); pickParent = false })
            state.parentCandidates.forEach { o ->
                ListItem(headlineContent = { Text(o.name) }, leadingContent = { ObjectTypeIcon(o.type) }, modifier = Modifier.clickable { viewModel.onParent(o); pickParent = false })
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_object_title, state.name)) },
            text = { Text(pluralStringResource(R.plurals.delete_object_body, state.activityCount, state.activityCount)) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; viewModel.delete(onDeleted) }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
