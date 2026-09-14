# Phase 2: Offline writes — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Everything except photos works with the radio off — objects, entries, readings and reminders can be created, edited, deleted, marked done and snoozed on the phone — and reconciles with the server when a connection returns, with the server's own last-write-wins rule and no conflict dialogs.

**Architecture:** Every local write is one Room transaction: change the mirror, append to the `ops` queue, stamp the field clock. A `PushEngine` drains the queue in order before every pull: a `create` op becomes the REST create carrying the row's *current* values and its `client_uuid`; runs of `set`/`delete` ops become one `POST /sync/push` batch. Forms are Compose screens over small repositories; nothing in a form knows the network exists.

**Tech Stack:** As phase 1 (Kotlin, Compose, Room, Retrofit, MockWebServer, Robolectric). Server: phase 0 (`client_uuid` on creates).

Spec: `docs/superpowers/specs/2026-09-14-logb-android-design.md`, sections *Identity, creates, and the op queue*, *Sync scheduling and status*, *Interface*.

## Global Constraints

- The rules of the spec section *Identity, creates, and the op queue* are law: a create op carries no value and reads the row at push time; edits to a row whose create is queued change the row and queue nothing; deleting such a row drops the row and its create op; a delete tombstones with the same cascade as a pulled delete and drops queued ops for every tombstoned row; `accepted` and `superseded` both remove an op; `rejected` marks it dead.
- `edited_at` is stamped from `Clock.correctedNowIso(syncState.clockOffsetMs)`; every local `set` also upserts `field_clock` with the phone's `device_id`.
- Reference fields (`parent_id`, `cover_attachment_id`, `done_activity_id`) are queued holding the target's uuid and translated to the server integer at push time; an op whose target has no `server_id` yet stops the push at that point (FIFO), to be retried next run.
- Reminder "done" is not a server call: `set done_at`, `set done_activity_id` when linked, and a local create of the successor from `ReminderRules.nextDue`. Snooze is `set snoozed_until`; archive is `set archived_at`.
- Attachment creates are phase 3: an `attachment` create op in the queue is skipped by the push (left in place), never sent.
- Screens read only from Room; forms validate the way the server's `validate()` does (see each task) so an offline write cannot be one the server would reject.
- Lint clean, unit tests green, strings in EN and DE, before every commit. Commit messages end with the attribution lines the session provides.

## File map

```
core/sync/LocalWriter.kt          the one transactional write API: create / set / delete
core/sync/PushEngine.kt           drains ops: REST creates + /sync/push batches
core/sync/SyncRunner (SyncModule) push then pull; pending count feeds SyncStatus
core/domain/ReminderTemplates.kt  port of reminder-templates.ts
core/domain/Validation.kt         the server's field rules (dates, counters, money)
feature/objects/ObjectRepository.kt, ObjectFormScreen.kt (+ViewModel), ParentPicker
feature/entries/ActivityRepository.kt, ActivityFormScreen.kt (+ViewModel), ReadingFormScreen.kt
feature/reminders/ReminderRepository.kt, ReminderFormScreen.kt (+ViewModel), DoneDialog, SnoozeSheet, DueListScreen
feature/settings/SyncScreen.kt    failed ops with retry / discard, pending list
navigation/Routes.kt + AppNavHost  new routes, FABs, card actions, Info-tab actions
```

---

### Task 1: LocalWriter — the transactional write API

**Files:**
- Create: `core/sync/LocalWriter.kt`
- Modify: `core/db/dao/LocalDaos.kt` (OpDao: `pendingCreate(entityUuid)`, `deleteForEntities`), `core/db/dao/*Dao.kt` (nothing new; uses `upsert`/`get`)
- Test: `core/sync/LocalWriterTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  class LocalWriter(db: LogbDatabase) {
      suspend fun deviceId(): String                       // from sync_state; generated and stored when absent
      suspend fun now(): String                            // corrected clock
      suspend fun create(entity: String, uuid: String, insert: suspend () -> Unit)
      suspend fun set(entity: String, uuid: String, changes: Map<String, Any?>, apply: suspend () -> Unit)
      suspend fun delete(entity: String, uuid: String)
  }
  ```
  `changes` values: `String?`, `Long?`, or for reference fields the target uuid as `String?`; the op stores the value as JSON (`"x"`, `12`, `null`). `insert`/`apply` are the caller's DAO writes and run inside the same transaction.

