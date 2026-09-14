# LogB for Android

Native, offline-first Android client for [LogB](https://github.com/13/logb): the complete history
of your owned objects, on your phone, readable with the radio off, reconciled with your
self-hosted server in the background.

**Status: phases 1 and 2 built.** Sign in, everything the account owns lands on the phone and
stays readable offline; objects, entries, readings and reminders can be created, edited,
deleted, marked done and snoozed with the radio off, and reconcile with the server -- and with
edits made in the browser -- under field-level last-write-wins when a connection returns.
Photos both ways (phase 3), statistics (phase 4) and polish (phase 5) follow, each with its
own plan.

## Requirements

- A LogB server at 0.7.1 or newer with the `server_time` millisecond fix (logb branch
  `server-time-millis`): the four sync additions from 0.7.0 (`client_uuid` on creates,
  `client_uuid` in responses, `entity_id` on pull rows, logged reference cleanups) plus
  millisecond `server_time`, which offline edits made right after a create depend on.
- Android 9 or newer.

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

## Tests

- Unit tests (JVM, Room on Robolectric's SQLite, MockWebServer): `./gradlew :app:testDebugUnitTest`
- Contract test against a real server binary:
  `./gradlew :app:testDebugUnitTest -PlogbBin=$HOME/repo/logb/target/release/logb --tests '*SyncContractTest'`
- Instrumented (a device or emulator): `./gradlew :app:connectedDebugAndroidTest`
- By hand: `docs/smoke-checklist.md`.

`tools/seed-server.py` seeds a fresh server with the tree the bootstrap fixture came from.

## Design

- `docs/superpowers/specs/2026-09-14-logb-android-design.md` — architecture, sync rules, screens, phases.
- `docs/superpowers/plans/` — phase 0 (server, in the `logb` repo) and phase 1 (this).

Stack: Kotlin, Jetpack Compose (Material 3), Room, Retrofit + OkHttp, WorkManager, DataStore,
Hilt, Coil.
