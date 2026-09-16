package dev.logb.android.feature.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.logb.android.R
import dev.logb.android.core.design.theme.LogbTheme
import dev.logb.android.core.server.Capabilities
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AboutContentTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val base = AboutInfo(
        versionName = "0.7.0", versionCode = 700, buildDate = "2026-09-14", commit = "1dc9ad7",
        debug = false, releaseKey = true, serverUrl = null, serverVersion = null, capabilities = Capabilities.NONE,
    )

    @Test fun `the build date line shows a known build date`() {
        compose.setContent { LogbTheme { AboutContent(base, onBack = {}, onOpenUrl = {}, onCopy = {}) } }
        val built = compose.activity.getString(R.string.about_build_date, "2026-09-14")
        compose.onNodeWithText(built).assertExists()
    }

    @Test fun `the build date line is hidden when the build date is unknown`() {
        compose.setContent { LogbTheme { AboutContent(base.copy(buildDate = "unknown"), onBack = {}, onOpenUrl = {}, onCopy = {}) } }
        val built = compose.activity.getString(R.string.about_build_date, "unknown")
        compose.onNodeWithText(built).assertDoesNotExist()
    }

    /**
     * releaseKey is null only until the signing check (a suspend call) answers. A release build
     * must never show "debug key" for that window -- the line is hidden instead, not wrong.
     */
    @Test fun `the signing line is hidden on a release build until the key is known`() {
        compose.setContent { LogbTheme { AboutContent(base.copy(releaseKey = null), onBack = {}, onOpenUrl = {}, onCopy = {}) } }
        compose.onNodeWithText(compose.activity.getString(R.string.about_release)).assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.about_release_debug_key)).assertDoesNotExist()
    }

    @Test fun `the signing line appears once the release key check answers`() {
        compose.setContent { LogbTheme { AboutContent(base.copy(releaseKey = true), onBack = {}, onOpenUrl = {}, onCopy = {}) } }
        compose.onNodeWithText(compose.activity.getString(R.string.about_release)).assertExists()
    }

    /** A debug build's line never depends on the signing check -- BuildConfig.DEBUG is known synchronously. */
    @Test fun `the debug line shows regardless of whether the release key is known`() {
        compose.setContent { LogbTheme { AboutContent(base.copy(debug = true, releaseKey = null), onBack = {}, onOpenUrl = {}, onCopy = {}) } }
        compose.onNodeWithText(compose.activity.getString(R.string.about_debug)).assertExists()
    }
}
