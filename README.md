# LogB for Android

Native, offline-first Android client for [LogB](https://github.com/13/logb): the complete history
of your owned objects, on your phone, readable with the radio off, reconciled with your
self-hosted server in the background.

**Status: feature parity with the web client for a signed-in, non-admin person.** Sign in, everything the account owns lands on the phone and
stays readable offline; objects, entries, readings and reminders can be created, edited,
deleted, marked done and snoozed with the radio off, and reconcile with the server -- and with
edits made in the browser -- under field-level last-write-wins when a connection returns. Photos
and documents work in both directions: taken, picked or shared into an entry offline and
uploaded later; the server's files mirrored as thumbnails always and originals within a budget.
Statistics (spend over time, by object tree, by type, by category, with a year picker) and every
object's insights (cost of ownership, spend per month, cost per km, fuel consumption, usage,
consumption per fill) are computed on the phone from the mirror, and reminders with a counter
target show when recent usage will reach it. A daily notification summarises what is due, an
optional biometric or screen-lock gate covers the logbook, and launcher shortcuts jump to the
due list, search and a new object. The objects list searches every depth and sorts five ways,
readings warn when they jump far beyond recent usage, a due reminder can be skipped by its own
interval, entries born offline say so until they are sent, and an object can be exported as the
server's zip through the share sheet. Settings also carries the account pages the web has --
API access, Data and the server's notification digest, described below. Only Settings › People
and Settings › Database stay in the browser, along with browser push notifications; everything
else a signed-in, non-admin person can do on the web, this app now does too.

<p>
<img src="docs/screenshots/objects.png" width="180" alt="Objects">
<img src="docs/screenshots/timeline.png" width="180" alt="An object's timeline">
<img src="docs/screenshots/statistics.png" width="180" alt="Statistics">
<img src="docs/screenshots/insights.png" width="180" alt="Insights on the Info tab">
</p>
<p>
<img src="docs/screenshots/reminders.png" width="180" alt="Reminders with a usage estimate">
<img src="docs/screenshots/settings.png" width="180" alt="Settings">
<img src="docs/screenshots/lock.png" width="180" alt="The lock screen">
</p>

## Requirements

- A LogB server at 0.7.1 or newer with the `server_time` millisecond fix (logb branch
  `server-time-millis`): the four sync additions from 0.7.0 (`client_uuid` on creates,
  `client_uuid` in responses, `entity_id` on pull rows, logged reference cleanups) plus
  millisecond `server_time`, which offline edits made right after a create depend on.
- Android 9 or newer.

## Tags and own types

Objects and entries carry free-form tags, and a person can define their own object types
(name, icon, entry categories, default counter unit) beside the nine built-in ones. Both need
logb **0.8.0 or newer** on the server -- gated by `ServerCapabilities`, so an older server shows
neither a tag input nor the Settings › Types row, while tags and types created earlier keep
displaying. Both sync both ways under the same field-level last-write-wins as everything else,
and both work fully offline: a type minted on the phone gets its object key (`custom:<uuid>`)
before ever reaching the server, and tags queue like any other field edit.

## Reminders outside the app

The daily notification for what is due now acts, not just informs: a service reminder's
notification carries *Done* and *Snooze 7 days*, a reading reminder's carries *Log reading*
only, and several due at once collapse into one grouped notification (a child per reminder, a
summary on top). Every action works offline and queues like any other write; tapping a child's
body opens that object's Reminders tab, and *Log reading* opens the reading form directly. A
home-screen widget mirrors the due list -- up to five rows with a one-tap check for service
reminders -- and refreshes itself after every sync, local write, the daily digest, midnight, and
whenever the lock or the signed-in account changes. With the app lock enabled the widget shows
only a count, never a name, title or object -- the same privacy the lock screen promises stays
true for anything glanceable from the home screen -- and it says so plainly ("Open LogB to sign
in") rather than showing stale data once signed out.

## Account settings

**Settings › API access** lists every personal access token on the account, including this
phone's own (labelled "This phone", recognised by the token id the phone remembers or, failing
that, by the server's own 15-character prefix; a row that can't be identified either way offers
no *Revoke*, since revoking the wrong token could lock the phone out). Creating a token and
revoking one both ask for the account password first and nothing else: logb mints and deletes
tokens only for an interactive session, never for a bearer token, so a browser sign-in could do
this but a script with a token could not -- the app opens a short-lived cookie session for the
one call and forgets the password the moment it returns. A freshly created token's plaintext is
shown once, with a Copy button, and is never written to disk or a log.

**Settings › Data** exports the whole account as the server's zip through the system's file
picker (suggested name `logb-export-<date>.zip`) and imports one the same way. Import always
*adds*: it never replaces what is already on the phone or the server, so importing the same
archive twice duplicates every object, entry, attachment and reminder it contains -- the app
warns about this before it asks for the file, then shows the server's counts and requests a
fresh bootstrap so the new rows land on the next sync.

**Settings › Notifications** gained a *Server digest* section alongside the phone's own local
reminder summary: a webhook address (checked before saving) and its format (plain text for
`ntfy`, or JSON), saved to the account, plus *Send a test notification*, which reports back
exactly what the server's own attempt at the webhook did.

All three pages need the server and say so instead of failing quietly when it can't be reached;
a 401 from any of their calls signs the phone out, the way every other page already does.

## Build

Gradle 9.7 / AGP 9.3 / Kotlin 2.4 on JDK 21. `gradle.properties` pins the JDK path of the
development machine; put the SDK path in `local.properties` (`sdk.dir=/home/ben/Android/Sdk`).

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

