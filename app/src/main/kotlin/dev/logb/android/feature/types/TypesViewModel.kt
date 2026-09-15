package dev.logb.android.feature.types

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.logb.android.core.db.LogbDatabase
import dev.logb.android.core.db.entity.ObjectTypeEntity
import dev.logb.android.core.domain.CustomTypes
import dev.logb.android.core.domain.ObjectTypes
import dev.logb.android.core.domain.TypeInput
import dev.logb.android.core.auth.ActiveAccount
import dev.logb.android.core.sync.Repositories
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The form being edited: `uuid` null for a new type. */
data class TypeForm(val uuid: String?, val name: String, val icon: String, val categories: List<String>, val counterUnit: String?, val error: String? = null)

data class TypeRow(val type: ObjectTypeEntity, val usage: Int)

data class TypesUiState(val rows: List<TypeRow> = emptyList(), val form: TypeForm? = null, val deleteError: Pair<String, String>? = null)

/** Pure enough to test on an in-memory mirror: the type rows and their usage counts, from Room flows. */
class TypesModel(private val db: LogbDatabase) {
    // Objects change independently of the type rows themselves (a new one created, retyped, or
    // deleted); combining their flow too keeps "Used by N" from going stale without it.
    @OptIn(ExperimentalCoroutinesApi::class)
    fun rows(): Flow<List<TypeRow>> = db.objectTypeDao().live().flatMapLatest { types ->
        db.objectDao().all().map { types.map { t -> TypeRow(t, db.objectTypeDao().usage(CustomTypes.key(t.uuid))) } }
    }
}

@HiltViewModel
class TypesViewModel @Inject constructor(accounts: ActiveAccount, private val repos: Repositories) : ViewModel() {
    private val model = TypesModel(accounts.db)
    private val repo get() = repos.objectTypeRepository
    private val form = MutableStateFlow<TypeForm?>(null)
    private val deleteError = MutableStateFlow<Pair<String, String>?>(null)

    val state: StateFlow<TypesUiState> = combine(model.rows(), form, deleteError) { types, f, d ->
        TypesUiState(types, f, d)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TypesUiState())

    fun newType() { deleteError.value = null; form.value = TypeForm(null, "", "object", listOf("maintenance", "repair", "inspection", "purchase", "other"), null) }

    fun edit(t: ObjectTypeEntity) { deleteError.value = null; form.value = TypeForm(t.uuid, t.name, t.icon, CustomTypes.categoriesFromJson(t.categories), t.counterUnit) }

    fun change(f: TypeForm) { form.value = f.copy(error = null) }

    fun cancel() { form.value = null }

    fun save() = viewModelScope.launch {
        val f = form.value ?: return@launch
        // In CATEGORIES order whatever order they were ticked in; `other` always (the web's Types.svelte).
        val input = TypeInput(f.name, f.icon, ObjectTypes.CATEGORIES.filter { it == "other" || it in f.categories }, f.counterUnit)
        when (val r = if (f.uuid == null) repo.create(input) else repo.update(f.uuid, input)) {
            is TypeSave.Saved -> form.value = null
            is TypeSave.Refused -> form.value = f.copy(error = r.code)
        }
    }

    fun delete(t: ObjectTypeEntity) = viewModelScope.launch {
        when (val r = repo.delete(t.uuid)) {
            is TypeSave.Saved -> { deleteError.value = null; if (form.value?.uuid == t.uuid) form.value = null }
            is TypeSave.Refused -> deleteError.value = t.uuid to r.code
        }
    }
}
