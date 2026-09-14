# Phase 1: Foundation, read-only mirror — Implementation Plan

> Status: executed 2026-09-14. Deviations: toolchain follows `~/repo/apexweather` (Gradle 9.7 / AGP 9.3) instead of Gradle 8.14; JVM DAO tests run on Robolectric's SQLite because the bundled driver ships Android natives only; the contract test lives in `src/test` behind `-PlogbBin` rather than its own source set; UI tests exercise stateless `*Content` composables instead of Hilt-wired screens.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A LogB Android app that signs in to a self-hosted server, mirrors everything the account owns into local SQLite, keeps that mirror current through the sync feed, and renders Objects, object detail, search and settings from the mirror alone — readable with the radio off.

**Architecture:** Single-activity Jetpack Compose app, package-by-feature under `dev.logb.android`. Room is the only thing screens read. `core/sync` is the only thing that writes mirror tables from the network; it applies pulled ops with the server's own last-write-wins rule. Writes to the mirror from the UI are phase 2; this phase ships no forms.

**Tech Stack:** Kotlin 2.4 (built into AGP 9), Gradle 9.7 + AGP 9.3 on JDK 21 — the toolchain `~/repo/apexweather` builds with on this machine —, Compose (Material 3, Navigation Compose type-safe routes), Hilt, Room with the bundled SQLite driver, OkHttp 5 + Retrofit 3 + kotlinx.serialization, Coil 3, WorkManager, DataStore, MockWebServer, Turbine.

Spec: `docs/superpowers/specs/2026-09-14-logb-android-design.md`. Requires phase 0 merged and a server built from it running for the contract test; MockWebServer covers everything else.

## Global Constraints

- Package `dev.logb.android`; `applicationId` the same; `minSdk 28`, `targetSdk 36`, `compileSdk 36`.
- Build with JDK 21: `gradle.properties` pins `org.gradle.java.home=/usr/lib/jvm/java-21-openjdk` (CI strips the line and brings its own 21), so a plain `./gradlew …` works. The machine's default JDK 25 is newer than AGP supports.
- `ANDROID_HOME` in the shell points at `/opt/android-sdk`, which does not exist; the SDK is at `~/Android/Sdk`. Task 1 writes `local.properties` with `sdk.dir=/home/ben/Android/Sdk` (git-ignored) and every command inherits it.
- Versions are pinned in `gradle/libs.versions.toml`. Where this plan says *latest stable*, look it up at task time (`https://developer.android.com/jetpack/androidx/versions`, `https://github.com/coil-kt/coil/releases`) and pin the number; never a `+` range.
- Screens read only from Room. Nothing under `feature/` imports `core.network`.
- Every mirror query filters `deleted_at IS NULL`.
- `client_uuid` values are opaque strings; nothing validates them as UUIDs.
- Strings live in `res/values/strings.xml` (EN) and `res/values-de/strings.xml` (DE); a unit test keeps the two key sets identical. Copy is taken from `~/repo/logb/frontend/src/i18n/en.ts` and `de.ts` where a key exists.
- Every icon-only control has a `contentDescription`; every tap target is at least 48 dp.
- Commit after each task; messages end with the attribution lines the session provides.
- Verification before claiming a task done: `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` passes.

## File map

```
settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml, gradle.properties
app/build.gradle.kts, app/proguard-rules.pro
app/src/main/AndroidManifest.xml
app/src/main/res/                         strings (EN/DE), drawables (type icons, launcher), xml/network_security_config.xml
app/src/main/kotlin/dev/logb/android/
  LogbApp.kt                              @HiltAndroidApp, WorkManager configuration
  MainActivity.kt                         edge-to-edge, sets LogbTheme + AppNavHost
  di/                                     Hilt modules: Database, Network, Sync
  core/design/theme/{Color,Theme,Type,Shape}.kt
  core/design/LogbIcons.kt                type icon lookup, ImageVector loaders for the drawables
  core/design/components/                 StatFigure, ObjectTypeIcon, DueBadge, EmptyState, SyncLine, TopBar
  core/format/{Money,Counter,Dates}.kt    formatting helpers
  core/db/LogbDatabase.kt                 @Database, version 1
  core/db/DatabaseProvider.kt             one database file per (server, user)
  core/db/entity/*.kt                     ObjectEntity, ActivityEntity, AttachmentEntity, FileEntity, ReminderEntity,
                                          OpEntity, SyncStateEntity, FieldClockEntity, BlobEntity
  core/db/dao/*.kt                        ObjectDao, ActivityDao, AttachmentDao, FileDao, ReminderDao, OpDao, SyncStateDao,
                                          FieldClockDao, SearchDao
  core/db/model/*.kt                      query projections: ObjectCard, ObjectStats, Ancestor
  core/network/LogbApi.kt                 Retrofit interface
  core/network/dto/*.kt                   kotlinx.serialization DTOs
  core/network/ApiClient.kt               OkHttp/Retrofit factory, bearer + user-agent interceptors
  core/network/ApiError.kt                {error,message} → ApiException; 401 → Unauthorized
  core/auth/ServerStore.kt                DataStore: server URL
  core/auth/TokenStore.kt                 Keystore-wrapped token
  core/auth/SessionRepository.kt          sign in (login → mint → logout), sign out, Session flow
  core/sync/Bootstrap.kt                  snapshot → mirror
  core/sync/ChangeApplier.kt              one pulled ChangeRow → mirror, with LWW
  core/sync/PullEngine.kt                 paging, epoch, 410 handling, clock offset
  core/sync/SyncManager.kt                serialised runs, triggers, status flow
  core/sync/SyncWorker.kt                 WorkManager periodic + one-shot
  core/domain/ReminderRules.kt            port of domain/reminder.rs
  core/domain/ReminderPresenter.kt        port of ReminderOut::build
  core/domain/ObjectTypes.kt              type → icon, categories (object-types.ts)
  core/domain/TimelineFold.kt             port of timeline-fold.ts
  feature/onboarding/{ServerScreen,SignInScreen,BootstrapScreen}.kt + ViewModels
  feature/objects/{ObjectsScreen,ObjectsViewModel,ObjectDetailScreen,ObjectDetailViewModel}.kt
  feature/objects/tabs/{TimelineTab,DocumentsTab,RemindersTab,InfoTab}.kt
  feature/search/{SearchScreen,SearchViewModel}.kt
  feature/settings/{SettingsHubScreen,AppearanceScreen,AccountScreen,SyncScreen,AboutScreen}.kt + ViewModels
  navigation/{Routes.kt,AppNavHost.kt,BottomBar.kt}
app/src/test/kotlin/dev/logb/android/     JVM tests (Room bundled driver, MockWebServer)
app/src/test/resources/fixtures/          bootstrap.json, pull-page-1.json
app/src/androidTest/kotlin/…              Compose UI tests
.github/workflows/android.yml
```

---

### Task 1: Project scaffold that builds on this machine

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `gradle.properties`, `gradle/wrapper/gradle-wrapper.properties` (+ jar via `gradle wrapper`), `gradlew`, `.gitignore`, `local.properties`
- Create: `app/build.gradle.kts`, `app/proguard-rules.pro`, `app/src/main/AndroidManifest.xml`, `app/src/main/res/values/strings.xml`, `app/src/main/res/values/themes.xml`
- Create: `app/src/main/kotlin/dev/logb/android/LogbApp.kt`, `MainActivity.kt`
- Create: `app/src/test/kotlin/dev/logb/android/SmokeTest.kt`
- Create: `.github/workflows/android.yml`

- [ ] **Step 1: Bootstrap the wrapper**

There is no `gradle` on the PATH. Borrow the wrapper from `~/repo/apexweather`, the newest Android project on this machine and the source of every version pinned below:

```bash
cd ~/repo/logb_mobile
cp -r ~/repo/apexweather/gradle ~/repo/apexweather/gradlew . && rm gradle/libs.versions.toml
chmod +x gradlew
grep distributionUrl gradle/wrapper/gradle-wrapper.properties   # expect gradle-9.7.1-bin.zip
printf 'sdk.dir=/home/ben/Android/Sdk\n' > local.properties
```

- [ ] **Step 2: `.gitignore`**

```
.gradle/
build/
local.properties
*.iml
.idea/
.kotlin/
captures/
*.keystore
*.jks
```

- [ ] **Step 3: Version catalog**

`gradle/libs.versions.toml` — the file as committed in Task 1 is the reference; its `[versions]` table copies `~/repo/apexweather` (AGP 9.3.2, Kotlin 2.4.10, KSP 2.3.11, Hilt 2.60.1, Compose BOM 2026.08.00, Room 2.8.4, sqlite-bundled 2.6.2, Navigation 2.10.0, WorkManager 2.11.2, DataStore 1.2.1, OkHttp 5.5.0, Retrofit 3.0.0, Coil 3.6.2, kotlinx.serialization 1.11.0, Robolectric 4.16.1, Turbine 1.2.0). Every one of them is already in the local Gradle cache. AGP 9 has Kotlin built in, so `kotlin-android` is declared but applied nowhere.

- [ ] **Step 4: Root build files**

`settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "logb"
include(":app")
```

`build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
}
```

`gradle.properties`:

```
org.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1g
org.gradle.caching=true
org.gradle.configuration-cache=true
android.useAndroidX=true
android.nonTransitiveRClass=true
kotlin.code.style=official
```

- [ ] **Step 5: `app/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}

android {
    namespace = "dev.logb.android"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.logb.android"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "dev.logb.android.HiltTestRunner"
        resourceConfigurations += listOf("en", "de")
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(21) }
    buildFeatures { compose = true; buildConfig = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

room { schemaDirectory("$projectDir/schemas") }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.process)
    implementation(libs.hilt.android); ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work); ksp(libs.hilt.work.compiler)
    implementation(libs.room.runtime); implementation(libs.room.ktx); ksp(libs.room.compiler)
    implementation(libs.sqlite.bundled)
    implementation(libs.work.runtime)
    implementation(libs.datastore.preferences)
    implementation(libs.okhttp); implementation(libs.okhttp.logging)
    implementation(libs.retrofit); implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose); implementation(libs.coil.network.okhttp)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.room.testing)
    testImplementation(libs.work.testing)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.hilt.android)
    kspAndroidTest(libs.hilt.compiler)
}
```

`app/proguard-rules.pro` starts with the kotlinx.serialization keep rules from its README and `-keep class dev.logb.android.core.network.dto.** { *; }`.

