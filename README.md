# LogB for Android

Native, offline-first Android client for [LogB](https://github.com/13/logb): the complete history
of your owned objects, on your phone, readable and writable with the radio off, reconciled with
your self-hosted server in the background.

Status: designed, not yet built.

- Design: `docs/superpowers/specs/2026-09-14-logb-android-design.md`
- Plans: `docs/superpowers/plans/` — phase 0 (server prerequisites, in the `logb` repo), phase 1
  (foundation, read-only mirror). Phases 2–5 get their plans when reached.

Stack: Kotlin, Jetpack Compose (Material 3), Room, Retrofit, WorkManager. Gradle 9.7 / AGP 9.3 on
JDK 21 (pinned in `gradle.properties`); the Android SDK path goes in `local.properties`
(`sdk.dir=/home/ben/Android/Sdk` on the development machine).
