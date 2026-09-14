package dev.logb.android.feature.objects

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.logb.android.R
import dev.logb.android.core.design.theme.LogbTheme
import dev.logb.android.core.sync.SyncStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class ObjectsContentTest {
    @get:Rule val compose = createComposeRule()

    private val res = InstrumentationRegistry.getInstrumentation().targetContext.resources

    private val golf = ObjectCard("golf", "Golf", "car", 86000, "km", 26150, "2026-06-10", 1, null)
    private val house = ObjectCard("house", "House", "home", null, null, 0, null, 0, null)

    @Test
    fun cardsShowNameFiguresAndDueBadgeAndOpenOnTap() {
        var opened: String? = null
        compose.setContent {
            LogbTheme { ObjectsContent(ObjectsUiState(listOf(golf, house), dueCount = 1, sync = SyncStatus.Idle("2026-09-14T09:00:00.000Z"), loaded = true), onOpen = { opened = it }, {}, {}, {}) }
        }
        compose.onNodeWithText("Golf").assertIsDisplayed()
        compose.onNodeWithText("House").assertIsDisplayed()
        compose.onNode(hasText("86", substring = true) and hasText("km", substring = true)).assertIsDisplayed()
        compose.onNodeWithText(res.getQuantityString(R.plurals.due_count, 1, 1)).assertIsDisplayed()
        compose.onNodeWithText("Golf").performClick()
        assertEquals("golf", opened)
    }

    @Test
    fun anEmptyMirrorShowsTheEmptyState() {
        compose.setContent { LogbTheme { ObjectsContent(ObjectsUiState(loaded = true), {}, {}, {}, {}) } }
        compose.onNodeWithText(res.getString(R.string.objects_empty)).assertIsDisplayed()
    }
}