- [ ] **Step 1: Failing tests**

```kotlin
@RunWith(RobolectricTestRunner::class)
class LocalWriterTest {
    private val db = TestDatabase.inMemory()
    private val w = LocalWriter(db)
    @After fun close() = db.close()

    @Test fun `a create queues one create op and no sets, and edits before push change the row only`() = runTest {
        w.create("object", "u1") { db.objectDao().upsert(obj("u1", "Golf")) }
        w.set("object", "u1", mapOf("name" to "Golf VII")) { db.objectDao().upsert(db.objectDao().get("u1")!!.copy(name = "Golf VII")) }
        val ops = db.opDao().pending()
        assertEquals(listOf("create"), ops.map { it.kind })
        assertEquals("Golf VII", db.objectDao().get("u1")!!.name)
    }

    @Test fun `a set on a server row queues one op per field with a JSON value and stamps the clock`() = runTest {
        db.objectDao().upsert(obj("u1", "Golf", serverId = 4))
        w.set("object", "u1", mapOf("name" to "Polo", "purchase_price_cents" to 1200L, "parent_id" to null)) { }
        val ops = db.opDao().pending()
        assertEquals(listOf("set", "set", "set"), ops.map { it.kind })
        assertEquals(mapOf("name" to "\"Polo\"", "purchase_price_cents" to "1200", "parent_id" to "null"), ops.associate { it.field!! to it.valueJson })
        assertNotNull(db.fieldClockDao().get("object", "u1", "name"))
        assertEquals(w.deviceId(), db.fieldClockDao().get("object", "u1", "name")!!.deviceId)
    }

    @Test fun `deleting a row whose create is queued removes both and sends nothing`() = runTest {
        w.create("object", "u1") { db.objectDao().upsert(obj("u1", "Golf")) }
        w.delete("object", "u1")
        assertNull(db.objectDao().get("u1"))
        assertTrue(db.opDao().pending().isEmpty())
    }

    @Test fun `deleting a server row tombstones the cascade, drops queued sets for it, and queues one delete`() = runTest {
        db.objectDao().upsert(obj("u1", "Golf", serverId = 4)); db.activityDao().upsert(act("a1", "u1", "2026-01-01").copy(serverId = 9))
        w.set("activity", "a1", mapOf("title" to "x")) { }
        w.delete("object", "u1")
        assertNotNull(db.objectDao().get("u1")!!.deletedAt); assertNotNull(db.activityDao().get("a1")!!.deletedAt)
        assertEquals(listOf("delete"), db.opDao().pending().map { it.kind })
        assertEquals("u1", db.opDao().pending().single().entityUuid)
    }

    @Test fun `the device id is minted once and kept`() = runTest {
        val a = w.deviceId(); val b = w.deviceId()
        assertEquals(a, b); assertEquals(36, a.length)
    }
}
```

- [ ] **Step 2: Run, expect compile failure** — `./gradlew :app:testDebugUnitTest --tests '*LocalWriterTest'`

- [ ] **Step 3: Implement**

`OpDao` gains:
```kotlin
@Query("SELECT * FROM ops WHERE kind = 'create' AND entity_uuid = :uuid AND dead = 0 LIMIT 1") suspend fun pendingCreate(uuid: String): OpEntity?
@Query("DELETE FROM ops WHERE entity_uuid IN (:uuids)") suspend fun deleteForEntities(uuids: List<String>)   // exists
```

