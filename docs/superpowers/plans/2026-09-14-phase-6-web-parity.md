# Phase 6: Parity with the web client — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> Status: executed 2026-09-14. Deviations: the Objects list now loads every live object (active and archived, every depth) once and filters in memory, as the web does, so `ObjectsModel.cards(archived)` became `allCards()`; cards refresh on `activityDao.version()` too, so a logged entry updates its card; the due list's inline action is *Snooze* (a week) for service reminders and *Log reading* for reading ones; *Skip* shows on any due reminder; the reading block sits under *Add reminder* on the Reminders tab; export is a `@Streaming` download into `cacheDir/exports` handed to `ACTION_SEND`. Verified on the phone (release build): "light" finds *Main light · in Garage*; the Golf card says "≈ 122 km pro Monat"; *Zurückstellen* on the due list snoozes a week; the reminders tab shows "Letzter Stand 86.000 km am 14.09.2026"; a reading of 200 000 warns "Weit mehr als üblich seit 14.09.2026"; Account shows *Server 0.7.1*, the password change succeeds, *Überall abmelden* is offered; export opens the share sheet with the zip.

**Goal:** Close every gap between what a signed-in, non-admin person can do in the web client (`logb/frontend`) and what the phone offers, so the two are interchangeable for daily use. Admin and operator screens stay out, as the spec decided.

**Architecture:** Every item reads the mirror and writes through `LocalWriter` like the rest of the app; two small pure ports (`object-list.ts`, `reading.ts`) carry the web's rules with their tests. The one network-only feature, per-object export, streams the server's zip to the cache directory and hands it to the share sheet.

**Tech Stack:** As phase 5. No new dependencies.

Spec: `docs/superpowers/specs/2026-09-14-logb-android-design.md`. Inventory: every `$t(...)` key each web route and component uses, compared with the app's screens on 2026-09-14.

## Gap inventory

| Web feature | App today | Task |
|---|---|---|
| Objects list: search box, sort (name, last activity, recently changed, highest cost, highest counter), remembered sort, a query searches every depth and names the parent | *Archived* chip only | 1 |
| Object card: "≈ 120 km a month" | counter, spent, last entry, due badge | 2 |
| Dashboard *Coming up*: "in 4 000 km", "≈ date", inline *Snooze* and *Record reading* | due banner → due list without those | 2 |
| Reminders tab, reading kind: "Last reading X on date", *Record reading*, "Remind me to log the reading" when none exists, *Skip this one* (snooze by the interval) on a due repeating reminder | done, snooze, unsnooze, edit, delete, history | 3 |
| Reading form: "Far more than usual since {date}" implausibility warning from the usage rate | lower-than-current warning only | 4 |
| Timeline: *Waiting to send* chip on an entry whose create is still queued | cloud-off badge on attachments only | 5 |
| Search results: *archived* tag | parent name only | 5 |
| Documents: *Clear cover* | *Use as cover* only | 5 |
| Account: change own password, *Sign out everywhere*, server version | server, user, lock, sign out | 6 |
| Object detail: *Export this object* (`GET /api/export?object_id=`) | none | 7 |

Out of scope, as the spec's decision 3 says: setup, people, API tokens, database, instance currency / timezone / language, data export-import of the whole instance, webhook and browser push (the phone has its own local digest).

Already equal: timeline folds and category filter, documents grid and viewer, entry / reading / object / reminder forms with templates and EXIF date, done dialog with link-an-entry, snooze picker, done history, statistics, insights, failed-op retry / discard, sign-in expiry handling.

## Global Constraints

- Sort and search on the Objects screen are per device (DataStore), like the web's `persisted` stores; the query is not persisted.
- Ports keep the web's names and figures: `SORT_KEYS` order, `fold()` accent stripping, null-last ordering with a name tiebreak; `IMPLAUSIBLE_FACTOR` and `ALWAYS_PLAUSIBLE` from `reading.ts`.
- *Skip* is what the web does: a snooze by the reminder's own interval (`intervalDays(every_n, every_unit)`), never a done.
- Change password goes through `PATCH /api/users/{id}` with `{password}`; *Sign out everywhere* is `POST /api/auth/logout-all` (server sessions and tokens) followed by the local sign-out that keeps the mirror.
- Export needs the network; offline it says so instead of failing silently.
- Lint clean, unit tests green, EN and DE strings, goldens re-recorded where a screen changed, before every commit.

## File map

```
feature/objects/ObjectListing.kt        port of object-list.ts: SortKey, matchesQuery, sortObjects, visibleRows
feature/objects/ObjectsPrefs.kt         remembered sort (DataStore)
feature/objects/ObjectsScreen.kt        search field, sort menu, parent line on deep matches, usage line on cards
feature/reminders/DueListScreen.kt      counter-until, estimate, inline Snooze / Record reading
feature/objects/tabs/RemindersTab.kt    reading-kind block: last reading, Record reading, remind-me shortcut; Skip
core/domain/ReadingWarning.kt           port of reading.ts: readingWarning(value, date, ctx)
feature/entries/ReadingFormScreen.kt    implausible warning
feature/objects/tabs/TimelineTab.kt     waiting-to-send chip
feature/search/SearchScreen.kt          archived tag
feature/entries/AttachmentViewer.kt     Clear cover
feature/settings/SettingsScreens.kt     Account: change password, sign out everywhere, server version
core/network/LogbApi.kt                 updateUser, logoutAll, export (@Streaming)
feature/objects/ObjectDetailScreen.kt   Info tab: Export this object → share sheet
```

---

