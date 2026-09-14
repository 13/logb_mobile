package dev.logb.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.logb.android.core.auth.Session
import dev.logb.android.core.auth.SessionRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Restores the session once at start-up and exposes it to the root composable. */
@HiltViewModel
class RootViewModel @Inject constructor(private val sessions: SessionRepository) : ViewModel() {
    val session: StateFlow<Session> = sessions.session

    init {
        viewModelScope.launch { sessions.restore() }
    }

    fun signOut() = viewModelScope.launch { sessions.signOut() }
}
