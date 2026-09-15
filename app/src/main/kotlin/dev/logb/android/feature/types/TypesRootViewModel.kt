package dev.logb.android.feature.types

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.logb.android.core.auth.ActiveAccount
import dev.logb.android.core.domain.TypeRegistry
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** The signed-in account's own types, as a [TypeRegistry], provided once at the navigation root. */
@HiltViewModel
class TypesRootViewModel @Inject constructor(accounts: ActiveAccount) : ViewModel() {
    val registry: StateFlow<TypeRegistry> = accounts.db.objectTypeDao().live()
        .map(::TypeRegistry)
        .stateIn(viewModelScope, SharingStarted.Eagerly, TypeRegistry.EMPTY)
}
