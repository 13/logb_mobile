package dev.logb.android.feature.types

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.logb.android.R
import dev.logb.android.core.db.entity.ObjectTypeEntity
import dev.logb.android.core.design.LogbIcons
import dev.logb.android.core.design.components.LogbTopBar
import dev.logb.android.core.design.components.ObjectTypeIcon
import dev.logb.android.core.domain.CustomTypes
import dev.logb.android.core.domain.ObjectTypes
import dev.logb.android.feature.objects.tabs.categoryLabel
import dev.logb.android.feature.objects.typeLabelRes

/** Settings › Types: this account's own object types -- list, add, edit, delete -- plus the built-in ones. */
@Composable
fun TypesScreen(onBack: () -> Unit, viewModel: TypesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TypesContent(
        state = state,
        onEdit = viewModel::edit,
        onNew = viewModel::newType,
        onSave = viewModel::save,
        onCancel = viewModel::cancel,
        onDelete = viewModel::delete,
        onFormChange = viewModel::change,
        onBack = onBack,
    )
}

/** The Types page without its view model. */
@Composable
fun TypesContent(
    state: TypesUiState,
    onEdit: (ObjectTypeEntity) -> Unit,
    onNew: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onDelete: (ObjectTypeEntity) -> Unit,
    onFormChange: (TypeForm) -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        LogbTopBar(stringResource(R.string.settings_types), onBack)
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.types_yours), style = MaterialTheme.typography.titleSmall)
            state.rows.forEach { row ->
                val form = state.form
                if (form != null && form.uuid == row.type.uuid) {
                    TypeFormCard(form, onSave, onCancel, onFormChange)
                } else {
                    TypeRowCard(row, state.deleteError, onEdit = { onEdit(row.type) }, onDelete = { onDelete(row.type) })
                }
            }
            val newForm = state.form?.takeIf { it.uuid == null }
            if (state.rows.isEmpty() && newForm == null) {
                Text(stringResource(R.string.types_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (newForm != null) {
                TypeFormCard(newForm, onSave, onCancel, onFormChange)
            } else if (state.form == null) {
                TextButton(onClick = onNew) { Text(stringResource(R.string.types_add)) }
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.types_built_in), style = MaterialTheme.typography.titleSmall)
            ObjectTypes.ALL.forEach { t ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    ObjectTypeIcon(t, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(typeLabelRes(t)))
                }
            }
        }
    }
}

@Composable
private fun TypeRowCard(row: TypeRow, deleteError: Pair<String, String>?, onEdit: () -> Unit, onDelete: () -> Unit) {
    val type = row.type
    val editDescription = stringResource(R.string.types_edit_named, type.name)
    val deleteDescription = stringResource(R.string.types_delete_named, type.name)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(LogbIcons.forIcon(type.icon)), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(type.name, style = MaterialTheme.typography.bodyLarge)
                    val categories = CustomTypes.categoriesFromJson(type.categories).map { categoryLabel(it) }.joinToString(", ")
                    val summary = listOfNotNull(type.counterUnit, categories.ifBlank { null }).joinToString(" · ")
                    if (summary.isNotBlank()) Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onEdit, modifier = Modifier.clearAndSetSemantics { contentDescription = editDescription }) {
                    Text(stringResource(R.string.edit))
                }
                TextButton(onClick = onDelete, modifier = Modifier.clearAndSetSemantics { contentDescription = deleteDescription }) {
                    Text(stringResource(R.string.types_delete))
                }
            }
            if (deleteError?.first == type.uuid) {
                Text(pluralStringResource(R.plurals.types_in_use, row.usage, row.usage), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TypeFormCard(form: TypeForm, onSave: () -> Unit, onCancel: () -> Unit, onFormChange: (TypeForm) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = form.name,
                onValueChange = { onFormChange(form.copy(name = it.take(CustomTypes.MAX_NAME_CHARS))) },
                label = { Text(stringResource(R.string.types_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.types_icon), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                CustomTypes.ICONS.forEach { i ->
                    val label = iconLabel(i)
                    IconToggleButton(checked = form.icon == i, onCheckedChange = { onFormChange(form.copy(icon = i)) }) {
                        Icon(painterResource(LogbIcons.forIcon(i)), contentDescription = label)
                    }
                }
            }
            Text(stringResource(R.string.types_categories), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ObjectTypes.CATEGORIES.forEach { c ->
                    val checked = c == "other" || c in form.categories
                    Row(
                        Modifier.toggleable(
                            value = checked, enabled = c != "other", role = Role.Checkbox,
                            onValueChange = { on -> onFormChange(form.copy(categories = if (on) form.categories + c else form.categories - c)) },
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null, enabled = c != "other")
                        Text(categoryLabel(c))
                    }
                }
            }
            Text(stringResource(R.string.types_unit), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf<String?>(null) + CustomTypes.UNITS).forEach { u ->
                    FilterChip(selected = form.counterUnit == u, onClick = { onFormChange(form.copy(counterUnit = u)) }, label = { Text(u ?: stringResource(R.string.types_unit_none)) })
                }
            }
            form.error?.let { code -> Text(typeErrorText(code), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onSave, enabled = form.name.isNotBlank()) { Text(stringResource(R.string.types_save)) }
                TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            }
        }
    }
}

/** The web's `ICON_LABEL`: built-in labels for the icons that are also a type glyph; a word of their own for the rest. */
@Composable
private fun iconLabel(icon: String): String = when (icon) {
    "car" -> stringResource(R.string.type_car)
    "e-bike" -> stringResource(R.string.type_e_bike)
    "bike" -> stringResource(R.string.type_bike)
    "motorcycle" -> stringResource(R.string.type_motorcycle)
    "home" -> stringResource(R.string.type_home)
    "appliance" -> stringResource(R.string.type_appliance)
    "tool" -> stringResource(R.string.type_tool)
    "body" -> stringResource(R.string.type_body)
    "document" -> stringResource(R.string.icon_document)
    "camera" -> stringResource(R.string.icon_camera)
    else -> stringResource(R.string.icon_object)
}

/** The server's stable refusal codes, said in the reader's language; an unknown code shows itself. */
@Composable
private fun typeErrorText(code: String): String = when (code) {
    "name_taken" -> stringResource(R.string.types_error_name_taken)
    "name_invalid" -> stringResource(R.string.types_error_name_invalid)
    "icon_invalid" -> stringResource(R.string.types_error_icon_invalid)
    "categories_invalid" -> stringResource(R.string.types_error_categories_invalid)
    "unit_invalid" -> stringResource(R.string.types_error_unit_invalid)
    else -> code
}