- [ ] **Step 6: Manifest, application, activity**

`AndroidManifest.xml`:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <application
        android:name=".LogbApp"
        android:label="@string/app_name"
        android:icon="@mipmap/ic_launcher"
        android:supportsRtl="true"
        android:theme="@style/Theme.Logb"
        android:networkSecurityConfig="@xml/network_security_config"
        android:enableOnBackInvokedCallback="true">
        <activity android:name=".MainActivity" android:exported="true"
            android:windowSoftInputMode="adjustResize" android:launchMode="singleTask">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
        <provider android:name="androidx.startup.InitializationProvider"
            android:authorities="${applicationId}.androidx-startup" android:exported="false"
            tools:node="merge" xmlns:tools="http://schemas.android.com/tools">
            <meta-data android:name="androidx.work.WorkManagerInitializer" android:value="androidx.startup" tools:node="remove" />
        </provider>
    </application>
</manifest>
```

`res/xml/network_security_config.xml` permits cleartext to `localhost`, `10.0.2.2` and private ranges only in debug — a self-hosted LogB on a home LAN is often plain http:

```xml
<network-security-config>
    <base-config cleartextTrafficPermitted="false" />
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="true">localhost</domain>
        <domain includeSubdomains="true">10.0.2.2</domain>
    </domain-config>
</network-security-config>
```

(Phase 5 revisits whether to allow http on any host behind an explicit "insecure server" switch; until then the server must be https or one of the two names above.)

`res/values/themes.xml`: `<style name="Theme.Logb" parent="android:Theme.Material.Light.NoActionBar" />`. `res/values/strings.xml`: `app_name` = `LogB`.

`LogbApp.kt`:

```kotlin
@HiltAndroidApp
class LogbApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}
```

`MainActivity.kt`:

```kotlin
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { Text("LogB") }   // replaced in Task 2
    }
}
```

- [ ] **Step 7: A smoke test and CI**

`app/src/test/kotlin/dev/logb/android/SmokeTest.kt`:

```kotlin
class SmokeTest { @Test fun `the test task runs`() { assertEquals(4, 2 + 2) } }
```

`.github/workflows/android.yml`: checkout, `actions/setup-java` (temurin 21), `gradle/actions/setup-gradle`, `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`. The contract job is added in Task 13.

- [ ] **Step 8: Build and run on the device**

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk && adb shell am start -n dev.logb.android/.MainActivity
```

Expected: tests pass; "LogB" appears on the connected phone.

- [ ] **Step 9: Commit**

```bash
git add -A && git commit -m "chore: Android project scaffold (Compose, Hilt, Room, Retrofit) building on JDK 21"
```

---

### Task 2: Design system — LogB's palette, tabular figures, type icons

**Files:**
- Create: `core/design/theme/Color.kt`, `Theme.kt`, `Type.kt`, `Shape.kt`
- Create: `core/design/LogbIcons.kt`, `res/drawable/ic_type_{car,e_bike,bike,motorcycle,home,appliance,tool,body,object}.xml`, `res/drawable/ic_logb_mark.xml`
- Create: launcher: `res/mipmap-anydpi-v26/ic_launcher.xml`, `res/drawable/ic_launcher_foreground.xml`, `ic_launcher_background.xml`, `ic_launcher_monochrome.xml`
- Create: `core/format/Money.kt`, `Counter.kt`, `Dates.kt`
- Create: `core/design/components/{StatFigure,ObjectTypeIcon,DueBadge,EmptyState,LogbTopBar}.kt`
- Test: `core/format/MoneyTest.kt`, `CounterTest.kt`, `DatesTest.kt`

**Interfaces:**
- Produces: `@Composable fun LogbTheme(dynamicColor: Boolean = false, darkTheme: Boolean = isSystemInDarkTheme(), content)`, `LogbIcons.forType(type: String): Painter`, `formatCents(cents: Long, currency: String, locale): String`, `formatCounter(value: Long?, unit: String?, locale): String`, `formatDate(iso: String, locale): String`, `relativeDay(iso, today, locale)`.

- [ ] **Step 1: Failing formatter tests**

```kotlin
class MoneyTest {
    @Test fun `cents format as the instance currency with two decimals`() {
        assertEquals("€189.00", formatCents(18900, "EUR", Locale.UK))
        assertEquals("189,00 €", formatCents(18900, "EUR", Locale.GERMANY))
    }
    @Test fun `negative and zero`() { assertEquals("€0.00", formatCents(0, "EUR", Locale.UK)) }
}
class CounterTest {
    @Test fun `a counter shows its unit with grouping`() {
        assertEquals("84,210 km", formatCounter(84210, "km", Locale.UK))
        assertEquals("84.210 km", formatCounter(84210, "km", Locale.GERMANY))
        assertEquals("120 h", formatCounter(120, "h", Locale.UK))
    }
    @Test fun `no unit means a bare number, null means a dash`() {
        assertEquals("12", formatCounter(12, null, Locale.UK)); assertEquals("—", formatCounter(null, "km", Locale.UK))
    }
}
class DatesTest {
    @Test fun `iso dates render in the locale medium style`() { assertEquals("1 Sept 2026", formatDate("2026-09-01", Locale.UK)) }
}
```

- [ ] **Step 2: Run, expect compile failure**

Run: `./gradlew :app:testDebugUnitTest --tests 'dev.logb.android.core.format.*'`

- [ ] **Step 3: Formatters**

`Money.kt` uses `java.text.NumberFormat.getCurrencyInstance(locale)` with `Currency.getInstance(currency)`; `Counter.kt` uses `NumberFormat.getIntegerInstance(locale)`; `Dates.kt` uses `java.time.LocalDate.parse` + `DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)`. The exact expected strings above come from JDK 21's CLDR; if the JDK emits a narrow no-break space or "Sep" instead of "Sept", adjust the expectation to what `Locale.UK` actually produces on JDK 21 — the test pins behaviour, not a guess.

- [ ] **Step 4: Colours and theme**

`Color.kt` — from `~/repo/logb/frontend/src/app.css`:

```kotlin
object LogbPalette {
    val Teal = Color(0xFF1F6F5F); val TealDark = Color(0xFF4FB39A)
    val OnTeal = Color(0xFFFFFFFF); val OnTealDark = Color(0xFF0B1F1A)
    val Bg = Color(0xFFF7F7F5); val BgDark = Color(0xFF121412)
    val Surface = Color(0xFFFFFFFF); val SurfaceDark = Color(0xFF1C1F1C)
    val Surface2 = Color(0xFFEEEEEA); val Surface2Dark = Color(0xFF262A26)
    val Text = Color(0xFF1C1C1A); val TextDark = Color(0xFFECEBE6)
    val Muted = Color(0xFF6B6B66); val MutedDark = Color(0xFFA3A39C)
    val Danger = Color(0xFFB3261E); val DangerDark = Color(0xFFFF6B60)
    val Warn = Color(0xFFB26A00); val WarnDark = Color(0xFFF0A640)
    val Border = Color(0xFFDDDCD6); val BorderDark = Color(0xFF33372F)
}
```

`Theme.kt` builds `lightColorScheme(primary = Teal, onPrimary = OnTeal, background = Bg, surface = Surface, surfaceVariant = Surface2, onBackground = Text, onSurface = Text, onSurfaceVariant = Muted, error = Danger, outline = Border, tertiary = Warn, …)` and the dark twin; when `dynamicColor && Build.VERSION.SDK_INT >= 31` it uses `dynamicLightColorScheme`/`dynamicDarkColorScheme`. Expose `LocalWarnColor` as a `CompositionLocal` for the due badge.

`Type.kt`: Material default `Typography` copied with `bodyLarge`/`titleLarge` unchanged and a `figure` style added via an extension: `val Typography.figure get() = titleLarge.copy(fontFeatureSettings = "tnum", fontWeight = FontWeight.SemiBold)` and `figureSmall` from `bodyLarge`. `Shape.kt`: `Shapes(small = 8.dp, medium = 12.dp, large = 16.dp)` rounded.

- [ ] **Step 5: Type icons and launcher**

For each of the nine names, open `~/repo/logb/frontend/src/lib/Icon.svelte`, copy the `<path d=…>` / `<circle>` elements for that name into a vector drawable on a `24×24` viewport, stroke `#000` with `android:strokeWidth="1.75"`, `strokeLineCap="round"`, `strokeLineJoin="round"`, no fill, and `android:tint="?attr/colorControlNormal"` off — tint is applied at use with `Icon(painter, tint = LocalContentColor.current)`. Circles become `pathData` arcs (`M cx-r,cy a r,r 0 1,0 2r,0 a r,r 0 1,0 -2r,0`).

`LogbIcons.kt`:

```kotlin
object LogbIcons {
    @DrawableRes fun forType(type: String): Int = when (type) {
        "car" -> R.drawable.ic_type_car; "e_bike" -> R.drawable.ic_type_e_bike; "bike" -> R.drawable.ic_type_bike
        "motorcycle" -> R.drawable.ic_type_motorcycle; "home" -> R.drawable.ic_type_home
        "appliance" -> R.drawable.ic_type_appliance; "tool" -> R.drawable.ic_type_tool; "body" -> R.drawable.ic_type_body
        else -> R.drawable.ic_type_object
    }
}
```

Launcher: adaptive icon with background `#1F6F5F`, foreground the mark from `public/icon.svg` (the ring and three lines) in white scaled into the safe zone, and a monochrome layer of the same mark for themed icons. Generate the legacy mipmaps with Android Studio's Image Asset tool or `inkscape`.

- [ ] **Step 6: Shared components**

- `StatFigure(label, value)` — label in `bodySmall` muted, value in `figure`.
- `ObjectTypeIcon(type, size = 24.dp)`.
- `DueBadge(count)` — warn-coloured pill, hidden at 0.
- `EmptyState(icon, title, body, action: (label, onClick)?)`.
- `LogbTopBar(title, onBack?, actions)` — `TopAppBar` with `Icons.AutoMirrored.Filled.ArrowBack` and `contentDescription = stringResource(R.string.back)`.

Add `@Preview` composables for each in light and dark.

