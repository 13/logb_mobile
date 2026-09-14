package dev.logb.android.feature.objects.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.logb.android.R
import dev.logb.android.core.db.entity.ActivityEntity
import dev.logb.android.core.design.components.EmptyState
import dev.logb.android.core.design.theme.figureLabel
import dev.logb.android.core.design.theme.figureSmall
import dev.logb.android.core.domain.TimelineFold
import dev.logb.android.core.domain.TimelineRow
import dev.logb.android.core.format.currentLocale
import dev.logb.android.core.format.formatCents
import dev.logb.android.core.format.formatCounter
import dev.logb.android.core.format.formatDate
import dev.logb.android.feature.objects.ObjectDetailUiState

@Composable
fun categoryLabel(category: String): String = stringResource(
    when (category) {
        "maintenance" -> R.string.cat_maintenance; "repair" -> R.string.cat_repair; "purchase" -> R.string.cat_purchase
        "inspection" -> R.string.cat_inspection; "modification" -> R.string.cat_modification; "fuel" -> R.string.cat_fuel
        "symptom" -> R.string.cat_symptom; "treatment" -> R.string.cat_treatment; "appointment" -> R.string.cat_appointment
        "medication" -> R.string.cat_medication; "reading" -> R.string.cat_reading; else -> R.string.cat_other
    },
)

@Composable
fun TimelineTab(state: ObjectDetailUiState, onFilter: (String?) -> Unit, thumbUrl: (Long?) -> String?, onEntry: (String) -> Unit = {}) {
    val expanded = remember { mutableStateOf(setOf<String>()) }
    val unit = state.obj?.counterUnit
    Column(Modifier.fillMaxSize()) {
        if (state.categories.size > 1) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.categories.forEach { c ->
                    FilterChip(selected = state.categoryFilter == c, onClick = { onFilter(c) }, label = { Text(categoryLabel(c)) })
                }
            }
        }
        if (state.loaded && state.years.isEmpty()) {
            EmptyState(
                icon = androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Outlined.Timeline),
                title = stringResource(R.string.timeline_empty), body = stringResource(R.string.timeline_empty_body),
            )
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), modifier = Modifier.fillMaxSize()) {
            state.years.forEach { group ->
                item(key = "y${group.year}") {
                    Text(group.year, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                }
                items(group.rows, key = { row -> when (row) { is TimelineRow.Entry -> row.activity.uuid; is TimelineRow.Readings -> row.key } }) { row ->
                    when (row) {
                        is TimelineRow.Entry -> EntryRow(row.activity, unit, state.currency, state.attachmentsByActivity[row.activity.uuid].orEmpty().map { thumbUrl(it.file.serverId) to it.file.mime }, onClick = { onEntry(row.activity.uuid) })
                        is TimelineRow.Readings -> ReadingsRow(row, unit, isOpen = row.key in expanded.value, onToggle = { expanded.value = if (row.key in expanded.value) expanded.value - row.key else expanded.value + row.key }, currency = state.currency, onEntry = onEntry)
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryRow(a: ActivityEntity, unit: String?, currency: String, thumbs: List<Pair<String?, String>>, onClick: () -> Unit = {}) {
    val locale = currentLocale()
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatDate(a.date, locale), style = MaterialTheme.typography.figureLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(96.dp))
            AssistChip(onClick = {}, label = { Text(categoryLabel(a.category), style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.height(24.dp))
        }
        Text(a.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 2.dp))
        val figures = listOfNotNull(a.costCents?.let { formatCents(it, currency, locale) }, a.counterValue?.let { formatCounter(it, unit, locale) })
        if (figures.isNotEmpty()) Text(figures.joinToString(" · "), style = MaterialTheme.typography.figureSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (a.notes.isNotBlank()) Text(a.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        val photos = thumbs.filter { it.second.startsWith("image/") && it.first != null }
        if (photos.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                items(photos) { (url, _) ->
                    AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceVariant))
                }
            }
        }
    }
}

@Composable
private fun ReadingsRow(row: TimelineRow.Readings, unit: String?, isOpen: Boolean, onToggle: () -> Unit, currency: String, onEntry: (String) -> Unit = {}) {
    val locale = currentLocale()
    val span = TimelineFold.readingSpan(row.readings)
    Column(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatDate(row.readings.last().date, locale) + " – " + formatDate(row.readings.first().date, locale), style = MaterialTheme.typography.figureLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(pluralStringResource(R.plurals.readings_folded, row.readings.size, row.readings.size), style = MaterialTheme.typography.bodyLarge)
        if (span != null) Text("${formatCounter(span.first, unit, locale)} → ${formatCounter(span.second, unit, locale)}", style = MaterialTheme.typography.figureSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (isOpen) {
            Spacer(Modifier.height(4.dp))
            row.readings.forEach { r -> EntryRow(r, unit, currency, emptyList(), onClick = { onEntry(r.uuid) }) }
        }
    }
}
