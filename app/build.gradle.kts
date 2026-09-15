import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
    alias(libs.plugins.roborazzi)
}

// The release workflow stamps the git tag in with -PversionName / -PversionCode.
val logbVersionName: String = providers.gradleProperty("versionName").getOrElse("0.8.0")
val logbVersionCode: Int = providers.gradleProperty("versionCode").map(String::toInt).getOrElse(800)

// A real signing key, when one exists: the user's global ANDROID_KEYSTORE* variables (CI exports
// the same names from secrets), or an untracked keystore/keystore.properties. Without either,
// release signs with the debug key, so a local build still installs.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingSecret(envName: String, propName: String): String? =
    (System.getenv(envName) ?: keystoreProps.getProperty(propName))?.takeIf { it.isNotBlank() }
val releaseStoreFile: String? = signingSecret("ANDROID_KEYSTORE", "storeFile")

// Which commit a build came from, for the About screen. The commit's own hash, not the wall
// clock, so BuildConfig does not change on every build.
val gitHash: String = runCatching {
    providers.exec { commandLine("git", "rev-parse", "--short", "HEAD"); workingDir = rootDir; isIgnoreExitValue = true }.standardOutput.asText.get().trim()
}.getOrNull()?.takeIf { it.isNotEmpty() } ?: "unknown"

// The commit's date, for the About screen: like the hash, stable for a given commit.
val gitDate: String = runCatching {
    providers.exec { commandLine("git", "show", "-s", "--format=%cs", "HEAD"); workingDir = rootDir; isIgnoreExitValue = true }.standardOutput.asText.get().trim()
}.getOrNull()?.takeIf { it.isNotEmpty() } ?: "unknown"

base {
    archivesName.set("LogB")
}

android {
    namespace = "dev.logb.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.logb.android"
        minSdk = 28
        targetSdk = 36
        versionCode = logbVersionCode
        versionName = logbVersionName
        testInstrumentationRunner = "dev.logb.android.HiltTestRunner"
        buildConfigField("String", "GIT_HASH", "\"$gitHash\"")
        buildConfigField("String", "BUILD_DATE", "\"$gitDate\"")
        // The repository the in-app updater asks for releases (feature/update).
        buildConfigField("String", "UPDATE_REPO", "\"13/logb_mobile\"")
        // The two languages the server speaks; nothing else ships strings.
        androidResources.localeFilters += listOf("en", "de")
    }

    lint {
        // CI runs this, so a warning has to be worth failing a build over.
        warningsAsErrors = true
        abortOnError = true
        disable += setOf(
            // Dependency and toolchain upgrades are a decision to take deliberately.
            "GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion",
            // targetSdk trails compileSdk on purpose: a new target is a behaviour change to test
            // on a device, not a number to bump.
            "OldTargetApi",
            // Lint calls mipmap-anydpi-v26 unnecessary at minSdk 28, but dropping the qualifier
            // makes aapt2 fail with "resource mipmap/ic_launcher not found" (apexweather: tried).
            "ObsoleteSdkInt",
        )
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = signingSecret("ANDROID_KEYSTORE_PASS", "storePassword")
                keyAlias = signingSecret("ANDROID_KEY_ALIAS", "keyAlias")
                keyPassword = signingSecret("ANDROID_KEY_PASS", "keyPassword")
                enableV1Signing = false
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            // The contract test runs against a real logb binary when one is named:
            //   ./gradlew :app:testDebugUnitTest -PlogbBin=$HOME/repo/logb/target/release/logb --tests '*SyncContractTest'
            all {
                it.systemProperty("logb.bin", project.findProperty("logbBin")?.toString() ?: "")
                // Thumbnails are real pixels; Robolectric's legacy graphics mode decodes nothing.
                it.systemProperty("robolectric.graphicsMode", "NATIVE")
            }
        }
    }
    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
    }

    // The migration test reads the exported schemas as assets. AGP 9's local (Robolectric) unit
    // tests read assets through the "debug" variant's merged assets, not a "test" source set
    // (there is no separate merged-assets output for unit tests) — so the schemas go on "debug".
    sourceSets.getByName("debug").assets.srcDir("$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.process)
    implementation(libs.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.androidx.compiler)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    // The bundled SQLite driver: one SQLite everywhere, and Room on a plain JVM in tests.
    implementation(libs.sqlite.bundled)
    implementation(libs.work.runtime)
    implementation(libs.datastore.preferences)
    implementation(libs.exifinterface)
    implementation(libs.biometric)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.coil.compose)
    implementation(libs.coil.okhttp)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.room.testing)
    testImplementation(libs.work.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.kotlin.test)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.compose.ui.test.junit4)
    // compose ui-test-junit4 brings espresso-core 3.5.0 at runtime, which crashes on API 36
    // (InputManager.getInstance is gone).
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
}