- [ ] **Step 7: Tests pass, wire the theme into `MainActivity`, commit**

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
git add -A && git commit -m "feat: LogB theme, tabular figures, type icons, launcher icon, formatters"
```

---

### Task 3: Room schema — the mirror plus local-only tables

**Files:**
- Create: `core/db/entity/{ObjectEntity,ActivityEntity,AttachmentEntity,FileEntity,ReminderEntity,OpEntity,SyncStateEntity,FieldClockEntity,BlobEntity}.kt`
- Create: `core/db/dao/{ObjectDao,ActivityDao,AttachmentDao,FileDao,ReminderDao,OpDao,SyncStateDao,FieldClockDao,SearchDao}.kt`
- Create: `core/db/model/{ObjectCard,ObjectStats,Ancestor,TimelineRow}.kt`
- Create: `core/db/LogbDatabase.kt`, `core/db/DatabaseProvider.kt`, `di/DatabaseModule.kt`
- Create: `app/src/test/kotlin/dev/logb/android/core/db/TestDatabase.kt` (JVM factory), `ObjectDaoTest.kt`, `ReminderDaoTest.kt`
- Create: `app/schemas/dev.logb.android.core.db.LogbDatabase/1.json` (exported by the Room plugin)

**Interfaces:**
- Produces: the entities and DAOs below; `DatabaseProvider.open(serverUrl: String, userId: Long): LogbDatabase`; `TestDatabase.inMemory(): LogbDatabase` for JVM tests.

- [ ] **Step 1: Entities**

Column names mirror the server's (`snake_case` via `@ColumnInfo`) except references, which are uuids:

```kotlin
@Entity(tableName = "objects", indices = [Index("server_id", unique = true), Index("parent_uuid"), Index("deleted_at")])
data class ObjectEntity(
    @PrimaryKey val uuid: String,
    @ColumnInfo(name = "server_id") val serverId: Long?,
    val name: String,
    val type: String,
    @ColumnInfo(name = "counter_unit") val counterUnit: String?,
    @ColumnInfo(name = "fuel_unit") val fuelUnit: String?,
    val description: String,
    @ColumnInfo(name = "purchase_date") val purchaseDate: String?,
    @ColumnInfo(name = "purchase_price_cents") val purchasePriceCents: Long?,
    @ColumnInfo(name = "archived_at") val archivedAt: String?,
    @ColumnInfo(name = "cover_attachment_uuid") val coverAttachmentUuid: String?,
    @ColumnInfo(name = "parent_uuid") val parentUuid: String?,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
    @ColumnInfo(name = "deleted_at") val deletedAt: String?,
)
```

`ActivityEntity(uuid, serverId, objectUuid, date, category, title, notes, counterValue, costCents, quantityMilli, createdAt, updatedAt, deletedAt)` with indices on `server_id` (unique), `(object_uuid, date)`, `deleted_at`.

`AttachmentEntity(uuid, serverId, objectUuid, activityUuid?, fileUuid, kind, caption, createdAt, deletedAt)`.

`FileEntity(uuid, serverId, sha256, originalName, mime, size, width?, height?, takenAt?, createdAt, deletedAt)`.

`ReminderEntity(uuid, serverId, objectUuid, title, notes, dueDate?, dueCounter?, repeatMonths?, repeatCounter?, snoozedUntil?, doneAt?, doneActivityUuid?, kind, everyN?, everyUnit?, createdAt, deletedAt)`.

Local-only:

```kotlin
@Entity(tableName = "ops", indices = [Index("id", unique = true), Index("entity_uuid")])
data class OpEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val id: String,                       // client_op_id
    val kind: String,                     // create | set | delete
    val entity: String,                   // object | activity | reminder | attachment
    @ColumnInfo(name = "entity_uuid") val entityUuid: String,
    val field: String?,
    @ColumnInfo(name = "value_json") val valueJson: String?,
    @ColumnInfo(name = "edited_at") val editedAt: String,
    val attempts: Int = 0,
    val dead: Boolean = false,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
)

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val id: Int = 1,
    @ColumnInfo(name = "cursor_seq") val cursorSeq: Long = 0,
    val epoch: String? = null,
    @ColumnInfo(name = "clock_offset_ms") val clockOffsetMs: Long = 0,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "last_synced_at") val lastSyncedAt: String? = null,
    @ColumnInfo(name = "bootstrap_needed") val bootstrapNeeded: Boolean = true,
)

@Entity(tableName = "field_clock", primaryKeys = ["entity", "entity_uuid", "field"])
data class FieldClockEntity(val entity: String, @ColumnInfo(name = "entity_uuid") val entityUuid: String,
    val field: String, @ColumnInfo(name = "edited_at") val editedAt: String, @ColumnInfo(name = "device_id") val deviceId: String)

@Entity(tableName = "blobs")
data class BlobEntity(@PrimaryKey val sha256: String, val size: Long, val mime: String,
    @ColumnInfo(name = "original_present") val originalPresent: Boolean, @ColumnInfo(name = "thumb_present") val thumbPresent: Boolean,
    @ColumnInfo(name = "last_access_at") val lastAccessAt: String?)
```

- [ ] **Step 2: Failing DAO tests**

`TestDatabase.kt`:

```kotlin
object TestDatabase {
    fun inMemory(): LogbDatabase = Room.inMemoryDatabaseBuilder<LogbDatabase>()
        .setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build()
}
```

(`Room.inMemoryDatabaseBuilder<T>()` without a `Context` is the KMP builder that exists from Room 2.7; if the resolved Room version only offers the Android builder, use Robolectric's `ApplicationProvider.getApplicationContext()` instead and add `robolectric` to the test dependencies.)

`ObjectDaoTest.kt`:

```kotlin
class ObjectDaoTest {
    private val db = TestDatabase.inMemory()
    private fun obj(uuid: String, name: String, parent: String? = null, archived: String? = null) = ObjectEntity(
        uuid, null, name, "car", "km", null, "", null, null, archived, null, parent, "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z", null)
    private fun act(uuid: String, obj: String, date: String, cost: Long?, counter: Long?) = ActivityEntity(
        uuid, null, obj, date, "maintenance", "x", "", counter, cost, null, "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z", null)

    @Test fun `stats sum cost and take the highest counter and the newest dates`() = runTest {
        db.objectDao().upsert(obj("o1", "Golf"))
        db.activityDao().upsert(act("a1", "o1", "2026-03-01", 18900, 84210))
        db.activityDao().upsert(act("a2", "o1", "2026-05-01", null, 84000))
        db.activityDao().upsert(act("a3", "o1", "2026-06-01", 5000, null))
        val s = db.objectDao().stats("o1")
        assertEquals(23900, s.totalCostCents); assertEquals(3, s.activityCount)
        assertEquals(84210, s.currentCounter); assertEquals("2026-06-01", s.lastActivityDate); assertEquals("2026-05-01", s.lastReadingDate)
    }
    @Test fun `a tombstoned activity counts for nothing`() = runTest {
        db.objectDao().upsert(obj("o1", "Golf"))
        db.activityDao().upsert(act("a1", "o1", "2026-03-01", 18900, 84210).copy(deletedAt = "2026-09-01T00:00:00Z"))
        assertEquals(0, db.objectDao().stats("o1").activityCount)
    }
    @Test fun `roots exclude children and archived unless asked`() = runTest {
        db.objectDao().upsert(obj("house", "House")); db.objectDao().upsert(obj("garage", "Garage", parent = "house"))
        db.objectDao().upsert(obj("old", "Old bike", archived = "2025-01-01T00:00:00Z"))
        assertEquals(listOf("House"), db.objectDao().roots(archived = false).first().map { it.name })
        assertEquals(listOf("Old bike"), db.objectDao().roots(archived = true).first().map { it.name })
        assertEquals(listOf("Garage"), db.objectDao().children("house").first().map { it.name })
    }
    @Test fun `ancestors run root first`() = runTest {
        db.objectDao().upsert(obj("house", "House")); db.objectDao().upsert(obj("garage", "Garage", parent = "house"))
        db.objectDao().upsert(obj("light", "Light", parent = "garage"))
        assertEquals(listOf("House", "Garage"), db.objectDao().ancestors("light").map { it.name })
    }
    @Test fun `descendants of a node include every level`() = runTest {
        db.objectDao().upsert(obj("house", "House")); db.objectDao().upsert(obj("garage", "Garage", parent = "house"))
        db.objectDao().upsert(obj("light", "Light", parent = "garage"))
        assertEquals(setOf("garage", "light"), db.objectDao().descendantUuids("house").toSet())
    }
}
```

`ReminderDaoTest`: open reminders for an object, done ones separate, tombstones excluded; `SearchDaoTest`: `LIKE` over name/description and title/notes, `%` and `_` in the query escaped (mirror `src/api/search.rs::like_pattern`).

- [ ] **Step 3: Run, expect failure**

Run: `./gradlew :app:testDebugUnitTest --tests 'dev.logb.android.core.db.*'`

- [ ] **Step 4: DAOs**

`ObjectDao` — the stats query ports `src/api/objects.rs::stats` and `derived`:

```kotlin
@Dao
interface ObjectDao {
    @Upsert suspend fun upsert(vararg rows: ObjectEntity)
    @Query("SELECT * FROM objects WHERE uuid = :uuid AND deleted_at IS NULL") fun observe(uuid: String): Flow<ObjectEntity?>
    @Query("SELECT * FROM objects WHERE uuid = :uuid") suspend fun get(uuid: String): ObjectEntity?
    @Query("SELECT uuid FROM objects WHERE server_id = :id") suspend fun uuidForServerId(id: Long): String?

    @Query("""SELECT * FROM objects WHERE deleted_at IS NULL AND parent_uuid IS NULL
              AND ((:archived = 0 AND archived_at IS NULL) OR (:archived = 1 AND archived_at IS NOT NULL))
              ORDER BY name COLLATE NOCASE""")
    fun roots(archived: Boolean): Flow<List<ObjectEntity>>

    @Query("SELECT * FROM objects WHERE deleted_at IS NULL AND parent_uuid = :parent ORDER BY name COLLATE NOCASE")
    fun children(parent: String): Flow<List<ObjectEntity>>

    @Query("""SELECT COALESCE(SUM(cost_cents), 0) AS totalCostCents, COUNT(*) AS activityCount,
              MAX(counter_value) AS currentCounter, MAX(date) AS lastActivityDate,
              (SELECT MAX(date) FROM activities WHERE object_uuid = :uuid AND deleted_at IS NULL AND counter_value IS NOT NULL) AS lastReadingDate
              FROM activities WHERE object_uuid = :uuid AND deleted_at IS NULL""")
    suspend fun stats(uuid: String): ObjectStats