`LocalWriter`:
```kotlin
class LocalWriter(private val db: LogbDatabase) {
    suspend fun deviceId(): String {
        val s = db.syncStateDao().get()
        if (s != null) return s.deviceId
        val id = UUID.randomUUID().toString()
        db.syncStateDao().upsert(SyncStateEntity(deviceId = id))
        return id
    }
    suspend fun now(): String = Clock.correctedNowIso(db.syncStateDao().get()?.clockOffsetMs ?: 0)

    suspend fun create(entity: String, uuid: String, insert: suspend () -> Unit) = db.inTransaction {
        insert()
        db.opDao().insert(OpEntity(id = UUID.randomUUID().toString(), kind = "create", entity = entity, entityUuid = uuid, field = null, valueJson = null, editedAt = now()))
    }

    suspend fun set(entity: String, uuid: String, changes: Map<String, Any?>, apply: suspend () -> Unit) = db.inTransaction {
        apply()
        if (db.opDao().pendingCreate(uuid) != null) return@inTransaction   // the create will carry the new values
        val at = now(); val device = deviceId()
        for ((field, value) in changes) {
            if (FieldSpecs.of(entity, field) == null) error("$entity.$field is not a syncable field")
            val json = when (value) { null -> "null"; is String -> LogbJson.encodeToString(String.serializer(), value); is Number -> value.toString(); else -> error("unsupported value $value") }
            db.opDao().insert(OpEntity(id = UUID.randomUUID().toString(), kind = "set", entity = entity, entityUuid = uuid, field = field, valueJson = json, editedAt = at))
            db.fieldClockDao().upsert(FieldClockEntity(entity, uuid, field, at, device))
        }
    }

    suspend fun delete(entity: String, uuid: String) = db.inTransaction {
        val pendingCreate = db.opDao().pendingCreate(uuid)
        if (pendingCreate != null) {
            // Never reached the server: remove the row and everything queued for it.
            hardDelete(entity, uuid)
            db.opDao().deleteForEntities(listOf(uuid))
            return@inTransaction
        }
        val tombstoned = Cascade.tombstone(db, entity, uuid, now())
        db.opDao().deleteForEntities(tombstoned)
        db.opDao().insert(OpEntity(id = UUID.randomUUID().toString(), kind = "delete", entity = entity, entityUuid = uuid, field = null, valueJson = null, editedAt = now()))
    }
}
```
`hardDelete` needs `@Query("DELETE FROM objects WHERE uuid = :uuid")` (and activities, reminders, attachments) — add `hardDelete(uuid)` to each DAO. A locally created object may already have locally created children; `hardDelete("object")` also hard-deletes descendants, their activities, reminders and attachments (all local-only by construction: a child of an unpushed object cannot have been pushed) and their ops.

- [ ] **Step 4: Tests pass; commit** — `git commit -m "feat: LocalWriter -- transactional local writes with the op queue"`

---

### Task 2: PushEngine and the push-then-pull runner

**Files:**
- Create: `core/sync/PushEngine.kt`
- Modify: `di/SyncModule.kt` (runner = push then pull), `core/sync/SyncManager.kt` (pending count in `Offline`/`Idle` status via `pendingCount: () -> Int`), `core/sync/SyncStatus.kt` (`Pending(n)` when idle with ops waiting)
- Modify: `core/network/LogbApi.kt` (`createObject`, `createActivity`, `createReminder`)
- Test: `core/sync/PushEngineTest.kt`

**Interfaces:**
- Produces: `class PushEngine(db, api) { suspend fun run() }`; `LogbApi.createObject(ObjectInput): ObjectDto`, `createActivity(objectId, ActivityInput): ActivityDto`, `createReminder(objectId, ReminderInput): ReminderDto`.

- [ ] **Step 1: Failing tests** (MockWebServer, Robolectric)

