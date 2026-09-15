import com.android.build.api.dsl.ManagedVirtualDevice

plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "dev.logb.android.baselineprofile"
    compileSdk = 37

    defaultConfig {
        // A baseline profile is collected without root from API 33 on, where a release build is
        // profileable by default.
        minSdk = 33
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The physical phone this app is otherwise verified on must never be a target for these
        // tasks (see baselineProfile below), so the macrobenchmark always runs on the managed
        // device's emulator. androidx.benchmark refuses that by default because an emulator's
        // numbers are not representative of a real device; suppressing is the documented way to
        // benchmark in an emulator-only environment. Read the reported medians with that caveat.
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    targetProjectPath = ":app"

    // A physical phone is always attached on this machine and must never be targeted by these
    // tasks, so runs go to a Gradle Managed Device instead of "whatever is connected". Only the
    // google_apis x86_64 API 34 image is installed locally, hence systemImageSource = "google".
    testOptions.managedDevices.allDevices {
        create<ManagedVirtualDevice>("pixel6Api34") {
            device = "Pixel 6"
            apiLevel = 34
            systemImageSource = "google"
        }
    }
}

kotlin {
    compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 }
}

baselineProfile {
    useConnectedDevices = false
    managedDevices += "pixel6Api34"
}

dependencies {
    implementation(libs.androidx.test.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.uiautomator)
    implementation(libs.benchmark.macro.junit4)
}