    @Query("""WITH RECURSIVE up(uuid, name, parent_uuid, depth) AS (
                SELECT o.uuid, o.name, o.parent_uuid, 0 FROM objects o WHERE o.uuid = (SELECT parent_uuid FROM objects WHERE uuid = :uuid)
                UNION ALL SELECT o.uuid, o.name, o.parent_uuid, up.depth + 1 FROM objects o JOIN up ON o.uuid = up.parent_uuid)
              SELECT uuid, name FROM up WHERE uuid IS NOT NULL ORDER BY depth DESC""")
    suspend fun ancestors(uuid: String): List<Ancestor>

    @Query("""WITH RECURSIVE down(uuid) AS (
                SELECT uuid FROM objects WHERE parent_uuid = :uuid AND deleted_at IS NULL
                UNION ALL SELECT o.uuid FROM objects o JOIN down ON o.parent_uuid = down.uuid WHERE o.deleted_at IS NULL)
              SELECT uuid FROM down""")
    suspend fun descendantUuids(uuid: String): List<String>
}
```

`due_reminder_count` is computed in Kotlin (Task 9) because it needs the reminder rules; `ObjectCard` in Task 10 combines `ObjectEntity + ObjectStats + dueCount`.

`ActivityDao`: `upsert`, `get`, `uuidForServerId`, `timeline(objectUuid): Flow<List<ActivityEntity>>` newest first, `recentTitles(objectUuid, limit = 20)`, `readings(objectUuid)` (counter non-null, ascending date). `AttachmentDao`: `forObject`, `forActivity`, `forActivities(uuids)`, with a `@Query` joining `files` into an `AttachmentWithFile` relation. `FileDao`, `ReminderDao` (`forObject`, `open`, `allOpen()` for the due banner), `OpDao` (`pending()` ordered by `seq`, `dead()`, `insert`, `delete(id)`, `markDead`, `deleteForEntity(uuid)`), `SyncStateDao` (`get`, `upsert`, `observe`), `FieldClockDao` (`get(entity, uuid, field)`, `upsert`, `clear()`), `SearchDao`.

`LogbDatabase`:

```kotlin
@Database(entities = [ObjectEntity::class, ActivityEntity::class, AttachmentEntity::class, FileEntity::class, ReminderEntity::class,
    OpEntity::class, SyncStateEntity::class, FieldClockEntity::class, BlobEntity::class], version = 1, exportSchema = true)
abstract class LogbDatabase : RoomDatabase() { abstract fun objectDao(): ObjectDao; /* … one per DAO */ }
```

`DatabaseProvider` keeps one open `LogbDatabase` at a time, keyed by `(serverUrl, userId)`:

```kotlin
@Singleton class DatabaseProvider @Inject constructor(@ApplicationContext private val context: Context) {
    fun fileName(serverUrl: String, userId: Long) = "logb-${sha256Hex(serverUrl).take(8)}-$userId.db"
    fun open(serverUrl: String, userId: Long): LogbDatabase =
        Room.databaseBuilder(context, LogbDatabase::class.java, fileName(serverUrl, userId))
            .setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build()
    fun delete(serverUrl: String, userId: Long) = context.deleteDatabase(fileName(serverUrl, userId))
}
```

`di/DatabaseModule.kt` provides `LogbDatabase` from `DatabaseProvider` + the active session (Task 5 supplies `SessionRepository`; until then bind a placeholder `@Provides fun db(p: DatabaseProvider) = p.open("placeholder", 0)` and replace it in Task 5).

- [ ] **Step 5: Tests pass; export the schema; commit**

```bash
./gradlew :app:testDebugUnitTest --tests 'dev.logb.android.core.db.*'
git add -A && git commit -m "feat: Room mirror schema with uuid keys, op queue, sync state, field clock"
```

---

### Task 4: Network client and DTOs

**Files:**
- Create: `core/network/LogbApi.kt`, `core/network/ApiClient.kt`, `core/network/ApiError.kt`, `core/network/dto/{Auth,Sync,Entities}.kt`, `di/NetworkModule.kt`
- Test: `core/network/ApiClientTest.kt`

**Interfaces:**
- Produces: `LogbApi` (Retrofit) with `health()`, `login(Credentials)`, `me()`, `createToken(NewToken)`, `revokeToken(id)`, `logout()`, `bootstrap()`, `pull(since, epoch?, limit)`, `push(PushBody)`; `ApiClient.create(baseUrl, tokenProvider: () -> String?, cookieJar: CookieJar? = null): LogbApi`; `ApiException(status, code, message)`, `UnauthorizedException`.

- [ ] **Step 1: DTOs**

`Sync.kt`:

```kotlin
@Serializable data class ChangeRow(val seq: Long, val entity: String, @SerialName("entity_uuid") val entityUuid: String,
    val op: String, val field: String? = null, val value: String? = null, @SerialName("edited_at") val editedAt: String,
    @SerialName("device_id") val deviceId: String, @SerialName("entity_id") val entityId: Long? = null)
@Serializable data class PullResult(val changes: List<ChangeRow>, @SerialName("next_seq") val nextSeq: Long,
    val complete: Boolean, @SerialName("server_time") val serverTime: String, val epoch: String)
@Serializable data class BootstrapResult(val objects: List<JsonObject>, val activities: List<JsonObject>, val reminders: List<JsonObject>,
    val attachments: List<JsonObject>, val files: List<JsonObject>, val seq: Long, @SerialName("server_time") val serverTime: String, val epoch: String)
@Serializable data class Op(@SerialName("client_op_id") val clientOpId: String, val entity: String, @SerialName("entity_uuid") val entityUuid: String,
    val op: String, val field: String? = null, val value: JsonElement? = null, @SerialName("edited_at") val editedAt: String, @SerialName("device_id") val deviceId: String)
@Serializable data class PushBody(val ops: List<Op>)
@Serializable data class OpResult(@SerialName("client_op_id") val clientOpId: String, val outcome: String, val reason: String? = null)
@Serializable data class PushResult(val results: List<OpResult>, @SerialName("server_time") val serverTime: String, val ids: Map<String, Long> = emptyMap())
```

`Auth.kt`: `Credentials(username, password)`, `User(id, username, isAdmin, lang)`, `NewToken(name)`, `ApiToken(id, name, prefix, createdAt, lastUsedAt)`, `NewApiToken(…, token)`. `Entities.kt`: `ObjectDto`, `ActivityDto`, `ReminderDto`, `AttachmentDto` matching `docs/openapi.json` including `client_uuid` and `file_uuid` (phase 0) — used by phase 2's creates, declared now so the shapes are reviewed together. Bootstrap rows stay `JsonObject`: the server sends every column verbatim, and Task 6 maps them by name.

The `Json` instance: `Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }`.

- [ ] **Step 2: Failing client tests**

```kotlin
class ApiClientTest {
    @get:Rule val server = MockWebServer()
    private fun api(token: String? = "logb_pat_x") = ApiClient.create(server.url("/").toString(), { token })

    @Test fun `every request carries the bearer token and the api prefix`() = runTest {
        server.enqueue(MockResponse(body = """{"id":1,"username":"ben","is_admin":true,"lang":"en"}"""))
        api().me()
        val r = server.takeRequest()
        assertEquals("/api/auth/me", r.url.encodedPath); assertEquals("Bearer logb_pat_x", r.headers["Authorization"])
        assertTrue(r.headers["User-Agent"]!!.startsWith("LogB-Android/"))
    }
    @Test fun `an error body becomes an ApiException with the servers code`() = runTest {
        server.enqueue(MockResponse(code = 400, body = """{"error":"bad_request","message":"name is required"}"""))
        val e = assertFailsWith<ApiException> { api().me() }
        assertEquals(400, e.status); assertEquals("bad_request", e.code); assertEquals("name is required", e.message)
    }
    @Test fun `a 401 is its own exception`() = runTest {
        server.enqueue(MockResponse(code = 401, body = """{"error":"unauthorized","message":"authentication required"}"""))
        assertFailsWith<UnauthorizedException> { api().me() }
    }
    @Test fun `a 410 on pull is GoneException`() = runTest {
        server.enqueue(MockResponse(code = 410, body = """{"error":"gone","message":"re-bootstrap"}"""))
        assertFailsWith<GoneException> { api().pull(since = 5, epoch = "e", limit = 10) }
    }
    @Test fun `pull parses entity_id and double-encoded values verbatim`() = runTest {
        server.enqueue(MockResponse(body = """{"changes":[{"seq":7,"entity":"object","entity_uuid":"u","op":"set","field":"name",
            "value":"\"Golf VII\"","edited_at":"2026-09-01T00:00:00.000Z","device_id":"rest","entity_id":42}],
            "next_seq":7,"complete":true,"server_time":"2026-09-01T00:00:01.000Z","epoch":"e"}"""))
        val p = api().pull(0, null, 500)
        assertEquals(42, p.changes[0].entityId); assertEquals("\"Golf VII\"", p.changes[0].value)
    }
}
```

- [ ] **Step 3: Run, expect failure**

Run: `./gradlew :app:testDebugUnitTest --tests 'dev.logb.android.core.network.*'`

- [ ] **Step 4: Implement**

`LogbApi.kt`:

```kotlin
interface LogbApi {
    @GET("api/health") suspend fun health(): Response<Unit>
    @POST("api/auth/login") suspend fun login(@Body body: Credentials): User
    @GET("api/auth/me") suspend fun me(): User
    @POST("api/auth/tokens") suspend fun createToken(@Body body: NewToken): NewApiToken
    @DELETE("api/auth/tokens/{id}") suspend fun revokeToken(@Path("id") id: Long)
    @POST("api/auth/logout") suspend fun logout()
    @GET("api/sync/bootstrap") suspend fun bootstrap(): BootstrapResult
    @GET("api/sync/pull") suspend fun pull(@Query("since") since: Long, @Query("epoch") epoch: String?, @Query("limit") limit: Int): PullResult
    @POST("api/sync/push") suspend fun push(@Body body: PushBody): PushResult
}
```

`ApiClient.create` builds OkHttp with: a bearer interceptor (adds the header when `tokenProvider()` is non-null), a `User-Agent: LogB-Android/<versionName>` interceptor, an error-mapping interceptor that turns any non-2xx into `ApiException` / `UnauthorizedException` (401) / `GoneException` (410) by parsing `{error,message}` (falling back to the status text), the optional cookie jar, `HttpLoggingInterceptor` at `BASIC` in debug builds only, 15 s connect / 60 s read timeouts. Retrofit uses `converter-kotlinx-serialization` with the `Json` above. Base URL is the server URL with a trailing slash guaranteed.

`di/NetworkModule.kt` provides a `LogbApi` bound to the current server URL and token (both from Task 5's stores; until then, provide from constants and replace in Task 5).

- [ ] **Step 5: Tests pass; commit**

```bash
git add -A && git commit -m "feat: LogB API client with bearer auth, error mapping and sync DTOs"
```

---

### Task 5: Server URL, sign in, token store, session

**Files:**
- Create: `core/auth/ServerStore.kt`, `core/auth/TokenStore.kt`, `core/auth/KeystoreCipher.kt`, `core/auth/Session.kt`, `core/auth/SessionRepository.kt`
- Modify: `di/NetworkModule.kt`, `di/DatabaseModule.kt` (bind to the live session)
- Create: `feature/onboarding/{ServerScreen,ServerViewModel,SignInScreen,SignInViewModel}.kt`
- Modify: `res/values/strings.xml`, `res/values-de/strings.xml`
- Test: `core/auth/SessionRepositoryTest.kt`, `core/auth/TokenStoreTest.kt` (androidTest — needs the real Keystore)

**Interfaces:**
- Produces: `sealed interface Session { SignedOut; NeedsServer; SignedIn(serverUrl, user: User, token) }`, `SessionRepository.session: StateFlow<Session>`, `suspend fun checkServer(url): Result<Unit>`, `suspend fun signIn(url, username, password): Result<Unit>`, `suspend fun signOut(removeLocalData: Boolean)`, `fun onUnauthorized()`.

- [ ] **Step 1: Failing repository test**

```kotlin
class SessionRepositoryTest {
    @get:Rule val server = MockWebServer()
    private val serverStore = FakeServerStore(); private val tokenStore = FakeTokenStore()
    private val repo = SessionRepository(serverStore, tokenStore, ApiClient, UnconfinedTestDispatcher())

