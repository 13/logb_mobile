package dev.logb.android.core.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.logb.android.R
import dev.logb.android.core.design.theme.LocalWarnColor
import dev.logb.android.core.design.theme.figureLabel

/** "2 due" as an amber pill. Renders nothing at zero: a badge that says "0 due" is noise. */
@Composable
fun DueBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    val text = pluralStringResource(R.plurals.due_count, count, count)
    Text(
        text,
        style = MaterialTheme.typography.figureLabel,
        color = Color.White,
        modifier = modifier
            .semantics { contentDescription = text }
            .background(LocalWarnColor.current, MaterialTheme.shapes.extraLarge)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}
