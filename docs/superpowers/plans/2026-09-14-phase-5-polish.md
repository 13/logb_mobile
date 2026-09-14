# Phase 5: Polish — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The last items the spec lists: a daily local reminder notification, an optional biometric / device-credential lock, launcher shortcuts, a screenshot suite, release signing with the user's keystore, CI workflows, and a README with screenshots.

**Architecture:** Every feature reads the mirror the earlier phases built; nothing new talks to the server. Notifications are one WorkManager job a day that reuses `DueListModel`. The lock is a gate in `MainActivity` over the existing content, driven by a pure `LockPolicy`. Shortcuts and the share target share one intent inbox. Screenshots are Roborazzi goldens over the state-driven `*Content` composables at phone width in both themes. Signing reads the user's global `ANDROID_KEYSTORE*` variables, with a `keystore.properties` fallback; CI mirrors apexweather's workflows.

**Tech Stack:** As phase 4, plus `androidx.biometric:biometric` 1.1.0 (already in the catalog), Roborazzi 1.74.0 (apexweather's version, in the Gradle cache), `androidx.core` notifications.

Spec: `docs/superpowers/specs/2026-09-14-logb-android-design.md`, *Notifications*, *Auth* item 5, *Interface › Settings hub*, *Testing*, phase 5.

## Global Constraints

- Notifications are local: computed from the mirror, one notification on a "Reminders" channel, "Golf: Oil change due · 2 more", tap opens the due list, nothing sent anywhere. Default hour 08:00; the digest covers what is due or comes due within seven days.
- Lock is off by default; `BIOMETRIC_WEAK | DEVICE_CREDENTIAL`; the switch is greyed with a reason when the device offers neither. The lock engages when the app has been in the background for over 60 s (a camera or picker round trip must not lock the person out) and on every cold start.
- The bottom bar and routes stay as they are; shortcuts and the share target enter through `MainActivity` and one inbox.
- Screenshot goldens live in `app/src/test/screenshots/`, recorded at `w400dp-h800dp-xhdpi`, light and dark; CI verifies, never records.
- Secrets never land on disk in the repo: signing reads `ANDROID_KEYSTORE`, `ANDROID_KEYSTORE_PASS`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASS` from the environment, or an untracked `keystore/keystore.properties`; without either, release signs with the debug key.
- Lint clean, unit tests green, EN and DE strings before every commit. Release APK installs and signs in on the phone.

## File map

```
core/notify/NotificationPrefs.kt      enabled, hour, minute (DataStore)
core/notify/Digest.kt                 pure: DigestText(title, body) from due items; nextRun(now, hour, minute)
core/notify/ReminderNotifier.kt       channel, permission check, post/cancel
core/notify/DigestWorker.kt           HiltWorker: DueListModel(7 days) → notifier; schedule(context, hour, minute)
feature/settings/NotificationsScreen.kt  switch + time picker, permission request on API 33+
core/auth/LockPrefs.kt                enabled (DataStore)
core/auth/LockPolicy.kt               pure: shouldLock(enabled, backgroundedAt, now, grace); availability from BiometricManager code
feature/lock/LockScreen.kt            locked screen + BiometricPrompt launch
MainActivity.kt                       lock gate over the session content; intent inbox for shortcuts
feature/share/ShareInbox.kt           + LaunchTarget (Due, Search, NewObject) from shortcut intents
res/xml/shortcuts.xml                 three static shortcuts
app/src/test/.../screenshot/*.kt      Roborazzi tests; app/src/test/screenshots/*.png goldens
app/build.gradle.kts                  signing configs, archivesName, version from -P, roborazzi plugin
.github/workflows/{ci,release}.yml
docs/screenshots/*.png, README.md, docs/smoke-checklist.md
```

---

### Task 1: Daily reminder digest
**Files:** create `core/notify/{NotificationPrefs,Digest,ReminderNotifier,DigestWorker}.kt`, `feature/settings/NotificationsScreen.kt`; modify `feature/settings/SettingsRows.kt` (`SettingsPage.Notifications` with value "08:00" / "off"), `SettingsScreens.kt` (hub row, icon `Notifications`), `SettingsViewModel.kt`, `navigation/Routes.kt` (`Notifications` under Settings), `AppNavHost.kt`, `LogbApp.kt` (schedule on create from prefs), `AndroidManifest.xml` (`POST_NOTIFICATIONS`), `MainActivity.kt` (extra `open=due` → DueList), strings; test `core/notify/DigestTest.kt`.
**Produces:**
```kotlin
data class DigestText(val title: String, val body: String)
object Digest {
    /** "Golf: Oil change due · 2 more" — the first item's object and title, the rest counted. Null when nothing is due or upcoming. */
    fun text(items: List<DueItem>, dueWord: String, upcomingWord: (Long) -> String, more: (Int) -> String): DigestText?
    /** The next occurrence of hour:minute strictly after `now`. */
    fun nextRun(now: LocalDateTime, hour: Int, minute: Int): LocalDateTime
}
```
- [ ] Tests: `text` with one due item gives "Golf: Oil change due" and no body; with three gives "… · 2 more" and a body listing the rest; an upcoming-only item says "in 3 days"; empty gives null; `nextRun` today at 08:00 when now is 07:59, tomorrow when now is 08:00.
- [ ] Worker: `DueListModel(db).items(withinDays = 7).first()` → `ReminderNotifier.post(text)` or `cancel()`; unique periodic work 24 h with `initialDelay = nextRun - now`; rescheduled (`REPLACE`) when the hour changes or the switch turns on; cancelled when off. Tap intent: `MainActivity` with `EXTRA_OPEN = "due"`.
- [ ] Settings › Notifications: switch (asks `POST_NOTIFICATIONS` on 33+, stays off when refused), time row opening a `TimePickerDialog`, hub row value.
- [ ] Commit `feat: daily reminder digest as a local notification`.

### Task 2: Biometric lock
**Files:** create `core/auth/{LockPrefs,LockPolicy}.kt`, `feature/lock/LockScreen.kt`; modify `MainActivity.kt`, `RootViewModel.kt` (lock state), `feature/settings/SettingsScreens.kt` (Account: lock switch with availability reason), strings; test `core/auth/LockPolicyTest.kt`.
**Produces:**
```kotlin
object LockPolicy {
    const val GRACE_MS = 60_000L
    fun shouldLock(enabled: Boolean, backgroundedAt: Long?, now: Long): Boolean   // enabled && (backgroundedAt == null || now - backgroundedAt > GRACE_MS)
    fun availability(canAuthenticate: Int): Availability   // Available, NoHardware, NoneEnrolled, Unavailable
}
```
- [ ] Tests: disabled never locks; enabled with no background stamp (cold start) locks; 30 s in background does not, 61 s does; availability mapping for `BIOMETRIC_SUCCESS`, `BIOMETRIC_ERROR_NONE_ENROLLED`, `BIOMETRIC_ERROR_NO_HARDWARE`.
- [ ] `RootViewModel`: `locked: StateFlow<Boolean>`; `onBackground()` stamps, `onForeground()` evaluates, `unlock()` clears. `MainActivity` shows `LockScreen` over the signed-in content when locked; `LockScreen` launches `BiometricPrompt` (`BIOMETRIC_WEAK or DEVICE_CREDENTIAL`, title *Unlock LogB*) on show and on the *Unlock* button; a failed or cancelled prompt stays locked.
- [ ] Account screen: *Lock with biometrics or screen lock* switch; disabled with "No screen lock is set up on this device" when unavailable.
- [ ] Commit `feat: optional biometric or screen-lock gate`.

### Task 3: Launcher shortcuts
**Files:** create `res/xml/shortcuts.xml`, `res/drawable/ic_shortcut_{due,search,add}.xml`; modify `AndroidManifest.xml` (`<meta-data android:name="android.app.shortcuts">`), `feature/share/ShareInbox.kt` (`LaunchTarget` flow from action `dev.logb.android.action.OPEN`, extra `target`), `AppNavHost.kt` (navigate on a target), strings (short/long labels); test `feature/share/LaunchTargetTest.kt` (parsing an intent's extra).
- [ ] Three static shortcuts: *Due reminders* → DueList, *Search* → Search, *New object* → ObjectForm. The notification tap reuses the same action with `target=due`.
- [ ] Commit `feat: launcher shortcuts`.

### Task 4: Screenshot suite
**Files:** modify `gradle/libs.versions.toml` (roborazzi 1.74.0 + plugin), `app/build.gradle.kts` (plugin, test deps); create `app/src/test/kotlin/dev/logb/android/screenshot/ScreensScreenshotTest.kt`, goldens in `app/src/test/screenshots/`.
- [ ] One test per screen and theme over state-driven composables: `ObjectsContent` (three cards, a due banner, the offline sync line), `StatsContent` (the phase-4 fixture), `InsightsSection` (car), `DueListContent`, `LockScreen`, `ServerScreen` content, Settings hub rows; each captured in `LogbTheme(darkTheme = false)` and `true` at `w400dp-h800dp-xhdpi`. Record with `./gradlew :app:recordRoborazziDebug`, look at every PNG, then `verifyRoborazziDebug` passes.
- [ ] Commit `test: screenshot goldens of every state-driven screen in both themes`.

### Task 5: Release signing and versioning
**Files:** modify `app/build.gradle.kts` (signing configs, `archivesName = "LogB"`, `versionName`/`versionCode` overridable by `-PversionName`/`-PversionCode`, git hash in `BuildConfig` for About), `proguard-rules.pro` (kotlinx.serialization + Retrofit keep rules), `.gitignore` (`keystore/`), `feature/settings/SettingsScreens.kt` (About shows the commit).
- [ ] `./gradlew :app:assembleRelease` with the user's environment signs with `~/sync/AndroidKeystore/androidkeystorenew.jks` (`apksigner verify --print-certs` shows CN=Ben); without the variables it signs with the debug key. Install the release APK on the phone (uninstall the debug build first: different signature), sign in, open an object, Statistics, take a photo: R8 broke nothing.
- [ ] Commit `build: release signing from the environment, versioned outputs`.

### Task 6: CI
**Files:** create `.github/workflows/ci.yml` (unit tests, lint, `verifyRoborazziDebug`, assemble debug + release, contract job checking out `13/logb` beside the repo and building it with `cargo build --release --locked`, artifacts), `.github/workflows/release.yml` (tag `v*` → decode `LOGB_KEYSTORE_BASE64`, `-PversionName`, signed APK attached to a GitHub release).
- [ ] Both workflows strip `org.gradle.java.home` as apexweather's do. No remote exists yet; the workflows are verified by `act`-free reading and by running the same Gradle tasks locally.
- [ ] Commit `ci: unit, lint, screenshots, contract test against a real logb, release workflow`.

### Task 7: README with screenshots, docs, device check
- [ ] Phone screenshots (light, German locale as the phone is) of Objects, an object's timeline, the Statistics screen, the Info tab insights, the due list and the lock screen, scaled to 360 px wide under `docs/screenshots/`; README gets a gallery, the notification / lock / shortcut features, the signing and CI sections; `docs/smoke-checklist.md` gains phase 5 checks; spec status "phases 0–5 built"; this plan's status header; memory.
- [ ] Device check: turn the digest on at the next minute and see the notification arrive and open the due list; turn the lock on, background the app for over a minute, reopen: the prompt shows; long-press the launcher icon: three shortcuts work.
- [ ] Commit `docs: phase 5 status, README screenshots`.

## Self-review
Spec *Notifications* ✔ T1; auth item 5 ✔ T2; Settings hub *Notifications* row and Account *lock* ✔ T1/T2; phase 5 list (notifications, lock, shortcuts, screenshots, signing, CI, README) ✔ T1–T7; *Testing › UI* screenshot tests ✔ T4; *Testing › Contract* on CI ✔ T6. Types: `DueItem` (phase 2) feeds `Digest.text`; `LaunchTarget` is consumed by `AppNavHost` and produced by `ShareInbox`; `LockPolicy` is used by `RootViewModel` only.
