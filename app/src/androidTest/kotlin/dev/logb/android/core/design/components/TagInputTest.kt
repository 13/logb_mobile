package dev.logb.android.core.design.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.logb.android.R
import dev.logb.android.core.design.theme.LogbTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/** As on the web: tapping a tag chip's body keeps it; only its trailing close icon removes it. */
@RunWith(AndroidJUnit4::class)
class TagInputTest {
    @get:Rule val compose = createComposeRule()
    private val res = InstrumentationRegistry.getInstrumentation().targetContext.resources

    @Test
    fun tappingTheChipBodyKeepsTheTagOnlyTheCloseIconRemovesIt() {
        var tags by mutableStateOf(listOf("Winter"))
        compose.setContent { LogbTheme { TagInput(tags, { tags = it }, emptyList()) } }

        compose.onNodeWithText("Winter").performClick()
        assertEquals(listOf("Winter"), tags, "the chip body must not remove the tag")

        compose.onNodeWithContentDescription(res.getString(R.string.tags_remove, "Winter")).performClick()
        assertEquals(emptyList(), tags, "the trailing close icon removes the tag")
    }
}
