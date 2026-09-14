package dev.logb.android.feature.entries

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import dev.logb.android.core.design.components.LogbTopBar
import dev.logb.android.core.design.components.errorText
import dev.logb.android.core.design.theme.LocalWarnColor
import dev.logb.android.core.domain.ActivityDraft
import dev.logb.android.core.domain.Validation
import dev.logb.android.core.format.Parse
import dev.logb.android.core.format.formatDate
import dev.logb.android.core.domain.ReadingChecks
import dev.logb.android.core.domain.ReadingWarning
import dev.logb.android.core.domain.ReminderRules
import dev.logb.android.feature.stats.InsightsModel
import dev.logb.android.core.format.currentLocale
import dev.logb.android.core.format.formatCounter
import dev.logb.android.core.sync.Repositories
import dev.logb.android.feature.objects.DateField
import dev.logb.android.navigation.ReadingForm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class ReadingFormState(
    val unit: String? = null,
    val date: String = LocalDate.now().toString(),
    val counter: String = "",
    val currentCounter: Long? = null,
    val lastReadingDate: String? = null,
    val ratePerDayMilli: Long? = null,
    val error: String? = null,
    val saved: Boolean = false,
) {
    val warning: ReadingWarning?
        get() {
            val value = Parse.long(counter) ?: return null
            val date = runCatching { LocalDate.parse(this.date) }.getOrNull() ?: return null
            return ReadingChecks.readingWarning(value, date, currentCounter, ReminderRules.parseDate(lastReadingDate), ratePerDayMilli)
        }
    val lower: Boolean get() = warning == ReadingWarning.Lower
}

/** A counter reading and nothing else: the quickest entry there is. */
@HiltViewModel
class ReadingFormViewModel @Inject constructor(accounts: ActiveAccount, private val repos: Repositories, savedState: SavedStateHandle) : ViewModel() {
    private val route: ReadingForm = savedState.toRoute()
    private val db = accounts.db
    private val _state = MutableStateFlow(ReadingFormState())
    val state: StateFlow<ReadingFormState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val obj = db.objectDao().get(route.objectUuid)
            val stats = db.objectDao().stats(route.objectUuid)
            val usage = InsightsModel(db).usage(route.objectUuid)
            _state.update { it.copy(unit = obj?.counterUnit, currentCounter = stats.currentCounter, lastReadingDate = stats.lastReadingDate, ratePerDayMilli = usage?.rateMilli) }
        }
    }

    fun onDate(v: String?) = _state.update { it.copy(date = v ?: LocalDate.now().toString()) }
    fun onCounter(v: String) = _state.update { it.copy(counter = v, error = null) }

    fun save(title: String) {
        val s = _state.value
        val obj = runCatching { }.let { s }
        val draft = ActivityDraft(date = s.date, category = "reading", title = title, counterValue = Parse.long(s.counter))
        viewModelScope.launch {
            val o = db.objectDao().get(route.objectUuid) ?: return@launch
            val errors = Validation.activityDraft(draft, o)
            if (errors.isNotEmpty()) { _state.update { it.copy(error = errors.values.first()) }; return@launch }
            repos.activityRepository.create(route.objectUuid, draft)
            _state.update { it.copy(saved = true) }
        }
    }
}

@Composable
fun ReadingFormScreen(onBack: () -> Unit, viewModel: ReadingFormViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.saved) { if (state.saved) onBack() }
    val locale = currentLocale()
    val title = stringResource(R.string.reading_title)
    Column(Modifier.fillMaxSize()) {
        LogbTopBar(title = stringResource(R.string.reading_new), onBack = onBack, actions = { TextButton(onClick = { viewModel.save(title) }) { Text(stringResource(R.string.save)) } })
        Column(Modifier.fillMaxSize().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                state.counter, viewModel::onCounter,
                label = { Text(stringResource(R.string.field_counter, state.unit ?: "")) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = state.error != null,
                supportingText = {
                    val err = errorText(state.error)
                    when {
                        err != null -> Text(err)
                        state.lower -> Text(stringResource(R.string.counter_lower_warning, formatCounter(state.currentCounter, state.unit, locale)), color = LocalWarnColor.current)
                        state.warning == ReadingWarning.Implausible -> Text(stringResource(R.string.reading_warn_implausible, formatDate(state.lastReadingDate ?: "", locale)), color = LocalWarnColor.current)
                        state.currentCounter != null -> Text(stringResource(R.string.counter_current_hint, formatCounter(state.currentCounter, state.unit, locale)))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            DateField(stringResource(R.string.field_date), state.date, viewModel::onDate, clearable = false)
            Button(onClick = { viewModel.save(title) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.save)) }
            Text(stringResource(R.string.reading_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
