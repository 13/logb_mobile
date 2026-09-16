package dev.logb.android.feature.onboarding

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.logb.android.R
import dev.logb.android.core.design.theme.LogbTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** [ServerContent]'s Continue button must not be tappable while a scanned code is still redeeming, or a late `checkServer()` could overwrite the record [SessionRepository.signInWithPairing] just stored. */
@RunWith(AndroidJUnit4::class)
class OnboardingContinueButtonTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `Continue is disabled while a scan is redeeming`() {
        compose.setContent {
            LogbTheme {
                ServerContent(state = ServerUiState(url = "logb.example.org", pairing = true), onUrlChange = {}, onSubmit = {})
            }
        }

        val continueLabel = compose.activity.getString(R.string.server_continue)
        compose.onNodeWithText(continueLabel).assertIsNotEnabled()
    }
}
