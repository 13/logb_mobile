package dev.logb.android.feature.share

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.logb.android.R
import dev.logb.android.core.auth.ActiveAccount
import dev.logb.android.core.db.entity.ObjectEntity
import dev.logb.android.core.design.components.LogbTopBar
import dev.logb.android.core.design.components.ObjectTypeIcon
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class ShareTargetViewModel @Inject constructor(accounts: ActiveAccount, val inbox: ShareInbox) : ViewModel() {
    val objects: StateFlow<List<ObjectEntity>> = accounts.db.objectDao().all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val count: Int get() = inbox.pending.value.size
}

/** A shared photo or document: which object is it about? Then the entry form, with the files attached. */
@Composable
fun ShareTargetScreen(onCancel: () -> Unit, onPick: (String) -> Unit, viewModel: ShareTargetViewModel = hiltViewModel()) {
    val objects by viewModel.objects.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    val count = viewModel.count
    Column(Modifier.fillMaxSize()) {
        LogbTopBar(title = pluralStringResource(R.plurals.share_title, count, count), onBack = { viewModel.inbox.clear(); onCancel() })
        Text(stringResource(R.string.share_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
        OutlinedTextField(query, { query = it }, placeholder = { Text(stringResource(R.string.search_hint)) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(16.dp))
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp), modifier = Modifier.fillMaxSize()) {
            items(objects.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }, key = { it.uuid }) { o ->
                ListItem(headlineContent = { Text(o.name) }, leadingContent = { ObjectTypeIcon(o.type, tint = MaterialTheme.colorScheme.primary) }, modifier = Modifier.clickable { onPick(o.uuid) })
            }
        }
    }
}
