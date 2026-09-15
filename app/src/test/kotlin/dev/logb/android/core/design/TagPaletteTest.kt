package dev.logb.android.core.design

import androidx.compose.ui.graphics.toArgb
import dev.logb.android.core.domain.Tags
import org.junit.Test
import kotlin.test.assertTrue

class TagPaletteTest {
    @Test fun `every tag colour pair is readable in both themes`() {
        for (dark in listOf(false, true)) for (i in 0 until Tags.PALETTE_SIZE) {
            val (bg, fg) = TagPalette.colors(i, dark)
            val ratio = Tags.contrastRatio(fg.toArgb().toLong() and 0xFFFFFFFF, bg.toArgb().toLong() and 0xFFFFFFFF)
            assertTrue(ratio >= 4.5, "tag $i dark=$dark: $ratio")
        }
    }
}