A server on the development machine is reachable from the phone through
`adb reverse tcp:8090 tcp:8090` as `http://localhost:8090`; the network security config allows
cleartext only for `localhost` and `10.0.2.2`.

## Release builds

`./gradlew :app:assembleRelease` signs with the key named by `ANDROID_KEYSTORE`,
`ANDROID_KEYSTORE_PASS`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASS` in the environment (or an
untracked `keystore/keystore.properties` with `storeFile`, `storePassword`, `keyAlias`,
`keyPassword`); without either it signs with the debug key. `-PversionName=0.5.0
-PversionCode=500` stamp a version; the About screen shows the commit the build came from.

The release workflow (`.github/workflows/release.yml`) builds a tagged `v*` push into a signed
APK attached to a GitHub release, reading the keystore from the `LOGB_KEYSTORE_BASE64`,
`LOGB_KEYSTORE_PASSWORD`, `LOGB_KEY_ALIAS` and `LOGB_KEY_PASSWORD` secrets. CI
(`.github/workflows/ci.yml`) runs the unit tests, lint, the screenshot goldens, both APKs, and
the contract test against a `13/logb` checkout built beside the app.

## Updates

Release builds check `api.github.com/repos/13/logb_mobile/releases/latest` once a day at
start (switchable in Settings › About), and download the APK only when asked from the
release's `browser_download_url`. A download is offered for install only after its size,
SHA-256 and signing certificate all match what GitHub published and the app already carries;
installing needs Android's "install unknown apps" permission, granted through the system
confirmation dialog on first use. This is the only request that does not go to the user's
own LogB server. Debug builds never check. The certificate check is exact, so rotating the
signing key would make every future release fail it until one install is done by hand with
the new key (or the updater itself is changed to accept it); `REQUEST_INSTALL_PACKAGES` is
restricted on the Play Store, which is why this feature lives entirely in `feature/update`.
Update traffic to `api.github.com`, `github.com`, `objects.githubusercontent.com` and
`release-assets.githubusercontent.com` trusts only the system certificate store, so a
privately installed CA cannot rewrite what an update is; a self-hosted LogB server may still
use one, as every other host keeps system + user CAs. The updater installs only the asset
named `LogB-<version>.apk` for the release's own version, never just the first `.apk` it finds.
One consequence: a device behind a proxy that intercepts all TLS with a user-installed CA will
no longer get in-app updates, since that CA is no longer trusted for GitHub's hosts — the
release page on GitHub still works normally for a manual download.

## Performance and release checks

The objects list and the due list read the mirror in a constant number of queries however many
objects there are: `ObjectsModel.allCards()` runs 6 SELECTs and `DueListModel.items()` runs 4,
regardless of object count, using aggregate DAO queries (`statsForAll`, `readingRowsForAll`,
`coverShas`) instead of a per-object loop. `AggregateEquivalenceTest` proves every `ObjectCard`
and `DueItem` field the aggregate path produces equals what the old per-object path produced, on
a seeded 30-object mirror, and asserts the query count stays flat from 5 to 200 objects.

CI (`.github/workflows/ci.yml`) also runs two emulator jobs beside the JVM checks: `Device tests`
runs `:app:connectedDebugAndroidTest` on API 31, and `Release APK on a device` assembles the
minified release APK and runs `tools/release-smoke.sh` against it on API 34 — this is the only
check that exercises the R8-shrunk build, catching a missing keep rule that the debug build and
the JVM tests can't see. Run the same script locally against an emulator or a device:

```bash
./gradlew :app:assembleRelease --console=plain
tools/release-smoke.sh <serial>       # e.g. emulator-5554, or a phone's adb serial
```

The script refuses to guess when more than one device is attached (`adb devices`) and no serial
is given — pass one explicitly, or set `ANDROID_SERIAL`.

A baseline profile (`app/src/release/generated/baselineProfiles/`) reorders the release dex so
the cold start to the server screen needs fewer page faults; `profileinstaller` compiles it into
odex on first launch. Regenerate it only on a Gradle Managed Device, never on a connected
emulator or phone:

```bash
./gradlew :app:generateReleaseBaselineProfile   # runs on the pixel6Api34 Gradle Managed Device
```

The startup benchmark that measures the effect is `:baselineprofile:pixel6Api34BenchmarkReleaseAndroidTest`,
also on `pixel6Api34`.

## Tests

- Unit tests (JVM, Room on Robolectric's SQLite, MockWebServer): `./gradlew :app:testDebugUnitTest`
- Contract test against a real server binary:
  `./gradlew :app:testDebugUnitTest -PlogbBin=$HOME/repo/logb/target/release/logb --tests '*SyncContractTest'`
- Screenshot goldens (Roborazzi, both themes): `./gradlew :app:verifyRoborazziDebug`; re-record
  deliberately with `:app:recordRoborazziDebug` and look at every PNG under `app/src/test/screenshots/`.
- Instrumented (a device or emulator): `./gradlew :app:connectedDebugAndroidTest`
- By hand: `docs/smoke-checklist.md`.

`tools/seed-server.py` seeds a fresh server with the tree the bootstrap fixture came from.

## Design

- `docs/superpowers/specs/2026-09-14-logb-android-design.md` — architecture, sync rules, screens, phases.
- `docs/superpowers/plans/` — phase 0 (server additions), phases 1–6 (this app), each with a status header.

Stack: Kotlin, Jetpack Compose (Material 3), Room, Retrofit + OkHttp, WorkManager, DataStore,
Hilt, Coil.
