package dev.logb.android.feature.reminders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.logb.android.R
import dev.logb.android.core.auth.ActiveAccount
import dev.logb.android.core.db.entity.ObjectEntity
import dev.logb.android.core.design.components.LogbTopBar
import dev.logb.android.core.design.components.errorText
import dev.logb.android.core.domain.ReminderDraft
import dev.logb.android.core.domain.ReminderRules
import dev.logb.android.core.domain.Validation
import dev.logb.android.core.format.Parse
import dev.logb.android.core.sync.Repositories
import dev.logb.android.feature.objects.DateField
import dev.logb.android.navigation.ReminderForm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ReminderFormState(
    val editing: Boolean = false,
    val obj: ObjectEntity? = null,
    val kind: String = ReminderRules.KIND_SERVICE,
    val title: String = "",
    val notes: String = "",
    val dueDate: String? = null,
    val dueCounter: String = "",
    val repeatMonths: String = "",
    val repeatCounter: String = "",
    val everyN: String = "1",
    val everyUnit: String = "month",
    val errors: Map<String, String> = emptyMap(),
    val saved: Boolean = false,
) {
    val draft: ReminderDraft
        get() = if (kind == ReminderRules.KIND_READING) ReminderDraft(title, notes, dueDate, kind = kind, everyN = Parse.long(everyN), everyUnit = everyUnit)
        else ReminderDraft(title, notes, dueDate, Parse.long(dueCounter), Parse.long(repeatMonths), Parse.long(repeatCounter))
}

@HiltViewModel
class ReminderFormViewModel @Inject constructor(accounts: ActiveAccount, private val repos: Repositories, savedState: SavedStateHandle) : ViewModel() {
    private val route: ReminderForm = savedState.toRoute()
    private val db = accounts.db
    private val _state = MutableStateFlow(ReminderFormState(editing = route.uuid != null, kind = route.kind ?: ReminderRules.KIND_SERVICE))
    val state: StateFlow<ReminderFormState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val obj = db.objectDao().get(route.objectUuid)
            val existing = route.uuid?.let { db.reminderDao().get(it) }
            _state.update { s ->
                if (existing == null) s.copy(obj = obj)
                else s.copy(
                    obj = obj, kind = existing.kind, title = existing.title, notes = existing.notes, dueDate = existing.dueDate,
                    dueCounter = existing.dueCounter?.toString() ?: "", repeatMonths = existing.repeatMonths?.toString() ?: "", repeatCounter = existing.repeatCounter?.toString() ?: "",
                    everyN = existing.everyN?.toString() ?: "1", everyUnit = existing.everyUnit ?: "month",
                )
            }
        }
    }

    fun onKind(v: String) = _state.update { it.copy(kind = v, errors = emptyMap()) }
    fun onTitle(v: String) = _state.update { it.copy(title = v, errors = it.errors - "title") }
    fun onNotes(v: String) = _state.update { it.copy(notes = v) }
    fun onDueDate(v: String?) = _state.update { it.copy(dueDate = v, errors = it.errors - "dueDate") }
    fun onDueCounter(v: String) = _state.update { it.copy(dueCounter = v, errors = it.errors - "dueCounter") }
    fun onRepeatMonths(v: String) = _state.update { it.copy(repeatMonths = v, errors = it.errors - "repeatMonths") }
    fun onRepeatCounter(v: String) = _state.update { it.copy(repeatCounter = v, errors = it.errors - "repeatCounter") }
    fun onEveryN(v: String) = _state.update { it.copy(everyN = v, errors = it.errors - "everyN") }
    fun onEveryUnit(v: String) = _state.update { it.copy(everyUnit = v, errors = it.errors - "everyN") }

    fun save() {
        val s = _state.value
        val obj = s.obj ?: return
        val errors = Validation.reminderDraft(s.draft, obj)
        if (errors.isNotEmpty()) { _state.update { it.copy(errors = errors) }; return }
        viewModelScope.launch {
            if (route.uuid != null) repos.reminderRepository.update(route.uuid, s.draft) else repos.reminderRepository.create(route.objectUuid, s.draft)
            _state.update { it.copy(saved = true) }
        }
    }

    fun delete(onDone: () -> Unit) = viewModelScope.launch { route.uuid?.let { repos.reminderRepository.delete(it) }; onDone() }
}