    @Test fun `sign in logs in with a cookie, mints a token, ends the cookie session, and loads me`() = runTest {
        server.enqueue(MockResponse(code = 200, headers = headersOf("Set-Cookie", "logb_session=abc; Path=/; HttpOnly"),
            body = """{"id":1,"username":"ben","is_admin":true,"lang":"en"}"""))                 // login
        server.enqueue(MockResponse(code = 201, body = """{"id":9,"name":"LogB Android","prefix":"logb_pat_ab","created_at":"x","last_used_at":null,"token":"logb_pat_abcdef"}""")) // tokens
        server.enqueue(MockResponse(code = 200))                                                       // logout
        server.enqueue(MockResponse(body = """{"id":1,"username":"ben","is_admin":true,"lang":"en"}"""))  // me (bearer)

        assertTrue(repo.signIn(server.url("/").toString(), "ben", "correct horse").isSuccess)

        val login = server.takeRequest(); assertEquals("/api/auth/login", login.url.encodedPath)
        val mint = server.takeRequest(); assertEquals("/api/auth/tokens", mint.url.encodedPath)
        assertEquals("logb_session=abc", mint.headers["Cookie"]); assertNull(mint.headers["Authorization"])
        val logout = server.takeRequest(); assertEquals("/api/auth/logout", logout.url.encodedPath)
        val me = server.takeRequest(); assertEquals("Bearer logb_pat_abcdef", me.headers["Authorization"]); assertNull(me.headers["Cookie"])

        val s = repo.session.value as Session.SignedIn
        assertEquals("ben", s.user.username); assertEquals("logb_pat_abcdef", tokenStore.read())
    }
    @Test fun `a wrong password surfaces the servers message and stores nothing`() = runTest {
        server.enqueue(MockResponse(code = 401, body = """{"error":"unauthorized","message":"wrong username or password"}"""))
        val r = repo.signIn(server.url("/").toString(), "ben", "nope")
        assertEquals("wrong username or password", r.exceptionOrNull()!!.message); assertNull(tokenStore.read())
    }
    @Test fun `onUnauthorized drops the token but remembers the server and user id`() = runTest {
        tokenStore.write("logb_pat_x"); serverStore.write("https://logb.example", lastUserId = 1)
        repo.restore()
        repo.onUnauthorized()
        assertIs<Session.SignedOut>(repo.session.value); assertNull(tokenStore.read()); assertEquals("https://logb.example", serverStore.serverUrl())
    }
}
```

- [ ] **Step 2: Run, expect compile failure**

- [ ] **Step 3: Stores**

`ServerStore` (DataStore preferences): `serverUrl: Flow<String?>`, `lastUserId`, `lastUsername`, plus `write`. `KeystoreCipher`: an AES-256-GCM key in `AndroidKeyStore` under alias `logb-token`, `encrypt(bytes): ByteArray` (IV prefixed), `decrypt`. `TokenStore` stores the ciphertext base64 in DataStore under `token`; `read(): String?`, `write(token)`, `clear()`. Both stores are behind small interfaces so the test above can use `FakeServerStore` / `FakeTokenStore`.

- [ ] **Step 4: `SessionRepository`**

```kotlin
suspend fun signIn(url: String, username: String, password: String): Result<Unit> = runCatching {
    val base = normalize(url)                           // https:// if no scheme, trailing slash
    val jar = InMemoryCookieJar()
    val cookieApi = ApiClient.create(base, tokenProvider = { null }, cookieJar = jar)
    cookieApi.login(Credentials(username, password))
    val minted = cookieApi.createToken(NewToken("LogB Android · ${Build.MODEL}"))
    runCatching { cookieApi.logout() }                  // best effort: the token is what matters
    val bearerApi = ApiClient.create(base, tokenProvider = { minted.token })
    val me = bearerApi.me()
    tokenStore.write(minted.token); serverStore.write(base, me.id, me.username); tokenIdStore = minted.id
    _session.value = Session.SignedIn(base, me, minted.token)
}
```

`restore()` runs at app start: server + token present → `SignedIn` with a cached `User` (username and id from `ServerStore`; `me()` is refreshed opportunistically on the next sync, not on the way to a screen). `signOut(removeLocalData)`: revoke best-effort, clear token, `SignedOut`; if asked, `DatabaseProvider.delete(...)`. `onUnauthorized()` is called by the sync engine on `UnauthorizedException`.

`NetworkModule` now provides `LogbApi` from the session's server URL and `TokenStore`; `DatabaseModule` provides `LogbDatabase` from the session's `(serverUrl, user.id)`. Because both depend on a session that changes, provide a `@Singleton class ActiveAccount` holding the current `LogbApi` and `LogbDatabase` (recreated when the session changes) and inject that.

- [ ] **Step 5: Screens**

`ServerScreen`: title "Your LogB server", one URL field (`KeyboardType.Uri`), *Continue*; on submit `checkServer` hits `/api/health`; errors inline ("Could not reach the server"). `SignInScreen`: server shown as a muted line with *Change*, username, password (`PasswordVisualTransformation`, toggle), *Sign in*, inline error from the server's message, progress on the button while in flight. Both use `main.auth`'s centred narrow-column feel: `Column(Modifier.widthIn(max = 400.dp).fillMaxHeight(), verticalArrangement = Center)`.

Strings: `server_title`, `server_hint`, `server_continue`, `server_unreachable`, `signin_title`, `username`, `password`, `sign_in`, `change_server` — EN and DE, taken from `en.ts`/`de.ts` where present (`login.*` keys).

- [ ] **Step 6: Tests pass; keystore test on device; commit**

```bash
./gradlew :app:testDebugUnitTest --tests 'dev.logb.android.core.auth.*'
./gradlew :app:connectedDebugAndroidTest --tests 'dev.logb.android.core.auth.TokenStoreTest'
git add -A && git commit -m "feat: server URL, password sign-in that mints a token, keystore-wrapped token store"
```

---

### Task 6: Bootstrap — snapshot into the mirror

**Files:**
- Create: `core/sync/Bootstrap.kt`, `core/sync/RowMapper.kt`
- Create: `app/src/test/resources/fixtures/bootstrap.json`
- Test: `core/sync/BootstrapTest.kt`

**Interfaces:**
- Produces: `class Bootstrap(db: LogbDatabase) { suspend fun apply(snapshot: BootstrapResult, deviceId: String) }`.

- [ ] **Step 1: Capture a fixture from a real server**

Start a phase-0 server on a temp dir, create one user, two objects (one nested), an activity with a photo, a reminder, then:

```bash
curl -s -c c.txt -X POST localhost:8080/api/auth/login -H 'content-type: application/json' -d '{"username":"ben","password":"correct horse"}'
curl -s -b c.txt localhost:8080/api/sync/bootstrap | python3 -m json.tool > app/src/test/resources/fixtures/bootstrap.json
```

Check the fixture in; it is a few kilobytes.

- [ ] **Step 2: Failing test**

```kotlin
class BootstrapTest {
    private val db = TestDatabase.inMemory()
    private val snapshot: BootstrapResult = json.decodeFromString(javaClass.getResource("/fixtures/bootstrap.json")!!.readText())

