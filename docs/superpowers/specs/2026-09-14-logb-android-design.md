# LogB for Android: a native, offline-first client

Status: phases 0–3 built (2026-09-14); phases 4–5 open. Requires a server with the
`server_time` millisecond fix (branch `server-time-millis` in logb, on top of 0.7.1). Supersedes the client half of
`logb/docs/superpowers/specs/2026-09-08-offline-sync-design.md`, whose Capacitor shell is not
built; its server half (phase 1, the sync protocol) is merged and is what this client talks to.

## Decisions taken in this design

These were settled without a conversation. Each is the choice a careful colleague would make from
the request "native Android, fully offline, syncs with the server"; any of them can be reversed
before the first plan is executed, and the ones marked *cheap to reverse later* can be reversed
after.

| # | Decision | Why | Reversal cost |
|---|----------|-----|---------------|
| 1 | **Kotlin + Jetpack Compose + Material 3**, one Gradle module, package-by-feature. | "Native" rules out Flutter and Capacitor. Flutter is also not installed on this machine any more; the Android SDK, JDK 21 and a connected device are. | Total: a different stack is a different project. |
| 2 | **The server gains four small additions first** (phase 0, in the `logb` repo). | The protocol as merged cannot carry an offline create with a phone-minted identity, and a pull row cannot be joined to a server id. Both are prerequisites, not conveniences. See *Server prerequisites*. | Low: ~200 lines of Rust, all additive and backward compatible. |
| 3 | **Scope is what a signed-in, non-admin person does on a phone.** Objects, timeline, entries, photos and documents, reminders, search, statistics, insights, settings. Not: user administration, database switching, instance currency and timezone, export and import, API-token management, browser push. | Every excluded thing is an operator task or a browser feature. None of it makes sense offline. | Cheap: each is one screen calling one existing endpoint, addable later. |
| 4 | **minSdk 28, targetSdk 36.** | Android 9 is what the previous mobile app in this household shipped with; nothing here needs newer. | Cheap. |
| 5 | **A personal access token is the credential.** Password entered once, online. | The only path the server already offers to a non-browser client. Cookie sessions are for the browser. | None: it is the server's design. |
| 6 | **One account signed in at a time; one local database per (server, user).** | A household phone can switch accounts without losing either person's mirror. Two accounts *simultaneously* has no use. | Cheap. |
| 7 | **Full-size originals are a bounded cache, thumbnails are not.** Default budget 4 GB, changeable. Originals download on unmetered connections only, by default. | Carried over from the offline-sync design: the mirror must always *render*; it need not always hold every original. | Cheap to reverse later. |
| 8 | **Reminder notifications are local**, computed from the mirror, not the server's push. | Web push is a browser mechanism. The phone already knows what is due without asking. | Cheap. |
| 9 | **No Material "dynamic colour" by default.** LogB's own teal and off-white, in light and dark. A switch in Appearance turns dynamic colour on. | Brand consistency with the web app. The switch costs one line. | Cheap. |

## Goal

A LogB app for Android in which the network is irrelevant to every interaction. All data is
readable, everything can be created, edited and deleted, photos can be taken and viewed, for as
long as the phone is offline. When a connection exists the app reconciles with the server in the
background using the sync protocol the server already implements, with field-level
last-write-wins and no conflict dialogs.

It should feel like a well-made Android app, not a wrapped web page: Material 3, edge-to-edge,
predictive back, the camera one tap from a timeline, a share target for photos, a bottom bar a
thumb can reach.

## What exists

- **Server** (`~/repo/logb`, Rust, axum, SQLite or PostgreSQL). Documented in
  `docs/openapi.json`, kept honest by `tests/openapi.rs`. Sync protocol merged: `POST /sync/push`,
  `GET /sync/pull`, `GET /sync/bootstrap`, `changes` and `field_clock` tables, tombstones, a 90-day
  purge, epochs. Every REST write is logged to the change feed, so the browser and the phone see
  each other.