@Composable
fun ReminderFormScreen(onBack: () -> Unit, viewModel: ReminderFormViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.saved) { if (state.saved) onBack() }
    val unit = state.obj?.counterUnit
    Column(Modifier.fillMaxSize()) {
        LogbTopBar(
            title = stringResource(if (state.editing) R.string.reminder_edit else R.string.reminder_new),
            onBack = onBack,
            actions = {
                if (state.editing) IconButton(onClick = { viewModel.delete(onBack) }) { Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.delete)) }
                TextButton(onClick = viewModel::save) { Text(stringResource(R.string.save)) }
            },
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!state.editing && unit != null) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(selected = state.kind == ReminderRules.KIND_SERVICE, onClick = { viewModel.onKind(ReminderRules.KIND_SERVICE) }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text(stringResource(R.string.reminder_kind_service)) }
                    SegmentedButton(selected = state.kind == ReminderRules.KIND_READING, onClick = { viewModel.onKind(ReminderRules.KIND_READING) }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text(stringResource(R.string.reminder_kind_reading)) }
                }
            }
            errorText(state.errors["kind"])?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedTextField(state.title, viewModel::onTitle, label = { Text(stringResource(R.string.field_title)) }, singleLine = true, isError = "title" in state.errors, supportingText = errorText(state.errors["title"])?.let { { Text(it) } }, modifier = Modifier.fillMaxWidth())
            if (state.kind == ReminderRules.KIND_READING) {
                DateField(stringResource(R.string.field_start_date), state.dueDate, viewModel::onDueDate, error = errorText(state.errors["dueDate"]))
                Text(stringResource(R.string.field_every), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(state.everyN, viewModel::onEveryN, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = "everyN" in state.errors, supportingText = errorText(state.errors["everyN"])?.let { { Text(it) } }, modifier = Modifier.weight(1f))
                    FilterChip(selected = state.everyUnit == "week", onClick = { viewModel.onEveryUnit("week") }, label = { Text(stringResource(R.string.unit_weeks)) })
                    FilterChip(selected = state.everyUnit == "month", onClick = { viewModel.onEveryUnit("month") }, label = { Text(stringResource(R.string.unit_months)) })
                }
            } else {
                DateField(stringResource(R.string.field_due_date), state.dueDate, viewModel::onDueDate, error = errorText(state.errors["dueDate"]))
                if (unit != null) {
                    OutlinedTextField(state.dueCounter, viewModel::onDueCounter, label = { Text(stringResource(R.string.field_due_counter, unit)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = "dueCounter" in state.errors, supportingText = errorText(state.errors["dueCounter"])?.let { { Text(it) } }, modifier = Modifier.fillMaxWidth())
                }
                Text(stringResource(R.string.repeat_title), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(state.repeatMonths, viewModel::onRepeatMonths, label = { Text(stringResource(R.string.field_repeat_months)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = "repeatMonths" in state.errors, supportingText = errorText(state.errors["repeatMonths"])?.let { { Text(it) } }, modifier = Modifier.weight(1f))
                    if (unit != null) OutlinedTextField(state.repeatCounter, viewModel::onRepeatCounter, label = { Text(stringResource(R.string.field_repeat_counter, unit)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = "repeatCounter" in state.errors, supportingText = errorText(state.errors["repeatCounter"])?.let { { Text(it) } }, modifier = Modifier.weight(1f))
                }
            }
            OutlinedTextField(state.notes, viewModel::onNotes, label = { Text(stringResource(R.string.field_notes)) }, minLines = 2, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Button(onClick = viewModel::save, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.save)) }
            Spacer(Modifier.height(48.dp))
        }
    }
}