    @Test fun `every row lands keyed by uuid with its server id and references by uuid`() = runTest {
        Bootstrap(db).apply(snapshot, deviceId = "dev-1")
        val objects = snapshot.objects.map { it["client_uuid"]!!.jsonPrimitive.content }
        for (uuid in objects) assertNotNull(db.objectDao().get(uuid))
        val garage = db.objectDao().get(uuidOf(snapshot.objects, name = "Garage"))!!
        assertEquals(uuidOf(snapshot.objects, name = "House"), garage.parentUuid)
        val a = db.activityDao().get(snapshot.activities[0]["client_uuid"]!!.jsonPrimitive.content)!!
        assertEquals(snapshot.activities[0]["id"]!!.jsonPrimitive.long, a.serverId)
        val att = db.attachmentDao().forObject(a.objectUuid).first().single()
        assertEquals(a.uuid, att.activityUuid); assertNotNull(db.fileDao().get(att.fileUuid))
    }
    @Test fun `sync state records the cursor, epoch, device and clock offset and clears bootstrap_needed`() = runTest {
        Bootstrap(db).apply(snapshot, deviceId = "dev-1")
        val s = db.syncStateDao().get()!!
        assertEquals(snapshot.seq, s.cursorSeq); assertEquals(snapshot.epoch, s.epoch); assertEquals("dev-1", s.deviceId); assertFalse(s.bootstrapNeeded)
    }
    @Test fun `a re-bootstrap replaces server rows but keeps local-only rows and queued ops`() = runTest {
        Bootstrap(db).apply(snapshot, deviceId = "dev-1")
        db.objectDao().upsert(ObjectEntity("local-only-1", null, "Offline bike", "bike", null, null, "", null, null, null, null, null, "t", "t", null))
        db.opDao().insert(OpEntity(id = "op1", kind = "create", entity = "object", entityUuid = "local-only-1", field = null, valueJson = null, editedAt = "t"))
        db.fieldClockDao().upsert(FieldClockEntity("object", objects.first(), "name", "2026-01-01T00:00:00.000Z", "dev-1"))
        Bootstrap(db).apply(snapshot, deviceId = "dev-1")
        assertNotNull(db.objectDao().get("local-only-1")); assertEquals(1, db.opDao().pending().size)
        assertNull(db.fieldClockDao().get("object", objects.first(), "name"), "the snapshot is the truth; the clock restarts")
    }
}
```

- [ ] **Step 3: Implement `RowMapper` and `Bootstrap`**

`RowMapper` reads a `JsonObject` by column name with helpers `str`, `strOrNull`, `long`, `longOrNull`. Mapping order matters: objects → files → activities → attachments → reminders, and each references earlier tables through an `id → uuid` map built from the snapshot (`objects.parent_id`, `objects.cover_attachment_id` — attachments come later, so resolve covers in a second pass — `activities.object_id`, `attachments.object_id/activity_id/file_id`, `reminders.object_id/done_activity_id`). `apply` runs in `db.withTransaction { }`: delete every mirror row whose `server_id IS NOT NULL`, insert the mapped rows, clear `field_clock`, upsert `sync_state(cursorSeq = seq, epoch, deviceId, clockOffsetMs = parse(serverTime) - now, bootstrapNeeded = false)`.

- [ ] **Step 4: Tests pass; commit**

```bash
git add -A && git commit -m "feat: bootstrap importer maps the server snapshot into the uuid-keyed mirror"
```

---

### Task 7: Pull and apply with last-write-wins

**Files:**
- Create: `core/sync/ChangeApplier.kt`, `core/sync/PullEngine.kt`, `core/sync/Lww.kt`, `core/sync/Cascade.kt`
- Create: `app/src/test/resources/fixtures/pull-page-1.json`
- Test: `core/sync/LwwTest.kt`, `core/sync/ChangeApplierTest.kt`, `core/sync/PullEngineTest.kt`

**Interfaces:**
- Produces: `object Lww { fun wins(incomingEditedAt, incomingDevice, currentEditedAt?, currentDevice?): Boolean; fun canonical(rfc3339): String }`; `class ChangeApplier(db) { suspend fun apply(rows: List<ChangeRow>) }`; `class PullEngine(db, api, bootstrap) { suspend fun run(): PullOutcome }`; `object Cascade { suspend fun tombstoneObject(db, uuid, now); suspend fun tombstoneActivity(db, uuid, now); suspend fun tombstoneAttachment(db, uuid, now) }` returning the uuids touched.

- [ ] **Step 1: Failing `Lww` tests** — mirror `src/sync/apply.rs::wins` and `canonical_edited_at`

```kotlin
class LwwTest {
    @Test fun `newer wins, older loses, equal breaks on device id`() {
        assertTrue(Lww.wins("2026-09-01T00:00:01.000Z", "b", "2026-09-01T00:00:00.000Z", "a"))
        assertFalse(Lww.wins("2026-09-01T00:00:00.000Z", "b", "2026-09-01T00:00:01.000Z", "a"))
        assertTrue(Lww.wins("2026-09-01T00:00:00.000Z", "b", "2026-09-01T00:00:00.000Z", "a"))
        assertFalse(Lww.wins("2026-09-01T00:00:00.000Z", "a", "2026-09-01T00:00:00.000Z", "b"))
        assertTrue(Lww.wins("2026-09-01T00:00:00.000Z", "a", null, null), "an unstamped field loses to anything")
    }
    @Test fun `timestamps compare chronologically, not lexically`() {
        assertTrue(Lww.wins("2026-09-01T02:00:00+02:00", "a", "2026-08-31T23:59:59.999Z", "b"))
        assertEquals("2026-09-01T00:00:00.000Z", Lww.canonical("2026-09-01T02:00:00+02:00"))
    }
}
```

- [ ] **Step 2: Failing `ChangeApplier` tests**

```kotlin
class ChangeApplierTest {
    private val db = TestDatabase.inMemory(); private val applier = ChangeApplier(db)
    private fun row(seq: Long, entity: String, uuid: String, op: String, field: String? = null, value: String? = null,
                    at: String = "2026-09-01T00:00:00.000Z", device: String = "rest", id: Long? = 1) =
        ChangeRow(seq, entity, uuid, op, field, value, at, device, id)

    @Test fun `a create for an unknown uuid makes a placeholder that later sets fill in`() = runTest {
        applier.apply(listOf(row(1, "object", "u1", "create", id = 42),
            row(2, "object", "u1", "set", "name", "\"Golf\""), row(3, "object", "u1", "set", "type", "\"car\"")))
        val o = db.objectDao().get("u1")!!
        assertEquals(42, o.serverId); assertEquals("Golf", o.name); assertEquals("car", o.type)
    }
    @Test fun `an older set is ignored and the clock is untouched`() = runTest {
        applier.apply(listOf(row(1, "object", "u1", "create"), row(2, "object", "u1", "set", "name", "\"New\"", at = "2026-09-02T00:00:00.000Z", device = "b")))
        applier.apply(listOf(row(3, "object", "u1", "set", "name", "\"Old\"", at = "2026-09-01T00:00:00.000Z", device = "a")))
        assertEquals("New", db.objectDao().get("u1")!!.name)
        assertEquals("2026-09-02T00:00:00.000Z", db.fieldClockDao().get("object", "u1", "name")!!.editedAt)
    }
    @Test fun `integer references are translated to uuids through server ids`() = runTest {
        applier.apply(listOf(row(1, "object", "house", "create", id = 10), row(2, "object", "garage", "create", id = 11),
            row(3, "object", "garage", "set", "parent_id", "10")))
        assertEquals("house", db.objectDao().get("garage")!!.parentUuid)
    }
    @Test fun `an unresolvable reference is applied as null and asks for a bootstrap`() = runTest {
        db.syncStateDao().upsert(SyncStateEntity(deviceId = "d", bootstrapNeeded = false))
        applier.apply(listOf(row(1, "object", "garage", "create", id = 11), row(2, "object", "garage", "set", "parent_id", "999")))
        assertNull(db.objectDao().get("garage")!!.parentUuid); assertTrue(db.syncStateDao().get()!!.bootstrapNeeded)
    }
    @Test fun `a null value clears the field, whether SQL NULL or the string null`() = runTest {
        // A pushed op with an explicit null lands as SQL NULL; a REST-side clear lands as the
        // double-encoded JSON null, i.e. the four-character string "null". Both mean clear.
        applier.apply(listOf(row(1, "object", "u1", "create"), row(2, "object", "u1", "set", "purchase_price_cents", "1200"),
            row(3, "object", "u1", "set", "purchase_price_cents", null, at = "2026-09-02T00:00:00.000Z")))
        assertNull(db.objectDao().get("u1")!!.purchasePriceCents)
        applier.apply(listOf(row(4, "object", "u1", "set", "purchase_price_cents", "1300", at = "2026-09-03T00:00:00.000Z"),
            row(5, "object", "u1", "set", "purchase_price_cents", "null", at = "2026-09-04T00:00:00.000Z")))
        assertNull(db.objectDao().get("u1")!!.purchasePriceCents)
    }
    @Test fun `deleting an object tombstones its activities, attachments, reminders and descendants`() = runTest {
        applier.apply(listOf(row(1, "object", "house", "create", id = 10), row(2, "object", "garage", "create", id = 11),
            row(3, "object", "garage", "set", "parent_id", "10")))
        db.activityDao().upsert(ActivityEntity("a1", 5, "garage", "2026-01-01", "repair", "x", "", null, null, null, "t", "t", null))
        applier.apply(listOf(row(4, "object", "house", "delete", at = "2026-09-03T00:00:00.000Z")))
        assertNotNull(db.objectDao().get("garage")!!.deletedAt); assertNotNull(db.activityDao().get("a1")!!.deletedAt)
    }
    @Test fun `a set on an unknown entity is skipped, not fatal`() = runTest {
        applier.apply(listOf(row(1, "activity", "ghost", "set", "title", "\"x\"")))   // no exception
    }
    @Test fun `field names the whitelist does not know are ignored`() = runTest {
        applier.apply(listOf(row(1, "object", "u1", "create"), row(2, "object", "u1", "set", "user_id", "7")))  // no exception, nothing changes
    }
}
```

- [ ] **Step 3: Failing `PullEngine` tests** (MockWebServer)

- pages until `complete`, advancing the cursor and storing the epoch and clock offset;
- a `410` calls `Bootstrap` then pulls again from the new cursor;
- `bootstrapNeeded = true` in `sync_state` forces a bootstrap before pulling;
- an `UnauthorizedException` propagates untouched (the manager handles it).

- [ ] **Step 4: Implement**

`Lww.canonical` parses with `OffsetDateTime.parse` (fall back to `Instant.parse`), formats `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'` in UTC. `wins` compares canonical strings, then device ids.

`ChangeApplier.apply(rows)` in one `withTransaction`:

```kotlin
for (r in rows) when (r.op) {
    "create" -> ensureRow(r.entity, r.entityUuid, r.entityId)     // insert placeholder or set serverId
    "delete" -> Cascade.tombstone(db, r.entity, r.entityUuid, now = Lww.canonical(r.editedAt))
    "set" -> {
        val field = r.field ?: continue
        val spec = FieldSpecs[r.entity]?.get(field) ?: continue    // whitelist mirrors src/sync/mod.rs
        if (!exists(r.entity, r.entityUuid)) continue
        val current = db.fieldClockDao().get(r.entity, r.entityUuid, field)
        if (!Lww.wins(r.editedAt, r.deviceId, current?.editedAt, current?.deviceId)) continue
        val value = r.value?.let { json.parseToJsonElement(it) }?.takeUnless { it is JsonNull }   // double-encoded; SQL NULL or "null" = clear
        val bound = spec.bind(value, resolver)                      // Text → String?, Integer → Long?, Ref(table) → uuid?
        if (bound is Unresolved) { markBootstrapNeeded(); writeNull(...) } else writeField(r.entity, r.entityUuid, field, bound)
        db.fieldClockDao().upsert(FieldClockEntity(r.entity, r.entityUuid, field, Lww.canonical(r.editedAt), r.deviceId))
    }
}
```

