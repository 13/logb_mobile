package dev.logb.android.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

/**
 * The cold start to the first screen, and nothing else, for the startup profile (it reorders the
 * dex, which only helps when it holds the launch path alone). A fresh install has no server, so
 * the first screen is the server screen: Compose, Hilt, DataStore and the session restore all run.
 *
 * Generate with `./gradlew :app:generateReleaseBaselineProfile` with only an API 33+ emulator
 * attached (`ANDROID_SERIAL=emulator-5554`).
 */
class StartupBaselineProfile {
    @get:Rule val rule = BaselineProfileRule()

    @Test
    fun startup() = rule.collect(packageName = PACKAGE, includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.textContains("Server")), 15_000)
    }

    private companion object { const val PACKAGE = "dev.logb.android" }
}
