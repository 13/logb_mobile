package dev.logb.android.baselineprofile

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

/**
 * Cold start with and without the recorded profile, so the profile's worth is a measurement rather
 * than an assumption. Run both and compare:
 *
 *     ./gradlew :baselineprofile:pixel6Api34BenchmarkReleaseAndroidTest \
 *         -Pandroid.testInstrumentationRunnerArguments.class=dev.logb.android.baselineprofile.StartupBenchmark
 */
class StartupBenchmark {

    @get:Rule val rule = MacrobenchmarkRule()

    @Test fun startupWithoutProfile() = startup(CompilationMode.None())

    @Test fun startupWithProfile() = startup(CompilationMode.Partial())

    private fun startup(mode: CompilationMode) = rule.measureRepeated(
        packageName = "dev.logb.android",
        metrics = listOf(StartupTimingMetric()),
        compilationMode = mode,
        startupMode = StartupMode.COLD,
        iterations = 10,
    ) {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.textContains("Server")), 10_000)
    }
}
