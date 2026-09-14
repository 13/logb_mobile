package dev.logb.android.core.design.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = LogbPalette.Teal,
    onPrimary = LogbPalette.OnTeal,
    primaryContainer = LogbPalette.Surface2,
    onPrimaryContainer = LogbPalette.Teal,
    secondary = LogbPalette.Teal,
    onSecondary = LogbPalette.OnTeal,
    secondaryContainer = LogbPalette.Surface2,
    onSecondaryContainer = LogbPalette.Text,
    tertiary = LogbPalette.Warn,
    background = LogbPalette.Bg,
    onBackground = LogbPalette.Text,
    surface = LogbPalette.Surface,
    onSurface = LogbPalette.Text,
    surfaceVariant = LogbPalette.Surface2,
    onSurfaceVariant = LogbPalette.Muted,
    surfaceContainer = LogbPalette.Surface,
    surfaceContainerLow = LogbPalette.Bg,
    surfaceContainerHigh = LogbPalette.Surface2,
    outline = LogbPalette.Border,
    outlineVariant = LogbPalette.Border,
    error = LogbPalette.Danger,
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = LogbPalette.TealDark,
    onPrimary = LogbPalette.OnTealDark,
    primaryContainer = LogbPalette.Surface2Dark,
    onPrimaryContainer = LogbPalette.TealDark,
    secondary = LogbPalette.TealDark,
    onSecondary = LogbPalette.OnTealDark,
    secondaryContainer = LogbPalette.Surface2Dark,
    onSecondaryContainer = LogbPalette.TextDark,
    tertiary = LogbPalette.WarnDark,
    background = LogbPalette.BgDark,
    onBackground = LogbPalette.TextDark,
    surface = LogbPalette.SurfaceDark,
    onSurface = LogbPalette.TextDark,
    surfaceVariant = LogbPalette.Surface2Dark,
    onSurfaceVariant = LogbPalette.MutedDark,
    surfaceContainer = LogbPalette.SurfaceDark,
    surfaceContainerLow = LogbPalette.BgDark,
    surfaceContainerHigh = LogbPalette.Surface2Dark,
    outline = LogbPalette.BorderDark,
    outlineVariant = LogbPalette.BorderDark,
    error = LogbPalette.DangerDark,
    onError = LogbPalette.BgDark,
)

/** The "due" colour: amber, not error red. A reminder coming due is news, not a fault. */
val LocalWarnColor = staticCompositionLocalOf { LogbPalette.Warn }

@Composable
fun LogbTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    CompositionLocalProvider(LocalWarnColor provides if (darkTheme) LogbPalette.WarnDark else LogbPalette.Warn) {
        MaterialTheme(colorScheme = colors, typography = LogbTypography, shapes = LogbShapes, content = content)
    }
}
