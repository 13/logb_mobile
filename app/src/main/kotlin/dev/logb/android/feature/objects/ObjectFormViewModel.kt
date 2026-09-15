package dev.logb.android.feature.objects

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.logb.android.core.auth.ActiveAccount
import dev.logb.android.core.db.entity.ObjectEntity
import dev.logb.android.core.domain.ObjectDraft
import dev.logb.android.core.domain.ReminderDraft
import dev.logb.android.core.domain.ReminderTemplate
import dev.logb.android.core.domain.ReminderTemplates
import dev.logb.android.core.domain.TagCount
import dev.logb.android.core.domain.Tags
import dev.logb.android.core.domain.TypeRegistry
import dev.logb.android.core.domain.Validation
import dev.logb.android.core.format.Parse
import dev.logb.android.core.server.ServerCapabilities
import dev.logb.android.core.sync.Repositories
import dev.logb.android.navigation.ObjectForm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class ObjectFormState(
    val editing: Boolean = false,
    val name: String = "",
    val type: String = "car",
    val counterUnit: String? = "km",
    val fuelUnit: String? = null,
    val description: String = "",
    val purchaseDate: String? = null,
    val price: String = "",
    val parent: ObjectEntity? = null,
    val parentCandidates: List<ObjectEntity> = emptyList(),
    val tags: List<String> = emptyList(),
    val templates: List<ReminderTemplate> = emptyList(),
    val ticked: Set<String> = emptySet(),
    val currentReading: String = "",
    val errors: Map<String, String> = emptyMap(),
    val saving: Boolean = false,
    /** The uuid to navigate to once saved. */
    val savedUuid: String? = null,
    val activityCount: Int = 0,
    val registry: TypeRegistry = TypeRegistry.EMPTY,
) {
    val draft: ObjectDraft
        get() = ObjectDraft(name, type, counterUnit, fuelUnit, description, purchaseDate, Parse.cents(price), parent?.uuid, tags)

    /** A distance template is ticked: the form asks where the counter is now. */
    val asksCurrentReading: Boolean
        get() = counterUnit != null && templates.any { it.id in ticked && ReminderTemplates.counterStep(it, counterUnit) != null }
}

@HiltViewModel
class ObjectFormViewModel @Inject constructor(accounts: ActiveAccount, private val repos: Repositories, private val capabilities: ServerCapabilities, savedState: SavedStateHandle) : ViewModel() {
    private val route: ObjectForm = savedState.toRoute()
    private val db = accounts.db
    private val _state = MutableStateFlow(ObjectFormState(editing = route.uuid != null))
    val state: StateFlow<ObjectFormState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val existing = route.uuid?.let { db.objectDao().get(it) }
            val parent = (existing?.parentUuid ?: route.parentUuid)?.let { db.objectDao().get(it) }
            val candidates = repos.objectRepository.candidatesForParent(route.uuid)
            _state.update { s ->
                if (existing != null) s.copy(
                    name = existing.name, type = existing.type, counterUnit = existing.counterUnit, fuelUnit = existing.fuelUnit,
                    description = existing.description, purchaseDate = existing.purchaseDate, price = Parse.centsToText(existing.purchasePriceCents),
                    parent = parent, parentCandidates = candidates, tags = Tags.fromJson(existing.tags), activityCount = db.objectDao().stats(existing.uuid).activityCount,
                )
                else s.copy(parent = parent, parentCandidates = candidates).withTemplates()
            }
        }
        viewModelScope.launch { db.objectTypeDao().live().collect { types -> _state.update { it.copy(registry = TypeRegistry(types)) } } }
    }

    private fun ObjectFormState.withTemplates(): ObjectFormState =
        if (editing) this else copy(templates = ReminderTemplates.templatesFor(type, counterUnit), ticked = ticked.filter { id -> ReminderTemplates.templatesFor(type, counterUnit).any { it.id == id } }.toSet())

    fun onName(v: String) = _state.update { it.copy(name = v, errors = it.errors - "name") }

    /** Selecting an own type also adopts its counter unit, but only while the form still shows the default one. */
    fun onType(v: String) = _state.update {
        val own = it.registry.find(v)
        val counterUnit = if (own != null && it.counterUnit == DEFAULT_COUNTER_UNIT) own.counterUnit else it.counterUnit
        it.copy(type = v, counterUnit = counterUnit, fuelUnit = if ("fuel" in it.registry.categoriesFor(v)) it.fuelUnit else null).withTemplates()
    }
    fun onCounterUnit(v: String?) = _state.update { it.copy(counterUnit = v).withTemplates() }
    fun onFuelUnit(v: String?) = _state.update { it.copy(fuelUnit = v) }
    fun onDescription(v: String) = _state.update { it.copy(description = v) }
    fun onPurchaseDate(v: String?) = _state.update { it.copy(purchaseDate = v, errors = it.errors - "purchaseDate") }
    fun onPrice(v: String) = _state.update { it.copy(price = v, errors = it.errors - "purchasePriceCents") }
    fun onParent(v: ObjectEntity?) = _state.update { it.copy(parent = v) }
    fun toggleTemplate(id: String) = _state.update { it.copy(ticked = if (id in it.ticked) it.ticked - id else it.ticked + id) }
    fun onCurrentReading(v: String) = _state.update { it.copy(currentReading = v) }
    fun onTags(v: List<String>) = _state.update { it.copy(tags = v) }
    val tagSuggestions: StateFlow<List<TagCount>> = db.objectDao().tagColumns().map(Tags::count).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val showTags: StateFlow<Boolean> = capabilities.current.map { it.tags }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val showOwnTypes: StateFlow<Boolean> = capabilities.current.map { it.ownTypes }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** `titles` resolves each template's title in the person's language; the view supplies it. */
    fun save(titles: Map<String, String>) {
        val s = _state.value
        val errors = Validation.objectDraft(s.draft)
        if (errors.isNotEmpty()) { _state.update { it.copy(errors = errors) }; return }
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val uuid = if (route.uuid != null) {
                repos.objectRepository.update(route.uuid, s.draft); route.uuid
            } else {
                val today = LocalDate.now()
                val reading = if (s.asksCurrentReading) Parse.long(s.currentReading) else null
                val drafts: List<ReminderDraft> = s.templates.filter { it.id in s.ticked }
                    .mapNotNull { ReminderTemplates.toDraft(it, titles[it.id] ?: it.id, s.counterUnit, reading, today) }
                repos.objectRepository.create(s.draft, drafts, reading, today)
            }
            _state.update { it.copy(saving = false, savedUuid = uuid) }
        }
    }

    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        route.uuid?.let { repos.objectRepository.delete(it) }
        onDone()
    }

    private companion object {
        /** Matches [ObjectFormState]'s default: a fresh form has not been touched. */
        const val DEFAULT_COUNTER_UNIT = "km"
    }
}
