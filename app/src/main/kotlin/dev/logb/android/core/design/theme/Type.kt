package dev.logb.android.core.design.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * The platform sans, unchanged. Figures are the content of a logbook — costs and counter
 * readings are read down a column — so they get their own styles with tabular numerals.
 */
val LogbTypography = Typography()

/** Tabular figures: every digit the same width, so a column of costs lines up. */
private fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")

/** A stat value: total spent, current counter. */
val Typography.figure: TextStyle
    get() = titleLarge.tabular().copy(fontWeight = FontWeight.SemiBold)

/** A figure inside a row: an entry's cost or counter. */
val Typography.figureSmall: TextStyle
    get() = bodyLarge.tabular()

/** A date or a small number beside text. */
val Typography.figureLabel: TextStyle
    get() = bodyMedium.tabular()
