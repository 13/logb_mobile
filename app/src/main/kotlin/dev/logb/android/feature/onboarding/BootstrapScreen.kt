package dev.logb.android.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.logb.android.R
import dev.logb.android.core.auth.SessionRepository
import dev.logb.android.core.sync.SyncManager
import dev.logb.android.core.sync.SyncStatus
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BootstrapViewModel @Inject constructor(private val syncManager: SyncManager, private val sessions: SessionRepository) : ViewModel() {
    val status: StateFlow<SyncStatus> = syncManager.status

    fun start() = viewModelScope.launch { syncManager.syncNow() }

    fun signOut() = viewModelScope.launch { sessions.signOut() }
}

/** The first sync after signing in: the mirror is empty until the snapshot lands. */
@Composable
fun BootstrapScreen(viewModel: BootstrapViewModel = hiltViewModel()) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.start() }
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 400.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(96.dp))
            val failed = status is SyncStatus.Offline || status is SyncStatus.Failed
            Text(
                stringResource(if (failed) R.string.bootstrap_failed else R.string.bootstrap_title),
                style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            if (!failed) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                (status as? SyncStatus.Failed)?.let { Text(it.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) }
                Spacer(Modifier.height(8.dp))
                Button(onClick = viewModel::start, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.retry)) }
                TextButton(onClick = viewModel::signOut) { Text(stringResource(R.string.sign_out)) }
            }
        }
    }
}
