package dev.logb.android.feature.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.logb.android.core.design.theme.figureLabel

/**
 * One row of a [BarList]: a label, a value the bar is proportional to, and the figure to print.
 * `depth` indents a tree; `expanded` (non-null) draws a chevron calling `onToggle`; `note` follows
 * the label in muted type ("archived"); `onLabel` makes the label a link.
 */
data class Bar(
    val key: String,
    val label: String,
    val value: Long,
    val display: String,
    val depth: Int = 0,
    val note: String? = null,
    val expanded: Boolean? = null,
    val onToggle: (() -> Unit)? = null,
    val onLabel: (() -> Unit)? = null,
)

/** The web's `BarList`: label · track · figure, the widest bar filling its track. Drawn with boxes, no chart library. */
@Composable
fun BarList(items: List<Bar>, modifier: Modifier = Modifier, labelWidth: androidx.compose.ui.unit.Dp = 96.dp) {
    val max = items.maxOfOrNull { it.value }?.takeIf { it > 0 } ?: 0L
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { bar ->
            Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.width(labelWidth + (bar.depth * 16).dp).padding(start = (bar.depth * 16).dp), verticalAlignment = Alignment.CenterVertically) {
                    if (bar.expanded != null) {
                        IconButton(onClick = { bar.onToggle?.invoke() }, modifier = Modifier.size(24.dp)) {
                            Icon(if (bar.expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
                        }
                    }
                    Row(Modifier.then(if (bar.onLabel != null) Modifier.clickable { bar.onLabel.invoke() } else Modifier), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            bar.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (bar.onLabel != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        bar.note?.let { Text(" · $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
                    }
                }
                Box(Modifier.weight(1f).padding(horizontal = 8.dp).height(14.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.extraSmall)) {
                    val fraction = if (max == 0L) 0f else (bar.value.toDouble() / max).toFloat().coerceIn(0f, 1f)
                    if (fraction > 0f) Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.extraSmall))
                }
                Text(bar.display, style = MaterialTheme.typography.figureLabel, maxLines = 1, modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}
