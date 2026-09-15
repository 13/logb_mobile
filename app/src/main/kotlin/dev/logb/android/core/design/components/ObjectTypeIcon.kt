package dev.logb.android.core.design.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.logb.android.core.design.LogbIcons

/** The glyph for an object type, built-in or own: `LocalTypeRegistry` knows which icon each key means. */
@Composable
fun ObjectTypeIcon(
    type: String,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = null,
) {
    Icon(
        painter = painterResource(LogbIcons.forIcon(LocalTypeRegistry.current.icon(type))),
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.size(size),
    )
}