- a queued object create posts `POST /api/objects` with `client_uuid` and the row's current values, and stores the returned `id` as `server_id`;
- an activity create for an object with no `server_id` yet waits (no request) — after the object's create runs first (FIFO) it posts with the object's id in the path;
- three `set` ops and a `delete` become one `POST /api/sync/push` with `device_id` and `edited_at` from the op; `accepted` and `superseded` results delete the ops, `rejected` marks the op dead with its reason;
- a `set parent_id` holding a uuid is sent as the target's integer id;
- a 409 on a create (uuid taken) marks the create op dead; a network error leaves everything queued;
- an `attachment` create op is skipped and left in the queue.

- [ ] **Step 2: Run, expect failure**

- [ ] **Step 3: Implement**

```kotlin
class PushEngine(private val db: LogbDatabase, private val api: LogbApi) {
    suspend fun run() {
        val ops = db.opDao().pending()
        var i = 0
        while (i < ops.size) {
            val op = ops[i]
            when (op.kind) {
                "create" -> { if (!pushCreate(op)) return; i++ }
                else -> {
                    val batch = mutableListOf<OpEntity>()
                    while (i < ops.size && ops[i].kind != "create") { batch += ops[i]; i++ }
                    if (!pushBatch(batch)) return
                }
            }
        }
    }
    // pushCreate: builds the input from the row; returns false to stop (a reference not yet on the server).
    // pushBatch: translates Ref values through server_id (stop if missing), posts, applies results.
}
```
`pushCreate` per entity: `object` → `ObjectInput(name, type, counterUnit, fuelUnit, description, purchaseDate, purchasePriceCents, archived = archivedAt != null, parentId = parent's server id, clientUuid = uuid)`; on success `upsert(row.copy(serverId = dto.id))`; then remove the op. `activity` → needs `objectDao().serverIdFor(objectUuid)` (return false if null); `reminder` likewise; `attachment` → skip (`i++`, no request). `ApiException` with status 409 or 400 → `markDead(op.id, e.message)` and continue; `IOException`/5xx → return false.

`SyncModule` runner becomes `PushEngine(db, api).run(); PullEngine(...).run()`. `SyncManager` takes `pendingCount: suspend () -> Int` and reports `Offline(pending)` and, after a successful run with ops still waiting (a stuck reference), `Idle`. The `SyncLine` shows "n changes waiting" from `Offline.pending`. `requestSync(AfterWrite)` is called by `LocalWriter`'s callers through the repositories (Task 3).

- [ ] **Step 4: Tests pass; commit** — `git commit -m "feat: push engine -- REST creates with client_uuid, /sync/push batches, push before pull"`

---

### Task 3: Repositories, validation, reminder templates

**Files:**
- Create: `core/domain/Validation.kt`, `core/domain/ReminderTemplates.kt`, `feature/objects/ObjectRepository.kt`, `feature/entries/ActivityRepository.kt`, `feature/reminders/ReminderRepository.kt`
- Test: `core/domain/ValidationTest.kt`, `core/domain/ReminderTemplatesTest.kt`, `feature/reminders/ReminderRepositoryTest.kt`, `feature/objects/ObjectRepositoryTest.kt`

**Interfaces:**
```kotlin
data class ObjectDraft(name, type, counterUnit, fuelUnit, description, purchaseDate, purchasePriceCents, parentUuid)
class ObjectRepository(db, writer, sync) { suspend fun create(d: ObjectDraft, templates: List<ReminderTemplate>, currentReading: Long?): String; suspend fun update(uuid, d); suspend fun setArchived(uuid, Boolean); suspend fun setCover(uuid, attachmentUuid?); suspend fun delete(uuid); suspend fun candidatesForParent(uuid?): List<ObjectEntity> }
data class ActivityDraft(date, category, title, notes, counterValue, costCents, quantityMilli)
class ActivityRepository(db, writer, sync) { suspend fun create(objectUuid, d): String; suspend fun update(uuid, d); suspend fun delete(uuid) }
data class ReminderDraft(title, notes, dueDate, dueCounter, repeatMonths, repeatCounter, kind, everyN, everyUnit)
class ReminderRepository(db, writer, sync) { suspend fun create(objectUuid, d): String; suspend fun update(uuid, d); suspend fun delete(uuid); suspend fun done(uuid, activityUuid?, today): String?; suspend fun snooze(uuid, days, today); suspend fun unsnooze(uuid) }
object Validation { fun date(s): String?; fun objectDraft(d): Map<String,String>; fun activityDraft(d, obj): Map<String,String>; fun reminderDraft(d, obj): Map<String,String> }   // field → error key
```

