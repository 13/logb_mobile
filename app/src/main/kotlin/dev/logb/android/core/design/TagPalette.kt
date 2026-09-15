package dev.logb.android.core.design

import androidx.compose.ui.graphics.Color

/** The web's tag colours (`--tag-N-bg`, `--tag-N-fg` in logb/frontend/src/app.css), so a tag has one colour everywhere. */
object TagPalette {
    private val light = listOf(
        Color(0xFFE3F1EC) to Color(0xFF14532D),
        Color(0xFFE4ECFB) to Color(0xFF1E3A8A),
        Color(0xFFFBE9E3) to Color(0xFF7C2D12),
        Color(0xFFF3E8FB) to Color(0xFF581C87),
        Color(0xFFFDF3D8) to Color(0xFF713F12),
        Color(0xFFFCE7EF) to Color(0xFF831843),
        Color(0xFFE0F4F7) to Color(0xFF164E63),
        Color(0xFFECEEF1) to Color(0xFF1F2937),
    )
    private val dark = listOf(
        Color(0xFF16392D) to Color(0xFFB7F0D2),
        Color(0xFF1C2D55) to Color(0xFFC7D7FE),
        Color(0xFF4A2417) to Color(0xFFFED7C7),
        Color(0xFF3A1D52) to Color(0xFFE9D5FF),
        Color(0xFF45330C) to Color(0xFFFDE68A),
        Color(0xFF4D1A33) to Color(0xFFFBCFE8),
        Color(0xFF123C47) to Color(0xFFBAE6FD),
        Color(0xFF2B313A) to Color(0xFFE5E7EB),
    )

    /** Background to content colour for palette slot [index]. */
    fun colors(index: Int, dark: Boolean): Pair<Color, Color> = (if (dark) this.dark else light)[index.mod(light.size)]
}