- **Web client** (`logb/frontend`, Svelte 5). Mobile-first PWA with an opportunistic outbox. Its
  screens, copy (EN and DE, ~355 keys in `i18n/en.ts`), icons (`lib/Icon.svelte`) and design tokens
  (`app.css`) are the reference for this app's look and vocabulary.
- **Bearer auth** (`src/auth.rs`): `Authorization: Bearer logb_pat_…` works on every endpoint.
  `POST /auth/tokens` mints one and requires a session cookie, so the phone logs in with a
  password once, mints a token, and ends the session.
- **Domain logic the phone must own** because the mirror is the source of every screen:
  `src/domain/reminder.rs` (due, upcoming, reading intervals, snooze, repeat successors),
  `src/domain/insights.rs` (usage rate, estimated dates, fuel consumption),
  `src/domain/stats.rs` (spend rollups), and the SQL in `src/api/objects.rs` (`stats`,
  `derived`, `due_readings`, `ancestors`) and `src/api/search.rs`.

## Architecture

**Local truth.** Every screen renders from Room. Nothing on the way to a screen touches the
network. Sync is a background reconciler that writes into the same tables, and Compose observes
Room flows, so a pulled change appears on screen the way a local edit does.

**Every write is one transaction: change the mirror, append to the op queue, commit.** The UI
never waits for the server, never rolls back, and never shows an "offline" mode.

**The server's rules are the phone's rules.** Last-write-wins per field, tie-break on `device_id`,
tombstone deletes with the same cascade, clock corrected from `server_time`. The phone applies a
pulled op with exactly the comparison the server applies to a pushed one, so both converge
regardless of arrival order.

### Layers

```
app/src/main/kotlin/dev/logb/android/
  App.kt, MainActivity.kt                 Hilt application, single-activity Compose host
  core/design/     theme (colours, type with tabular figures, shapes), LogB icons, shared composables
  core/db/         Room database, entities, DAOs, migrations; one file per (server, user)
  core/network/    OkHttp + Retrofit, bearer interceptor, DTOs, error mapping
  core/auth/       server URL, token minting, Keystore-encrypted token store, session state
  core/sync/       bootstrap importer, pull/apply, op queue, push, scheduler, status
  core/blobs/      sha256 store, thumbnails, download and upload queues, budget
  core/domain/     pure Kotlin ports of the Rust domain modules + the stats SQL
  feature/onboarding/   server URL, sign in, first bootstrap
  feature/objects/      dashboard, object detail (tabs), object form, parent picker
  feature/entries/      activity form, reading form, attachment viewer, camera/picker
  feature/reminders/    list, form, done dialog, snooze
  feature/search/
  feature/stats/        statistics screen, insights (Info tab)
  feature/settings/     hub, appearance, account, sync, notifications, about
```

Each feature is `Screen` composables + one `ViewModel` exposing a `StateFlow<UiState>` + a
`Repository` that talks to DAOs and the op queue. Feature code never imports `core/network`: the
only things that talk to the server are `core/sync`, `core/blobs` and `core/auth`.

### Toolchain

| Piece | Choice |
|-------|--------|
| Language | Kotlin 2.4.x (built into AGP 9), KSP 2.3.x |
| Build | Gradle 9.7 wrapper, AGP 9.3 — the toolchain `~/repo/apexweather` builds with on this machine — on JDK 21 (`org.gradle.java.home` in `gradle.properties`; the default JDK 25 is newer than AGP supports) |
| UI | Compose BOM (latest stable at task time), Material 3, Navigation Compose with type-safe `@Serializable` routes, `androidx.activity` edge-to-edge and predictive back |
| DI | Hilt |
| Database | Room 2.7+ with the bundled SQLite driver, so DAO and sync tests run on the JVM with no emulator |
| Network | OkHttp 5, Retrofit 3, `kotlinx.serialization` |
| Images | Coil 3 with a custom fetcher that serves from the local blob store |
| Background | WorkManager (periodic sync, expedited push after writes, unmetered-only downloads) |
| Preferences | DataStore |
| Secrets | Android Keystore AES-GCM key wrapping the token in DataStore (`androidx.security:security-crypto` is deprecated) |
| Camera | `ActivityResultContracts.TakePicture` into a FileProvider URI; `PickVisualMedia` for the gallery; `OpenDocument` for PDFs |
| Tests | JUnit 4 + `kotlin.test`, Turbine, MockWebServer, Room on JVM, Compose UI tests, Compose screenshot tests; a contract test job against the real `logb` binary |

