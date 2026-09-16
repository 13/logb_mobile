package dev.logb.android.feature.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.logb.android.R
import dev.logb.android.core.auth.Session
import dev.logb.android.core.design.theme.LogbTheme
import dev.logb.android.core.network.dto.User
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The hub column ([SettingsHubContent]) must scroll its rows while [dev.logb.android.core.design.components.LogbTopBar]
 * stays put -- on a small screen or a large system font scale the eight rows overflow the
 * viewport, and without a scrollable container "About" (and whatever else falls past the fold)
 * is simply unreachable. The window this test renders into is constrained well below that, so
 * the fix is exercised for real rather than merely matching a screenshot taken at a size where
 * the rows already happened to fit.
 */
@RunWith(AndroidJUnit4::class)
class SettingsHubScrollTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `the About row scrolls into view while the top bar stays fixed`() {
        compose.setContent {
            LogbTheme {
                Box(Modifier.height(240.dp)) {
                    SettingsHubContent(
                        state = SettingsUiState(session = Session.SignedIn("https://logb.example.org/", User(1, "ben"), "t", "EUR")),
                        onOpen = {},
                    )
                }
            }
        }

        val topBarTitle = compose.activity.getString(R.string.nav_settings)
        val aboutTitle = compose.activity.getString(R.string.settings_about)

        compose.onNodeWithText(topBarTitle).assertIsDisplayed()
        // If the rows were not inside a scrollable container, performScrollTo() would find no
        // scrollable ancestor to act on and this row would stay unreachable.
        compose.onNodeWithText(aboutTitle).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(topBarTitle).assertIsDisplayed()
    }
}