`FieldSpecs` is the whitelist from `~/repo/logb/src/sync/mod.rs`, with the reference fields typed: `object.parent_id → Ref(objects)`, `object.cover_attachment_id → Ref(attachments)`, `reminder.done_activity_id → Ref(activities)`. `writeField` is one `@Query("UPDATE objects SET name = :v WHERE uuid = :u")`-style DAO method per field — generated by hand, one per whitelisted field, so a typo is a compile error rather than a runtime string. Also bump `updated_at` for objects and activities as the server does.

`Cascade` ports `record::cascade_object` / `cascade_activity` / `clear_cover_of`: object → descendants (recursive), their activities, attachments, reminders; activity → its attachments, clear covers pointing at them, unlink `done_activity_uuid`; attachment → clear covers. One `now` for the whole cascade. Phase 2 reuses this for local deletes.

`PullEngine.run()`:

```kotlin
val state = db.syncStateDao().get() ?: return PullOutcome.NeedsBootstrap
if (state.bootstrapNeeded) bootstrap.apply(api.bootstrap(), state.deviceId)
while (true) {
    val s = db.syncStateDao().get()!!
    val page = try { api.pull(s.cursorSeq, s.epoch, 1000) } catch (e: GoneException) { bootstrap.apply(api.bootstrap(), s.deviceId); continue }
    applier.apply(page.changes)
    db.syncStateDao().upsert(s.copy(cursorSeq = page.nextSeq, epoch = page.epoch, clockOffsetMs = offset(page.serverTime), lastSyncedAt = nowIso()))
    if (page.complete) break
}
```

- [ ] **Step 5: All sync tests pass; commit**

```bash
./gradlew :app:testDebugUnitTest --tests 'dev.logb.android.core.sync.*'
git add -A && git commit -m "feat: pull engine applies the change feed with the server's last-write-wins rule"
```

---

### Task 8: Sync manager, scheduling, status, first-run bootstrap screen

**Files:**
- Create: `core/sync/SyncManager.kt`, `core/sync/SyncStatus.kt`, `core/sync/SyncWorker.kt`, `core/sync/Connectivity.kt`, `di/SyncModule.kt`
- Create: `feature/onboarding/BootstrapScreen.kt` (+ ViewModel)
- Modify: `LogbApp.kt` (schedule periodic work; foreground observer)
- Test: `core/sync/SyncManagerTest.kt`

**Interfaces:**
- Produces: `SyncManager.status: StateFlow<SyncStatus>`, `fun requestSync(reason: SyncReason)`, `suspend fun syncNow(): Result<Unit>`; `sealed interface SyncStatus { Idle(lastSyncedAt: String?), Syncing, Offline(pending: Int), Pending(n: Int), Failed(dead: Int), SignedOut }`.

- [ ] **Step 1: Failing tests**

- two overlapping `requestSync` calls run one pull, not two (a `Mutex`);
- `UnauthorizedException` from the engine calls `SessionRepository.onUnauthorized()` and status becomes `SignedOut`;
- an `IOException` with no connectivity yields `Offline(pending = opDao.pending().size)`;
- status is `Idle(lastSyncedAt)` after a successful run.

- [ ] **Step 2: Implement**

`SyncManager` holds a `Mutex`; `syncNow` = (phase 2 inserts push here) → `pullEngine.run()` → status. `requestSync` launches on an application scope with a 2 s debounce per reason. Triggers: `ProcessLifecycleOwner` `ON_START` → `requestSync(Foreground)`; `SyncWorker` (`@HiltWorker`, `CoroutineWorker`) calls `syncNow` and returns `retry()` on `IOException`; `LogbApp` enqueues `PeriodicWorkRequestBuilder<SyncWorker>(15, MINUTES)` with `NetworkType.CONNECTED`, `KEEP` policy. `Connectivity` wraps `ConnectivityManager` into `isOnline: StateFlow<Boolean>` and `isUnmetered`.

`BootstrapScreen`: shown by the nav host when the session is `SignedIn` and `sync_state.bootstrapNeeded` is true (or no `sync_state` row): the mark, "Getting your logbook…", an indeterminate progress bar, an error state with *Retry* and *Sign out*. It calls `syncNow()` once on entry.

- [ ] **Step 3: Tests pass; commit**

```bash
git add -A && git commit -m "feat: sync manager with foreground, periodic and manual triggers, status flow, first-run bootstrap"
```

---

### Task 9: Reminder rules and presenter (domain ports)

**Files:**
- Create: `core/domain/ReminderRules.kt`, `core/domain/ReminderPresenter.kt`, `core/domain/ObjectTypes.kt`, `core/domain/TimelineFold.kt`, `core/domain/Clock.kt` (`today(zone)`)
- Test: `core/domain/ReminderRulesTest.kt`, `ReminderPresenterTest.kt`, `ObjectTypesTest.kt`, `TimelineFoldTest.kt`

**Interfaces:**
- Produces: `ReminderRules.isDue(today, currentCounter, dueDate, dueCounter, snoozedUntil)`, `isUpcoming(today, …, withinDays)`, `Every.fromParts(n, unit)`, `readingNextDue(start, lastReading, every)`, `readingStatus(today, start, lastReading, every, snoozedUntil): Pair<Boolean, LocalDate?>`, `nextDue(baseDate, baseCounter, dueCounter, repeat): Pair<LocalDate?, Long?>?`, `snoozedDate`, `daysUntil`, `counterUntil`; `ReminderPresenter.present(reminder, objectCounterUnit, currentCounter, lastReadingDate, today): ReminderView(due, daysUntil, counterUntil, nextDueDate, estimatedDueDate = null)`; `ObjectTypes.categoriesFor(type, current?)`, `ObjectTypes.icon(type)`; `TimelineFold.fold(activities): List<TimelineItem>`.

- [ ] **Step 1: Translate the Rust tests**

Open `~/repo/logb/src/domain/reminder.rs` and copy every `#[test]` in its `mod tests` into `ReminderRulesTest.kt`, one `@Test` per Rust test with the same name in backticks. Then the reading-reminder cases from `~/repo/logb/tests/reading_reminders.rs` that exercise `reading_status` (via the API there; here directly). Then `~/repo/logb/frontend/src/lib/timeline-fold.ts`'s tests (`frontend/src/lib/*.test.ts`) into `TimelineFoldTest.kt`, and `object-types.ts`'s into `ObjectTypesTest.kt`.

- [ ] **Step 2: Run, expect failure**

- [ ] **Step 3: Port**

Line for line, `chrono::NaiveDate` → `java.time.LocalDate`, `checked_add_months` → `plusMonths` (which clamps to month end, matching chrono), `checked_add_days` → `plusDays`. `MAX_EVERY = 60`. `Repeat(months: Int?, counter: Long?)`. Keep the doc comments — they explain edge cases the tests pin.

`ReminderPresenter.present` ports `ReminderOut::build` without the `usage` argument (phase 4 adds `estimated_due_date` when `Insights.kt` exists). `dueCount(objectUuid)` for cards: open reminders of the object presented with today, count `due`.

- [ ] **Step 4: Tests pass; commit**

```bash
git add -A && git commit -m "feat: reminder rules, presenter, object types and timeline folding ported from the server"
```

---

### Task 10: Navigation shell and the Objects screen

**Files:**
- Create: `navigation/Routes.kt`, `navigation/AppNavHost.kt`, `navigation/BottomBar.kt`
- Create: `feature/objects/{ObjectsScreen,ObjectsViewModel,ObjectCard,DueBanner}.kt`, `core/design/components/SyncLine.kt`
- Modify: `MainActivity.kt`, strings
- Test: `navigation/RoutesTest.kt` (active-destination rule), `feature/objects/ObjectsViewModelTest.kt`

- [ ] **Step 1: Routes**

```kotlin
@Serializable object Server; @Serializable object SignIn; @Serializable object Bootstrapping
@Serializable object Objects; @Serializable data class ObjectDetail(val uuid: String, val tab: String = "timeline")
@Serializable object Search; @Serializable object Settings
@Serializable object Appearance; @Serializable object Account; @Serializable object SyncSettings; @Serializable object About
```

`activeDestination(route): Destination?` — `Objects` for `Objects` and `ObjectDetail`, `Settings` for `Settings` and its children, `Search` for `Search`; tested like the web shell's `nav.ts`.

- [ ] **Step 2: Nav host and bottom bar**

`AppNavHost` observes `session` and `bootstrapNeeded`: `NeedsServer → Server`, `SignedOut → SignIn`, `SignedIn && bootstrapNeeded → Bootstrapping`, else the main graph with a `Scaffold` whose `bottomBar` is `NavigationBar` with three `NavigationBarItem`s (`Icons.Outlined.Inventory2` — or the LogB `object` glyph — `Icons.Outlined.Search`, `Icons.Outlined.Settings`), labels from strings, `selected` from `activeDestination`. Screens without the bar: the three onboarding routes. Predictive back works by default with `enableOnBackInvokedCallback`.

- [ ] **Step 3: `ObjectsViewModel`**

```kotlin
data class ObjectsUiState(val cards: List<ObjectCard>, val archived: Boolean, val dueCount: Int, val sync: SyncStatus, val currency: String)
data class ObjectCard(val uuid: String, val name: String, val type: String, val counter: Long?, val counterUnit: String?,
                      val totalCostCents: Long, val lastActivityDate: String?, val dueCount: Int, val coverSha: String?)
```

`combine(objectDao.roots(archived), reminderDao.allOpen(), syncManager.status, settings.currency)` → for each object `objectDao.stats(uuid)` + `ReminderPresenter.dueCount`. Currency comes from `settings` — phase 1 stores the instance currency in `ServerStore` at sign-in from `GET /settings` (add `settings()` to `LogbApi`), defaulting to `EUR`. Test with the in-memory database: two objects, one due reminder → `dueCount == 1` and the right card carries it; toggling `archived` swaps the list.

- [ ] **Step 4: Screen**