### Task 1: Objects list search and sort
**Files:** create `feature/objects/ObjectListing.kt`, `feature/objects/ObjectsPrefs.kt`; modify `ObjectsViewModel.kt` (`query`, `sort` flows; `ObjectCard` gains `parentName`, `updatedAt`), `ObjectsScreen.kt` (search field under the sync line, sort menu in the top bar, parent line on deep matches), strings; test `feature/objects/ObjectListingTest.kt`.
**Produces:**
```kotlin
enum class SortKey { Name, LastActivity, Changed, Cost, Counter }
object ObjectListing {
    fun fold(s: String): String
    fun matchesQuery(card: ObjectCard, query: String, typeLabel: (String) -> String): Boolean
    fun sortObjects(list: List<ObjectCard>, key: SortKey, locale: Locale): List<ObjectCard>
    /** Active tab without a query: roots (a live child of an archived parent counts as a root). A query: every depth. Archived: flat. */
    fun visibleRows(active: List<ObjectCard>, archived: List<ObjectCard>, tab: Boolean, query: String, sort: SortKey, typeLabel: (String) -> String, locale: Locale): List<Row>
}
```
- [x] Tests, one per web test in `frontend/tests/object-list.test.ts`: accent-folded match on name, type label and description; each sort key with nulls last and a name tiebreak; roots only without a query, every depth with one; a live child of an archived parent shows as a root.
- [x] Commit `feat: search and sort on the objects list`.

### Task 2: Usage on cards; the due list as the web's dashboard block
**Files:** modify `ObjectsViewModel.kt` (`counterPerDayMilli` per card via `InsightsModel.usage`), `ObjectsScreen.kt` (card line "≈ 120 km a month"), `DueListScreen.kt` (counter-until "in 4 000 km", "≈ date", inline *Snooze* (one week) and *Record reading* for reading reminders), strings.
- [x] Commit `feat: usage on object cards; snooze and record from the due list`.

### Task 3: Reading reminders and Skip
**Files:** modify `RemindersTab.kt` (reading block above the list: "Last reading 86 000 km on 5 Sep 2026" or "No reading yet", *Record reading*, "Remind me to log the reading" when the object has a counter unit and no reading reminder; *Skip this one* on a due repeating reminder), `ReminderRepository.kt` (`skip(uuid)` = snooze by `ReminderRules.intervalDays`), strings; test additions in `ReminderRepositoryTest.kt`.
- [x] Commit `feat: reading reminders show the last reading; skip a due repeat`.

### Task 4: Implausible reading warning
**Files:** create `core/domain/ReadingWarning.kt`; modify `ReadingFormViewModel.kt` (usage from `InsightsModel.usage`), `ReadingFormScreen.kt`, strings; test `core/domain/ReadingWarningTest.kt`.
**Produces:** `enum class ReadingWarning { Lower, Implausible }`, `fun readingWarning(value: Long, date: LocalDate, lastCounter: Long?, lastDate: LocalDate?, ratePerDayMilli: Long?): ReadingWarning?` with the web's `IMPLAUSIBLE_FACTOR` and `ALWAYS_PLAUSIBLE`.
- [x] Tests transliterated from `frontend/tests/reading.test.ts`. Commit `feat: warn about a reading far above recent usage`.

### Task 5: Small parity items
**Files:** `TimelineTab.kt` (*Waiting to send* chip when `OpDao.pendingCreate` holds the entry), `SearchScreen.kt` (*archived* tag), `AttachmentViewer.kt` (*Clear cover* when the photo is the cover), strings.
- [x] Commit `feat: waiting-to-send chip, archived tag in search, clear cover`.

### Task 6: Account parity
**Files:** `LogbApi.kt` (`@PATCH("api/users/{id}") updateUser`, `@POST("api/auth/logout-all") logoutAll`), `SessionRepository.kt` (`signOutEverywhere()`), `SettingsViewModel.kt`, `SettingsScreens.kt` (Account: *Change password* dialog with confirmation field, *Sign out everywhere* with confirm, server version from `/api/health` cached at sign-in), strings; MockWebServer tests for both calls.
- [x] Commit `feat: change password and sign out everywhere`.

### Task 7: Export this object
**Files:** `LogbApi.kt` (`@Streaming @GET("api/export") export(@Query("object_id") id: Long): ResponseBody`), `ObjectDetailViewModel.kt` (`export()` → `cacheDir/exports/<name>.zip` → `FileProvider` URI → `ACTION_SEND`), `InfoTab.kt` (button; disabled offline with the reason), `res/xml/file_paths.xml` (cache path), strings.
- [x] Commit `feat: export one object through the share sheet`.

### Task 8: Device check, docs
- [x] Phone: search "fil" finds the light under the garage with "in Garage"; sort by cost puts the Golf first; card shows "≈ 122 km pro Monat"; due list snoozes inline; reminders tab records a reading and skips a due repeat; a reading of 200 000 km warns; a new entry offline shows *Waiting to send* until reconnect; change the password and sign back in; export the Golf and share the zip to Files.
- [x] `docs/smoke-checklist.md` phase 6 section, README feature list, spec status, this plan's status header, goldens re-recorded, memory.
- [x] Commit `docs: phase 6 status`.

## Self-review
Every row of the gap inventory maps to a task (1–7); out-of-scope rows are named with the spec decision that excludes them. Types: `ObjectCard` is extended in Task 1 and consumed by Tasks 2 and 5; `InsightsModel.usage` (phase 4) feeds Tasks 2 and 4; `intervalDays` (web `reminder-form.ts`) is added to `ReminderRules` beside `nextDue` for Task 3.