- [ ] **Step 1: Failing tests**: `Validation` mirrors `ObjectInput::validate`, `ActivityInput::validate` (title required, category in `categoriesFor(type)`, counter needs a unit and ≥ 0, `reading` needs a counter, cost ≥ 0, quantity needs a counter) and `ReminderInput::validate` (title required; service: due_date or due_counter; reading: every_n 1..60 and unit; due_counter needs a counter unit). `ReminderTemplates.templatesFor(type, unit)` and `counterStep` port the ts file with its table. `ReminderRepository.done`: sets `done_at`, links the activity, creates a successor with `ReminderRules.nextDue(baseDate = today, baseCounter = current counter, ...)` when the reminder repeats, returns the successor's uuid; `snooze` sets `snoozed_until = ReminderRules.snoozedDate(...)`. `ObjectRepository.create` with templates creates the ticked reminders (a distance template needs `currentReading` and also logs it as the first reading, as the web form does).

- [ ] **Step 2: Run, expect failure** — [ ] **Step 3: Implement** — every write goes through `LocalWriter` and ends with `sync.requestSync(SyncReason.AfterWrite)`. — [ ] **Step 4: Tests pass; commit** — `git commit -m "feat: object, entry and reminder repositories over the op queue; validation and reminder templates ported"`

---

### Task 4: Object form and parent picker