`ObjectsScreen`: `LogbTopBar(title = "Objects")` with the `SyncLine` beneath (`status` → "Synced just now" / "3 changes waiting" / "Offline" / "1 change couldn't be saved" → navigates to `SyncSettings`); `DueBanner(count)` → (phase 1) navigates to the first due object's Reminders tab, listing comes with phase 2's due list; `FilterChip("Archived")`; `PullToRefreshBox` calling `syncNow`; `LazyColumn` of `ObjectCard`s (type icon in a tonal circle, name `titleMedium`, second line `figureSmall` counter · cost, third line muted "Last entry 3 Jun", `DueBadge` top-right, `Log` `TextButton` at the end of the card — disabled in phase 1 with a tooltip "coming with the next release"? No: omit it until phase 2; the card is tappable as a whole); `EmptyState` with "No objects yet" and no action in phase 1; `FloatingActionButton` omitted until phase 2. Card spacing 12 dp, content padding 16 dp, `contentPadding` bottom for the bar.

Cover thumbnails: `AsyncImage` with Coil, `model = "$serverUrl/api/files/$coverFileServerId/thumb"` through an `OkHttpNetworkFetcher` that carries the bearer header (Coil's OkHttp client is the `ApiClient`'s), disk cache enabled — recently seen thumbnails render offline; phase 3 replaces this with the blob store.

- [ ] **Step 5: Run on the device**

```bash
./gradlew :app:installDebug
```

Sign in against the local phase-0 server (`http://10.0.2.2:8080` on the emulator, the LAN address on the phone — which needs https or a debug override; for the phone use `adb reverse tcp:8080 tcp:8080` and `http://localhost:8080`). Expected: the objects appear; airplane mode on; kill and reopen; they are still there.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat: navigation shell with bottom bar, Objects screen from the mirror, pull-to-refresh"
```

---

### Task 11: Object detail with four tabs

**Files:**
- Create: `feature/objects/{ObjectDetailScreen,ObjectDetailViewModel}.kt`, `feature/objects/tabs/{TimelineTab,DocumentsTab,RemindersTab,InfoTab}.kt`, `feature/objects/Breadcrumb.kt`
- Test: `feature/objects/ObjectDetailViewModelTest.kt`

- [ ] **Step 1: ViewModel**

`ObjectDetailUiState(object, stats, ancestors, children: List<ObjectCard>, timeline: List<TimelineItem>, categoryFilter, categories, documents: List<AttachmentWithFile>, openReminders: List<ReminderView>, doneReminders, currency)`. Timeline: `activityDao.timeline(uuid)` → filter by category → `TimelineFold.fold` → grouped by year (`groupBy { it.date.take(4) }`). Attachments per activity via `attachmentDao.forActivities`. Tests: year grouping, category filter keeps the current entry's category in the chip list (`categoriesFor(type, current)`), readings fold.

- [ ] **Step 2: Screen**

- `LogbTopBar(title = object.name, onBack)`; `Breadcrumb` ("House › Garage") under it when `ancestors` is non-empty, each crumb navigates.
- Header: `Row` of `StatFigure`s — total spent, entries, counter (with unit), owned since (`purchase_date`) — in a `Card`, wrapping into two rows on narrow widths (`FlowRow`).
- `PrimaryTabRow` with Timeline / Documents / Reminders / Info; the tab is part of the route so back returns to the right one.
- Timeline: `LazyColumn` with sticky year headers; entry row = date (`figureSmall`, tabular) · category `AssistChip` · title; second line cost and counter aligned in a column; a `LazyRow` of thumbnails when the entry has photos; folded readings render as one row "3 readings · 84,000 → 84,210 km" that expands. Category filter chips in a horizontal scroll with a fade at the edge.
- Documents: `LazyVerticalGrid(3 columns)` of thumbnails (photos) or a document tile with the file name; tap opens the original in the system viewer via an `ACTION_VIEW` on the server URL with the bearer header — not possible for an external app; in phase 1 tap does nothing for documents and opens a full-screen `AsyncImage` for photos; phase 3 adds the real viewer.
- Reminders: open list with `DueBadge`/"in 12 days"/"in 1,200 km", snoozed shown muted with "snoozed until"; done list collapsed under "Done (n)".
- Info: description, purchase date and price, counter/fuel units, "Contents" list of children as small cards navigating into them, and nothing else in phase 1 (edit/archive/delete arrive with phase 2, insights with phase 4).

- [ ] **Step 3: Device check and commit**

```bash
git add -A && git commit -m "feat: object detail with timeline, documents, reminders and info tabs from the mirror"
```

---

### Task 12: Search and Settings

**Files:**
- Create: `feature/search/{SearchScreen,SearchViewModel}.kt`
- Create: `feature/settings/{SettingsHubScreen,AppearanceScreen,AccountScreen,SyncScreen,AboutScreen}.kt` + ViewModels, `core/prefs/AppearancePrefs.kt`
- Test: `feature/search/SearchViewModelTest.kt`, `feature/settings/SettingsRowsTest.kt`

- [ ] **Step 1: Search**

`SearchViewModel`: `query: StateFlow<String>` debounced 150 ms → `searchDao.objects(pattern)` and `searchDao.activities(pattern)`; empty query → empty results and a hint "Search names, descriptions, titles and notes". Object hit shows "Main light — in Garage" (join parent name in the DAO query). Tests: `like_pattern` escaping (`50%` finds the literal), an object hit carries its parent's name.

`SearchScreen`: `SearchBar`-style `OutlinedTextField` at the top with a clear button (`contentDescription = "Clear"`), two sections with headers "Objects" and "Entries", `EmptyState` "Nothing matches".

- [ ] **Step 2: Settings hub**

Rows are a pure function, as in the web spec: `settingsRows(session, appearance, syncStatus): List<SettingsRow(route, icon, title, value?)>` → Appearance (`Dark · EN`), Account (username), Sync (`Synced just now` / `3 waiting` / `1 failed`), About (version). Test the value strings. The hub renders them as a `ListItem` list with chevrons.

- Appearance: theme (System / Light / Dark) as `SegmentedButton`, language (System / English / Deutsch) applying `AppCompatDelegate.setApplicationLocales`, `Switch` "Use system colours" (dynamic colour). Stored in `AppearancePrefs` (DataStore).
- Account: server URL, username, *Sign out* with a dialog offering "Also remove local data" (`Checkbox`).
- Sync: last synced, cursor, pending count (0 in phase 1), *Sync now*, and the failed-ops list placeholder (empty in phase 1; phase 2 fills it), "Device id" muted for support.
- About: version, link to the server's own About (`/settings/about` in the browser), licences via `OssLicensesMenuActivity` or a static text — static text listing the libraries is enough.

- [ ] **Step 3: Strings parity test**

`app/src/test/kotlin/dev/logb/android/StringsParityTest.kt` parses `src/main/res/values/strings.xml` and `values-de/strings.xml` with `javax.xml.parsers` and asserts the two key sets are equal, naming the difference.

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "feat: local search, settings hub with appearance, account, sync and about"
```

---

### Task 13: UI tests, contract test, README

**Files:**
- Create: `app/src/androidTest/kotlin/dev/logb/android/{HiltTestRunner,OnboardingTest,ObjectsScreenTest}.kt`
- Create: `app/src/contractTest/kotlin/dev/logb/android/contract/SyncContractTest.kt` (a separate JVM source set wired in `app/build.gradle.kts` as `contractTest`)
- Create: `README.md`, `docs/smoke-checklist.md`
- Modify: `.github/workflows/android.yml`

- [ ] **Step 1: Compose UI tests**

`OnboardingTest`: with a MockWebServer bound in the test (Hilt test module replacing `ApiClient`'s base URL), enter a server, sign in, land on Objects with the seeded object's name on screen. `ObjectsScreenTest`: seed the in-memory database through Hilt, assert the card shows name, counter and cost, the archived chip hides and shows, tapping opens detail with the timeline tab selected.

Run: `./gradlew :app:connectedDebugAndroidTest` on the connected device or the `Medium_Phone` AVD.

- [ ] **Step 2: Contract test against the real server**

`SyncContractTest` (JVM, `contractTest` source set, skipped unless `-PlogbBin=/path/to/logb` is given):

1. Start `logb` with `LOGB_DATA_DIR=<temp> LOGB_PORT=<free port>`; wait for `/api/health`.
2. `POST /auth/setup` a user; through REST create House → Garage → Light, an activity with a PNG attachment, a reminder marked done against it.
3. `SessionRepository.signIn` → `Bootstrap` → assert the mirror matches (`ObjectDao.ancestors("light") == [House, Garage]`, attachment's file present, reminder done).
4. Through REST: rename Garage, delete the activity (which clears the cover and unlinks the reminder — phase 0's logged cleanups), create a new reminder.
5. `PullEngine.run()` → assert the mirror reflects all four, and `field_clock` for `object/garage/name` carries the REST edit's time.
6. Stop the server, `PullEngine.run()` again → `IOException`, mirror unchanged.

Gradle: `tasks.register<Test>("contractTest") { useJUnit(); testClassesDirs = sourceSets["contractTest"].output.classesDirs; classpath = sourceSets["contractTest"].runtimeClasspath; systemProperty("logb.bin", project.findProperty("logbBin") ?: "") }`.

Locally: `cd ~/repo/logb && cargo build --release` then `./gradlew :app:contractTest -PlogbBin=$HOME/repo/logb/target/release/logb`.

CI: a second job checks out `13/logb` at the phase-0 tag beside this repo, `cargo build --release` (with `frontend/dist` stubbed), and runs the task.

- [ ] **Step 3: README and smoke checklist**

`README.md`: what it is, screenshots placeholder, requirements (server ≥ the phase-0 release), build (`JAVA_HOME` note, `local.properties`), run on a device with `adb reverse`, tests (unit, connected, contract), architecture pointer to the spec, phases and what this phase ships.

`docs/smoke-checklist.md`: sign in on the phone; airplane mode; kill the app; reopen; browse two objects, a timeline with photos, a reminder; disable airplane mode; edit an object in the browser; pull to refresh; the edit appears; sign out keeping data; sign in again; no bootstrap screen.

- [ ] **Step 4: Full verification and commit**

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew :app:connectedDebugAndroidTest
./gradlew :app:contractTest -PlogbBin=$HOME/repo/logb/target/release/logb
git add -A && git commit -m "test: UI tests, contract test against the real server, README and smoke checklist"
```

- [ ] **Step 5: Finish the branch**

Use superpowers:finishing-a-development-branch. Then write the phase 2 plan (offline writes) from the spec's *Identity, creates, and the op queue* section.
