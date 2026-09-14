package dev.logb.android.core.design.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.logb.android.core.design.theme.figure

/** A label over a figure: "Spent" over "€698.50". The figure is set in tabular numerals. */
@Composable
fun StatFigure(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.figure, color = MaterialTheme.colorScheme.onSurface)
    }
}
