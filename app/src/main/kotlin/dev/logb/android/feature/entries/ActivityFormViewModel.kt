package dev.logb.android.feature.entries

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.logb.android.core.auth.ActiveAccount
import dev.logb.android.core.db.entity.ObjectEntity
import dev.logb.android.core.domain.ActivityDraft
import dev.logb.android.core.domain.ObjectTypes
import dev.logb.android.core.domain.Validation
import dev.logb.android.core.format.Parse
import dev.logb.android.core.sync.Repositories
import dev.logb.android.navigation.ActivityForm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class ActivityFormState(
    val editing: Boolean = false,
    val obj: ObjectEntity? = null,
    val date: String = LocalDate.now().toString(),
    val category: String = "maintenance",
    val title: String = "",
    val notes: String = "",
    val counter: String = "",
    val cost: String = "",
    val quantity: String = "",
    val currentCounter: Long? = null,
    val recentTitles: List<String> = emptyList(),
    val errors: Map<String, String> = emptyMap(),
    val saving: Boolean = false,
    val saved: Boolean = false,
) {
    val categories: List<String> get() = obj?.let { ObjectTypes.categoriesFor(it.type, category) } ?: ObjectTypes.CATEGORIES
    val showsQuantity: Boolean get() = category == "fuel" && obj?.let { ObjectTypes.hasFuel(it.type) && it.counterUnit != null } == true
    val counterValue: Long? get() = Parse.long(counter)
    /** The reading typed is lower than the newest one the object has: a typo more often than not. */
    val counterLowerThanCurrent: Boolean get() = counterValue != null && currentCounter != null && counterValue!! < currentCounter
    val draft: ActivityDraft get() = ActivityDraft(date, category, title, notes, counterValue, Parse.cents(cost), if (showsQuantity) Parse.milli(quantity) else null)
    val suggestions: List<String> get() = if (title.length < 1) emptyList() else recentTitles.filter { it.contains(title, ignoreCase = true) && it != title }.take(5)
}

@HiltViewModel
class ActivityFormViewModel @Inject constructor(accounts: ActiveAccount, private val repos: Repositories, savedState: SavedStateHandle) : ViewModel() {
    private val route: ActivityForm = savedState.toRoute()
    private val db = accounts.db
    private val _state = MutableStateFlow(ActivityFormState(editing = route.uuid != null))
    val state: StateFlow<ActivityFormState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val obj = db.objectDao().get(route.objectUuid)
            val stats = db.objectDao().stats(route.objectUuid)
            val existing = route.uuid?.let { db.activityDao().get(it) }
            val titles = db.activityDao().recentTitles(route.objectUuid)
            _state.update { s ->
                val base = s.copy(obj = obj, currentCounter = stats.currentCounter, recentTitles = titles)
                if (existing != null) base.copy(
                    date = existing.date, category = existing.category, title = existing.title, notes = existing.notes,
                    counter = existing.counterValue?.toString() ?: "", cost = Parse.centsToText(existing.costCents), quantity = Parse.milliToText(existing.quantityMilli),
                    // Editing: the "lower than current" warning compares against the other entries, not this one.
                    currentCounter = db.activityDao().readings(route.objectUuid).filter { it.uuid != existing.uuid }.maxOfOrNull { it.counterValue ?: 0 },
                )
                else base.copy(
                    category = route.category?.takeIf { it in base.categories } ?: base.categories.first(),
                    title = route.title ?: "",
                )
            }
        }
    }

    fun onDate(v: String?) = _state.update { it.copy(date = v ?: LocalDate.now().toString(), errors = it.errors - "date") }
    fun onCategory(v: String) = _state.update { it.copy(category = v, errors = it.errors - "category") }
    fun onTitle(v: String) = _state.update { it.copy(title = v, errors = it.errors - "title") }
    fun onNotes(v: String) = _state.update { it.copy(notes = v) }
    fun onCounter(v: String) = _state.update { it.copy(counter = v, errors = it.errors - "counterValue") }
    fun onCost(v: String) = _state.update { it.copy(cost = v, errors = it.errors - "costCents") }
    fun onQuantity(v: String) = _state.update { it.copy(quantity = v, errors = it.errors - "quantityMilli") }

    fun save() {
        val s = _state.value
        val obj = s.obj ?: return
        val errors = Validation.activityDraft(s.draft, obj)
        if (errors.isNotEmpty()) { _state.update { it.copy(errors = errors) }; return }
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            if (route.uuid != null) {
                repos.activityRepository.update(route.uuid, s.draft)
            } else {
                val created = repos.activityRepository.create(route.objectUuid, s.draft)
                // Opened from a reminder's "log an entry now": the entry is what did the job.
                route.doneReminderUuid?.let { repos.reminderRepository.done(it, created) }
            }
            _state.update { it.copy(saving = false, saved = true) }
        }
    }

    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        route.uuid?.let { repos.activityRepository.delete(it) }
        onDone()
    }
}