## Server prerequisites (phase 0, in `logb`)

Four additive changes. Each is backward compatible: the web client sends none of the new fields
and ignores the new ones it receives.

1. **REST creates accept `client_uuid`.** `POST /objects`, `POST /objects/{id}/activities`,
   `POST /objects/{id}/reminders` (JSON) and `POST /objects/{id}/attachments` (multipart field)
   take an optional `client_uuid`, 8–64 characters, no whitespace. The row is inserted with it.
   A second create carrying a `client_uuid` that already names a live row **of the caller's**
   returns that row with `200` instead of inserting (idempotent replay after a lost response).
   One that names another user's row, or a tombstoned one, is `409`. This is what lets a row
   born offline keep the identity its activities, attachments and reminders already reference,
   and it is the cheaper of the two options the offline-sync design left open: the create
   arrives through REST with its *final* offline values, so no queued `set` can predate it.
2. **`client_uuid` in every entity response.** `ObjectOut`, `ActivityOut`, `ReminderOut`,
   `AttachmentOut` gain `client_uuid`; `AttachmentOut` also gains `file_uuid`, because an upload
   may be deduplicated onto a `files` row the phone has never seen.
3. **`entity_id` on every pull row.** `ChangeRow` gains the server's integer id for
   `entity_uuid`, resolved at feed time. Without it a phone that learns of a row from a `create`
   change row has no way to relate a later `set parent_id = 42` — or a `/files/42/thumb` URL — to
   anything. `entity_id` is null only when the row has since been hard-purged, in which case the
   phone has nothing to apply it to anyway.
4. **The two silent reference cleanups are logged.** Clearing an object's cover when its
   attachment is deleted, and unlinking `reminders.done_activity_id` when the activity is
   deleted, each record a `set … null` op and stamp the field clock, through the existing
   `record::record_update`. The offline-sync design named this as a gap to close before a client
   relies on the feed being complete.

`docs/openapi.json` is updated in the same change; `tests/openapi.rs` enforces the paths, and
the new fields get integration tests beside the existing ones in `tests/sync.rs`,
`tests/objects.rs`, `tests/attachments.rs`.

## Local schema

Room, one database file per account: `logb-<sha256(serverUrl)[0..8]>-<user_id>.db` under
`filesDir/db/`. Mirror tables carry the server's columns with these deliberate differences:

- **Primary key is `uuid`** (the server's `client_uuid`). `server_id: Long?` is null until the
  server has confirmed the row. A unique index on `server_id` where not null.
- **References are by uuid**: `objects.parent_uuid`, `objects.cover_attachment_uuid`,
  `activities.object_uuid`, `attachments.object_uuid/activity_uuid/file_uuid`,
  `reminders.object_uuid/done_activity_uuid`. A pulled `set` carrying an integer id is translated
  through the `server_id` index at apply time. An id that cannot be translated is applied as null
  and sets `sync_state.bootstrap_needed`, so the next sync heals it rather than leaving a
  dangling reference forever.
- **`deleted_at` is kept** on every mirror table; every query filters it. A local delete
  tombstones exactly what the server's cascade would, in one transaction.
- `client_uuid` is **never validated as a UUID shape**: backfilled server rows are 32 hex chars,
  new ones are 36-char v4, and phase 0 accepts anything 8–64 chars without whitespace.

Local-only tables:

```
ops          seq PK autoincrement, id (client_op_id, uuid), kind create|set|delete,
             entity, entity_uuid, field?, value_json?, edited_at, attempts, dead, last_error?
sync_state   singleton: cursor_seq, epoch, clock_offset_ms, device_id, last_synced_at,
             bootstrap_needed
field_clock  entity, entity_uuid, field, edited_at, device_id   (PK on the first three)
blobs        sha256 PK, size, mime, original_present, thumb_present, last_access_at
```

`ops.seq` is the push order. `id` is the `client_op_id` the server deduplicates on.

## Identity, creates, and the op queue

Every row the phone makes gets a v4 uuid at creation. Then:

- **Create.** Insert the row, queue `create(entity, uuid)`. No value is stored on the op: at push
  time the op reads the row's *current* values and sends them as the REST create body with
  `client_uuid`. Edits to a row whose create is still queued therefore queue nothing; they simply
  change the row. Deleting such a row removes the row and its create op — the server never hears
  of it.
- **Set.** For a row the server already knows (`server_id` set, or a create op ahead in the queue
  that will have run by the time this op is reached), each changed field queues
  `set(entity, uuid, field, value, edited_at)`, where `edited_at` is local time plus the stored
  clock offset. The same write also updates `field_clock` locally. Foreign-key fields are queued
  holding the target's uuid and translated to the server integer at push time; if the target has
  no `server_id` yet, the push stops at that op and retries on the next run (FIFO order makes this
  rare: the target's create is always earlier in the queue).
- **Delete.** Tombstone the row and everything the server's `cascade_object` /
  `cascade_activity` would tombstone, with one timestamp, and queue a single `delete`. Queued
  `set` ops for the tombstoned rows are dropped; a queued `create` for any of them is dropped
  along with the row.
- **Push.** Batch consecutive `set`/`delete` ops into one `POST /sync/push` (the server applies a
  batch atomically); a `create` op is its own REST call, because that is how the server inserts
  rows. Results: `accepted` and `superseded` both remove the op — a superseded field was already
  overwritten locally by the pull that carried the winner, or will be by the next one.
  `rejected` marks the op dead with its reason; dead ops surface in Settings › Sync with
  *retry* and *discard*, exactly as the web client's failed-operations banner does.
- **Attachments** are creates whose REST call is the multipart upload. The op waits until the
  blob is present and, when the file is on an activity, until that activity has a `server_id`.
  Uploads honour the same connection rule as downloads (unmetered by default) unless the user
  taps *upload now*.
- **Reminder "done"** is not a server call. The phone applies what `api::reminders::done` does:
  `set done_at`, `set done_activity_id` when one is linked, and — if the reminder repeats — a
  local create of the successor from `domain::reminder::next_due`, with its own uuid. Snooze is
  `set snoozed_until`; archive is `set archived_at`.

## Pull and apply

On every sync, after pushing:

1. `GET /sync/pull?since=<cursor>&epoch=<epoch>&limit=1000`, repeated while `complete` is false.
2. Store `server_time − local_now` as the clock offset.
3. Apply each row in one transaction per page:
   - `create`: if the uuid is unknown, insert a placeholder row (all nullable fields null, required
     text fields empty) carrying `server_id = entity_id`; the `set` rows that follow fill it in.
     If known (the phone made it), just record `server_id`.
   - `set`: parse the double-encoded `value` (`JSON.parse` of a JSON string; SQL NULL and the
     string `"null"` both mean clear — REST-side clears store the latter); compare against
     `field_clock` with the server's rule (greater `edited_at` wins, equal `edited_at` and greater
     `device_id` wins); on a win, write the field and the clock entry. Integer values for
     reference fields are translated to uuids.
   - `delete`: tombstone with the same local cascade as a local delete, without queueing.
4. Advance `cursor_seq`, store `epoch`.

`410` means re-bootstrap. `GET /sync/bootstrap` replaces every row that has a `server_id`, leaves
rows that do not (local-only creates still queued), rebuilds `field_clock` from nothing (the
snapshot is the truth; queued ops carry their own `edited_at` and will be judged on push), and
resets the cursor and epoch. Nothing in the op queue is lost by a re-bootstrap.

A placeholder row created by a `create` change row can be visible to the UI before its `set`s
arrive only if the page boundary falls between them; a page is applied in one transaction, and
the phone requests pages of 1000, so this is a row with an empty name for at most one page's
worth of ops. Accepted.

## Sync scheduling and status

Sync runs: on app foreground; after any local write, debounced by two seconds, when a connection
is available; on pull-to-refresh; and every 15 minutes through WorkManager with a network
constraint, which also covers the app being killed with ops queued. Push and pull are one
serialised job — never two at once. Metadata syncs on any connection; blobs follow the
connection rule.

Status is one `StateFlow<SyncStatus>`: `Idle(lastSyncedAt)`, `Syncing`, `Offline(pending)`,
`Pending(n)`, `Failed(dead)`, `SignedOut`. The Objects screen shows it as one quiet line under
the title ("Synced just now", "3 changes waiting", "Offline · 3 waiting", "1 change couldn't be
saved" linking to Settings › Sync). Nowhere else is the network mentioned. An attachment whose
upload has not happened carries a small cloud-off badge on its thumbnail, because a photo that
exists only on this phone is a fact the person should be able to see.

## Blobs

Content-addressed under `filesDir/blobs/<sha256[0..2]>/<sha256>` and
`filesDir/thumbs/<sha256>.jpg`, mirroring the server's layout.

- **Capture.** Camera or picker delivers a URI; the app copies the bytes to a temp file, hashes
  them, moves the file into place, reads EXIF (`DateTimeOriginal` offered as the entry date,
  orientation applied), generates a 400 px JPEG thumbnail, and inserts `files` + `attachments` +
  `blobs` rows and the create op — all before the form even closes. Photos are stored as taken;
  the server keeps originals and would reject nothing the phone stores.
- **Download.** After each pull, every `files` row with a `server_id` and no thumbnail queues a
  thumbnail download (any connection, never evicted). Originals download on unmetered
  connections until the budget is reached; a tap on a photo whose original is absent fetches it
  on demand on any connection. Eviction is LRU on `last_access_at` over originals only.
- **Coil** loads every image through a custom fetcher keyed on `sha256`, so a screen never knows
  whether bytes came from the camera five seconds ago or the server five days ago.

## Authentication

1. **Server** screen: URL field, `GET /health` on submit. Stored in DataStore. Shown again only
   from Settings › Account.
2. **Sign in**: username and password to `POST /auth/login` through an OkHttp client with a
   cookie jar scoped to this exchange; `POST /auth/tokens` with name `LogB Android · <device
   model>`; `POST /auth/logout` to end the cookie session; the token goes into the Keystore-wrapped
   store; the cookie jar is discarded. `GET /auth/me` fills the session (`user_id`, username,
   `is_admin`, `lang`).
3. Every later request carries `Authorization: Bearer`. A `401` clears the token, keeps the
   mirror, and returns to Sign in with the server prefilled and a one-line explanation. Signing
   back in as the same user reopens the same database; as a different user opens theirs.
4. Signing out revokes the token (`DELETE /auth/tokens/{id}`, best effort), clears it locally,
   and keeps the mirror unless the person picks *also remove local data*.
5. Optional biometric or device-credential lock on foreground (`androidx.biometric`), off by
   default; a later phase.

## Domain ports

Ported line for line, with each Rust test translated into a JVM test, so the phone and the server
agree on every edge that already has a name:

| Rust | Kotlin | Notes |
|------|--------|-------|
| `domain/reminder.rs` | `core/domain/ReminderRules.kt` | `isDue`, `isUpcoming`, `readingNextDue`, `readingStatus`, `nextDue`, `snoozedDate`, `daysUntil`, `counterUntil`; calendar-month clamping via `java.time` |
| `domain/insights.rs` | `core/domain/Insights.kt` | daily rate, estimated date, monthly usage, fuel consumption |
| `domain/stats.rs` | `core/domain/SpendStats.kt` | `summarize` over an object tree, children rolled up |
| `api/objects.rs::stats`, `derived`, `due_readings`, `ancestors` | `ObjectDao` SQL | `total_cost_cents`, `activity_count`, `current_counter`, `last_activity_date`, `last_reading_date`, `due_reminder_count`, recursive ancestors and descendants (`WITH RECURSIVE`) |
| `api/reminders.rs::ReminderOut::build` | `ReminderPresenter.kt` | `due`, `days_until`, `counter_until`, `next_due_date`, `estimated_due_date` |
| `api/search.rs` | `SearchDao` | `LIKE` over name/description and title/notes with the same escaping |
| `api/activities.rs::recent_titles` | `ActivityDao` | 20 distinct recent titles for suggestions |
| `frontend/lib/object-types.ts`, `reminder-templates.ts`, `timeline-fold.ts` | `core/domain/ObjectTypes.kt`, `ReminderTemplates.kt`, `TimelineFold.kt` | type → icon and categories; per-type reminder templates; folding consecutive readings |

## Interface

**Material 3, LogB's palette.** Primary is the teal (`#1f6f5f` light, `#4fb39a` dark), background
the off-white (`#f7f7f5`) and near-black-green (`#121412`), error and warning as in `app.css`.
Shapes: 8 dp controls, 12 dp cards, full-round chips and FAB. Type: the platform sans, with
`tnum` font feature on every figure — costs and counters are the content and they line up.
Light and dark follow the system unless Appearance says otherwise; dynamic colour is a switch,
off by default.

**Icons.** The nine object-type glyphs and the log-entry mark are ported from `Icon.svelte` as
vector drawables, so an object looks the same on the phone as in the browser. Everything else is
Material Symbols (outlined). The launcher icon is an adaptive icon from `public/icon.svg`, with a
monochrome layer for themed icons.

**Navigation.** Bottom bar with the web shell's three destinations: **Objects**, **Search**,
**Settings**. Everything else is a drill-down. Type-safe routes; predictive back; the FAB
belongs to the screen, above the bar.

**Screens** (each mirrors the web screen of the same name, restated for a phone):

- *Objects.* Due banner ("2 reminders due" → a due list), root object cards: type icon, name,
  counter, total spent, last touched, due badge; a *Log* action on the card; long-press → Log
  entry / Log reading / Add reminder; *Archived* filter chip; FAB *New object*; empty state with
  the action; pull-to-refresh; the sync line.
- *Object detail.* Breadcrumb when nested; header with stats; tabs Timeline / Documents /
  Reminders / Info. Timeline groups by year, folds consecutive readings, filters by category,
  and shows thumbnail strips. Documents is a grid; tap opens the viewer (photos in-app, PDFs and
  others through the system). Reminders lists open then done, with due state and snooze. Info
  shows fields, contents (children with their own stats and *Add here*), insights, edit,
  archive, delete.
- *Entry form* (new/edit). Date, category (per type), title with recent-title suggestions,
  counter (prefilled; warns when lower), cost, fuel quantity when it applies, notes, and a photo
  row with *Camera*, *Gallery*, *Document*. EXIF date offered. Save reachable without scrolling
  past the photos on a Pixel-sized screen.
- *Reading form.* Counter and date only, one screen.
- *Object form.* Name, type, counter and fuel units, description, purchase date and price,
  parent picker (excludes self and descendants), and — for a new object — the type's reminder
  templates, unticked.
- *Reminder form* and *Done* dialog (link an existing entry or log one inline), snooze picker.
- *Search.* Objects (with "in Garage") and entries, local, as you type.
- *Statistics.* Spend over time, by object tree, by type, by category, year picker — the web
  screen's model, drawn with Compose canvases (no chart library).
- *Settings hub* with rows carrying their value: Appearance (theme · language · dynamic colour),
  Account (server · user · sign out · lock), Sync (status, pending, failed ops, storage budget,
  connection rule, *Sync now*), Notifications (daily reminder digest on/off and time), About.

**States.** Every list has an empty state that names what belongs there and offers the action.
Loading states do not exist for local data. Errors are for sync and blobs only, and they live in
the sync line and Settings › Sync.

**Accessibility.** Content descriptions on every icon-only control, 48 dp targets, contrast
checked in both themes, TalkBack pass on the four main screens, large-font pass.

**Language.** EN and DE, `strings.xml`, keys ported from `i18n/en.ts` / `de.ts` and kept in
parity by a unit test that walks both resource files.

**Share target.** The app accepts a shared image or PDF: pick an object, land in the entry form
with the file attached.

## Notifications

A daily WorkManager job at the hour chosen in Settings › Notifications (default 08:00) reads the
mirror, and if anything is due or comes due within seven days, posts one notification on a
"Reminders" channel: "Golf: oil change due · 2 more". Tapping opens the due list. Nothing is sent
anywhere; the server's digest, if configured, is unaffected.

## Testing

- **Domain ports**: table-driven JVM tests, one per Rust test, same names transliterated.
- **DAOs and the sync engine**: JVM tests against Room with the bundled driver — no emulator.
  The apply rule gets the cases `tests/sync.rs` has (newer wins, older superseded, tie on
  `device_id`, wrong type rejected, delete cascades, placeholder then sets, 410 → bootstrap
  keeps queued creates).
- **Network**: MockWebServer for the client, error mapping, bearer header, 401 handling, token
  minting sequence.
- **Contract**: a Gradle task that starts a real `logb` binary on a temp data dir (the same
  harness `tests/common` uses) and runs the engine against it end to end: bootstrap → offline
  create tree with photo → push → browser-side edit via REST → pull → LWW outcome. CI checks out
  `13/logb` beside this repo to build it.
- **UI**: Compose tests for onboarding, dashboard, entry form with photo, and the Settings › Sync
  retry/discard; screenshot tests of each screen in both themes at phone width.
- **Manual**: a smoke checklist on the connected device for camera, share target, background
  sync after force-stop, and the biometric lock.

## Phases

Each phase is its own plan and its own review; each leaves working software.

0. **Server prerequisites** (`logb` repo). The four additions, tests, `openapi.json`. Ships as a
   normal server release; the web client is unaffected.
1. **Foundation, read-only.** Project, theme, icons, Room schema, network client, server URL +
   sign in + token store, bootstrap, pull and apply, scheduler, Objects / detail / search /
   settings screens rendering from the mirror. Thumbnails through Coil's network cache for now.
   *Deliverable: sign in, everything the account owns is on the phone and stays readable
   offline, browser edits appear on the phone.*
2. **Offline writes.** Op queue, push, create-through-REST, delete cascade, all forms, reminder
   done and snooze, Settings › Sync with failed ops. *Deliverable: everything except photos works
   with the radio off and reconciles when it comes back.*
3. **Blobs.** Blob store, capture, thumbnails, uploads, downloads, budget, viewer, share target.
   *Deliverable: photos both directions, offline.*
4. **Statistics and insights.** The remaining domain ports and their screens.
5. **Polish.** Local reminder notifications, biometric lock, app shortcuts, screenshot suite,
   release signing (`~/sync/AndroidKeystore`, see memory), CI, README with screenshots.

## Risks

- **Two doors onto one write.** The phone's delete cascade and LWW must match the server's
  exactly; the contract test is the guard, and the server's own tests are the specification.
- **Reference translation.** Integer ids in pulled `set` values must resolve to uuids. Phase 0's
  `entity_id` makes it possible; the self-heal to bootstrap makes an unresolved one recoverable.
- **Toolchain drift.** Compose, Room and AGP move monthly; versions are pinned in the catalog and
  the plan's first task is a build that succeeds on this machine with JDK 21.
- **Scope.** Five phases is a lot of app. Each phase ships alone, and phase 1 is useful on its
  own as a read-only pocket copy of the logbook.

## Out of scope

iOS. Tablet-specific layouts (the phone layout scales, nothing more). Widgets. Wear. Multiple
servers at once. Sharing between users. Anything listed under decision 3.
