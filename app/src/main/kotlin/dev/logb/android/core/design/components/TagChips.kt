package dev.logb.android.core.design.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.logb.android.R
import dev.logb.android.core.design.TagPalette
import dev.logb.android.core.design.theme.LocalDarkTheme
import dev.logb.android.core.domain.Tags

/** A row of tag chips in their shared colours; with [onSelect], tapping one filters by it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagChips(tags: List<String>, modifier: Modifier = Modifier, onSelect: ((String) -> Unit)? = null, active: String? = null) {
    if (tags.isEmpty()) return
    val dark = LocalDarkTheme.current
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        tags.forEach { tag ->
            val (bg, fg) = TagPalette.colors(Tags.colorIndex(tag), dark)
            val isActive = active != null && Tags.fold(active) == Tags.fold(tag)
            Surface(
                color = bg, contentColor = fg, shape = RoundedCornerShape(12.dp),
                border = if (isActive) BorderStroke(2.dp, fg) else null,
                modifier = Modifier
                    .semantics { if (onSelect != null) selected = isActive }
                    .then(if (onSelect != null) Modifier.clickable(role = Role.Button) { onSelect(tag) } else Modifier),
            ) { Text(tag, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) }
        }
    }
}

/** "Filtering by <tag> · Clear", shown above a list while a tag filter is active. */
@Composable
fun TagFilterRow(tag: String, onClear: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.tags_filter, tag), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onClear) { Text(stringResource(R.string.tags_clear)) }
    }
}