**Files:**
- Create: `feature/objects/ObjectFormScreen.kt`, `ObjectFormViewModel.kt`, `ParentPicker.kt`
- Modify: `navigation/Routes.kt` (`ObjectForm(uuid: String? = null, parentUuid: String? = null)`), `AppNavHost.kt`, `feature/objects/ObjectsScreen.kt` (FAB *New object*), `feature/objects/tabs/InfoTab.kt` (*Edit*, *Add here*, *Archive*/*Unarchive*, *Delete* with confirm), strings

Form: name, type (segmented or dropdown of the nine types with icons), counter unit (none/km/mi/h), fuel unit (shown for types with fuel), description, purchase date (`DatePickerDialog`), purchase price (decimal field → cents), parent picker (bottom sheet listing candidates, excludes self and descendants), and for a new object the type's templates as checkboxes (unticked) with a "current reading" field when a distance template is ticked. Save validates with `Validation.objectDraft`, shows errors inline, then `create`/`update` and navigates to the object. Delete asks "Delete Golf and its 12 entries?" using the stats.

- [ ] Steps: write `ObjectFormViewModel` state + `save()`; write the screen; wire routes and buttons; strings EN/DE; lint; device check (create an object offline: airplane mode, save, appears in the list; airplane off, sync line shows it pushed; the row has a `server_id`); commit `feat: object form with parent picker and reminder templates; edit, archive, delete`.

---

### Task 5: Entry form and reading form

**Files:**
- Create: `feature/entries/ActivityFormScreen.kt`, `ActivityFormViewModel.kt`, `ReadingFormScreen.kt`
- Modify: routes (`ActivityForm(objectUuid, uuid?)`, `ReadingForm(objectUuid)`), `AppNavHost`, `ObjectDetailScreen` (FAB *Log entry*, entry row tap → edit), `ObjectsScreen` (card *Log* action; long-press menu: Log entry / Log reading / Add reminder), strings

Fields in the order the web spec settles: date (default today), category (from `categoriesFor(type, current)`), title with suggestions from `recentTitles`, counter (prefilled with the current counter; a warning line when lower than the current), cost, fuel quantity when `category == fuel` and the type has fuel, notes. Save reachable without scrolling past the notes on a 2340-px screen: the top bar carries a *Save* action. Delete in the edit form's overflow, with confirm. Reading form: date and counter only. Photos row shows "Photos come with the next release" muted text — not a button.

- [ ] Steps as Task 4; device check: log an entry offline, the timeline shows it with the object's stats updated, the counter warning fires for a lower value; commit `feat: entry and reading forms, quick-log from the object card`.

---

### Task 6: Reminder form, done dialog, snooze, due list

**Files:**
- Create: `feature/reminders/ReminderFormScreen.kt`, `ReminderFormViewModel.kt`, `DoneDialog.kt`, `SnoozeSheet.kt`, `DueListScreen.kt`
- Modify: routes (`ReminderForm(objectUuid, uuid?)`, `DueList`), `AppNavHost`, `RemindersTab` (row menu: Done / Snooze / Unsnooze / Edit / Delete; *Add reminder* button), `ObjectsScreen` (due banner → `DueList`), strings

Form: kind (service / reading), title, notes; service: due date and/or due counter, repeat months, repeat counter; reading: start date, every n week/month. Done dialog: "Link an entry" (list of the object's newest 20 entries) or "Log an entry now" (opens the entry form with the reminder's title prefilled, and marks done on save). Snooze sheet: 1 day, 1 week, 1 month, custom date. Due list: every due or upcoming (30 days) reminder across objects, grouped by object, tapping opens the object's Reminders tab.

- [ ] Steps as Task 4; device check: mark a repeating reminder done offline — the successor appears with the right date and counter; snooze hides it from the due banner; commit `feat: reminder form, done with successor, snooze, due list`.

---

### Task 7: Settings › Sync with failed ops; pending count everywhere

**Files:**
- Modify: `feature/settings/SettingsScreens.kt` (SyncScreen: list of dead ops with entity, field, reason, *Retry* and *Discard*; count of waiting ops), `SettingsViewModel.kt` (`deadOps`, `pendingCount`, `retry(id)`, `discard(id)`), `core/design/components/SyncLine.kt` ("1 change couldn't be saved" when dead ops exist, red, → Sync page), strings
- Test: `feature/settings/SettingsRowsTest.kt` (row value `failed:n`)

Discarding a dead `set` reverts nothing (the pull already carries the server's truth); discarding a dead `create` hard-deletes the local row it would have created, after a confirm naming it.

- [ ] Steps: tests, implement, device check, commit `feat: Settings > Sync lists failed changes with retry and discard`.

---

### Task 8: Contract test for offline writes; smoke; README

**Files:**
- Modify: `app/src/test/kotlin/dev/logb/android/contract/SyncContractTest.kt` (second test), `docs/smoke-checklist.md`, `README.md`

Second contract test: after bootstrap, with the mirror only — create House → Garage → Light (nested, unpushed), an entry on Light, a reminder marked done with a successor; then `PushEngine.run()` + `PullEngine.run()`; assert via REST that the server has the four rows with the phone's `client_uuid`s, the parent chain, the entry, the done reminder and its successor; then a browser-side rename of Garage and a phone-side rename of Light while "offline" (no run), then run: both survive (different rows), and a same-field collision resolves to the newer edit on both sides.

- [ ] Steps: write, run with `-PlogbBin`, fix, checklist and README status, commit `test: contract test for offline creates, edits and last-write-wins`.

## Self-review

Spec coverage: identity/create/set/delete rules → Tasks 1–2; push batching and results → Task 2; reminder done/snooze → Tasks 3, 6; forms named in *Interface* (object, entry, reading, reminder, done dialog, snooze) → Tasks 4–6; failed ops in Settings › Sync and the sync line's fourth state → Task 7; pending badge on rows deliberately not done (spec: only the sync line and Settings). Photos, share target: phase 3. Type consistency: `LocalWriter.set(entity, uuid, changes, apply)` is the only write path the repositories use; `ReminderRules.nextDue`/`snoozedDate` from phase 1; `Validation` errors are string keys resolved to `R.string` in the form.
