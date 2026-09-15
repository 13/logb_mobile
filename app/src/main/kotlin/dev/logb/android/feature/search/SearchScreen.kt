package dev.logb.android.feature.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.logb.android.R
import dev.logb.android.core.auth.ActiveAccount
import dev.logb.android.core.db.LogbDatabase
import dev.logb.android.core.db.dao.ObjectHit
import dev.logb.android.core.db.dao.SearchDao
import dev.logb.android.core.db.entity.ActivityEntity
import dev.logb.android.core.design.components.EmptyState
import dev.logb.android.core.design.components.LogbTopBar
import dev.logb.android.core.design.components.ObjectTypeIcon
import dev.logb.android.core.format.currentLocale
import dev.logb.android.core.format.formatDate
import dev.logb.android.feature.objects.tabs.categoryLabel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class SearchResults(val query: String = "", val objects: List<ObjectHit> = emptyList(), val entries: List<Pair<ActivityEntity, String>> = emptyList())

/** Local, as you type; entries carry their object's name so a hit out of context is not a mystery. */
class SearchModel(private val db: LogbDatabase) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun results(queries: Flow<String>): Flow<SearchResults> = queries.mapLatest { q ->
        if (q.isBlank()) return@mapLatest SearchResults(q)
        val pattern = SearchDao.likePattern(q)
        val tagsPattern = SearchDao.tagsPattern(q)
        val entries = db.searchDao().activities(pattern, tagsPattern).map { it to (db.objectDao().get(it.objectUuid)?.name ?: "") }
        SearchResults(q, db.searchDao().objects(pattern, tagsPattern), entries)
    }
}

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(accounts: ActiveAccount) : ViewModel() {
    private val query = MutableStateFlow("")
    val queryText: StateFlow<String> = query
    val results: StateFlow<SearchResults> = SearchModel(accounts.db).results(query.debounce(150).distinctUntilChanged())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchResults())

    fun onQueryChange(q: String) { query.value = q }
}

@Composable
fun SearchScreen(onOpenObject: (String) -> Unit, viewModel: SearchViewModel = hiltViewModel()) {
    val query by viewModel.queryText.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val locale = currentLocale()
    Column(Modifier.fillMaxSize()) {
        LogbTopBar(title = stringResource(R.string.nav_search))
        OutlinedTextField(
            value = query,
            onValueChange = viewModel::onQueryChange,
            placeholder = { Text(stringResource(R.string.search_hint)) },
            singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { viewModel.onQueryChange("") }) { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.clear)) }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        when {
            results.query.isBlank() -> EmptyState(rememberVectorPainter(Icons.Outlined.Search), stringResource(R.string.search_title), stringResource(R.string.search_body))
            results.objects.isEmpty() && results.entries.isEmpty() -> EmptyState(rememberVectorPainter(Icons.Outlined.Search), stringResource(R.string.search_nothing), stringResource(R.string.search_nothing_body))
            else -> LazyColumn(contentPadding = PaddingValues(bottom = 16.dp), modifier = Modifier.fillMaxSize()) {
                if (results.objects.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.nav_objects)) }
                    items(results.objects, key = { "o" + it.uuid }) { hit ->
                        ListItem(
                            headlineContent = { Text(hit.name) },
                            supportingContent = {
                                val parts = listOfNotNull(hit.parentName?.let { stringResource(R.string.search_in, it) }, if (hit.archivedAt != null) stringResource(R.string.stats_archived) else null)
                                if (parts.isNotEmpty()) Text(parts.joinToString(" · "))
                            },
                            leadingContent = { ObjectTypeIcon(hit.type, tint = MaterialTheme.colorScheme.primary) },
                            modifier = Modifier.clickable { onOpenObject(hit.uuid) },
                        )
                    }
                }
                if (results.entries.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.search_entries)) }
                    items(results.entries, key = { "a" + it.first.uuid }) { (a, objectName) ->
                        ListItem(
                            headlineContent = { Text(a.title) },
                            supportingContent = { Text("${formatDate(a.date, locale)} · ${categoryLabel(a.category)} · $objectName") },
                            modifier = Modifier.clickable { onOpenObject(a.objectUuid) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}
