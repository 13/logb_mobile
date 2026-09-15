# LogB for Android, next: polish, the web's newer features, and life outside the app

Status: designed 2026-09-14, not built. Follows `2026-09-14-logb-android-design.md` (phases 0–6, released as
0.6.0). Targets logb 0.8.0 (tags, own types, the new objects list) and a logb 0.9.0 that does not exist yet
(QR pairing). `logb.muh` runs 0.7.1 at the time of writing; everything here keeps working against it.

## Decisions taken in this design

Settled in conversation with the user on 2026-09-14.

| # | Decision | Why | Reversal cost |
|---|----------|-----|---------------|
| 1 | **One spec, eight phases, one release tag per group of phases.** | The user asked for everything in one design; phases keep each release shippable. | Cheap: phases are independent after phase 1. |
| 2 | **Features follow the server's version** (`/api/health`), hidden when the server is too old. | `logb.muh` runs 0.7.1; the app must not break or silently lose tags against it. | Cheap. |
| 3 | **First open still needs a server**, now with a QR code as the fast path. | The app is a mirror of a self-hosted server; a local-only mode would need server-side id reconciliation. | Local-only mode later is expensive. |
| 4 | **Archived is a top-bar icon**, not a chip and not a tab row. | User request; a tab row costs phone height the web does not pay. | Cheap. |
| 5 | **A QR code carries a short-lived, single-use pairing code, never an API token.** | Tokens in logb never expire; a token in a picture is a permanent login. | Needs a logb release either way. |
| 6 | **ZXing, not ML Kit**, for scanning. | No Google Play Services dependency, no network or analytics in a self-hosted app. | Cheap. |
| 7 | **Web push subscriptions stay web-only.** | Web Push needs a browser (or Firebase) on the receiving end. The server's webhook digest and the app's local digest cover the phone. | Expensive (Firebase). |
| 8 | **The update check is the one request not sent to the user's server**, and it can be switched off. | Signed APKs are published on GitHub releases; sideloaded apps get no store updates. | Cheap. |

## Goal

Close the gap the web client opened again with logb 0.8.0 (tags, own types, the new objects list), bring over
the web settings the app never had (API tokens, full export and import, the server notification digest), make
the first connection a scan instead of typing, and let reminders be handled without opening the app. Tidy the
About page and the archived toggle on the way.

## What exists

- App 0.6.0: objects list with a "Archiviert" filter chip, About page with version, commit hash, one line of
  text and licences. Server screen asks for an address, then username and password; sign-in mints an API token
  through a cookie session (`SessionRepository.signIn`).
- Room database at version 1, schema exported (`app/schemas/.../1.json`), no migrations yet.
- Built-in object types hard-coded in `core/domain/ObjectTypes.kt`.
- One daily digest notification (`ReminderNotifier`, `DigestWorker`), no actions.
- Static launcher shortcuts: due, search, new object. Share target: pick an object, then the entry form with
  the files attached.
- logb 0.8.0 (on `main`): `tags` as JSON text on objects and activities (sync field, `/tags` with counts),
  `object_type` sync entity with `name`, `icon`, `categories` (JSON text), `counter_unit` and `/types` CRUD,
  `frontend/src/lib/object-list.ts` (sorts `name`, `last-activity`, `changed`, `cost`, `counter`; tabs
  `active`/`archived`), `frontend/src/lib/tags.ts` (10 tags, 32 chars, fold, 8 colour slots).
- logb token rules: `create_token` needs an interactive session (`SessionUser`); listing, revoking, export,
  import and notification settings accept a token (`AuthUser`). Import only adds rows; types are reused by
  folded name, everything else is created again.

## Phases

| Phase | What | Needs server | Release |
|-------|------|--------------|---------|
| 1 | Server capabilities, About page, archived icon | any | 0.7.0 |
| 2 | Objects list parity | any | 0.7.0 |
| 3 | Tags | 0.8.0 | 0.8.0 |
| 4 | Own types | 0.8.0 | 0.8.0 |
| 5 | API tokens, data export and import, server notification digest | any | 0.9.0 |
| 6 | QR sign-in (logb and app) | 0.9.0 | 0.9.0 |
| 7 | Notification actions, home-screen widget | any | 0.10.0 |
| 8 | Photo-first entry, recent-object shortcuts, update check | any | 0.11.0 |

## Phase 1: server capabilities, About, archived icon

### Server capabilities

`core/server/ServerCapabilities`: reads `version` from `/api/health` at sign-in and on every sync run, stores it
in `ServerStore`, and exposes `tags`, `ownTypes` (both `>= 0.8.0`) and `pairing` (`>= 0.9.0`) as a `StateFlow`.
Versions compare numerically per part; an unparsable version counts as `0.0.0`.

- Screens ask the capabilities before showing tag inputs, the Types page, own-type choices or the QR button.
- The Account screen lists what the server does not support: "Tags and own types need LogB 0.8.0 or newer".
- `FieldSpecs` lists the new fields from the start; an old server's feed never contains them.
- Release 0.7.0 only records the version. The mirror cannot hold tags or own types before the Room
  version 2 migration, so no bootstrap is requested on a version change.

### About page

Top to bottom:

1. Logo: the launcher foreground on its adaptive background, 96 dp. "LogB", then "0.7.0 (700)".
2. Build: build date, commit, build type, signature.
   - Build date is the commit's date (`git show -s --format=%cI HEAD` through `providers.exec`), not the
     wall clock, so `BuildConfig` stays stable and the configuration cache keeps working.
   - Commit opens `https://github.com/13/logb_mobile/commit/<hash>`.
   - Build type: release or debug.
   - Signature: "release key" when the signing certificate's SHA-256 is `EF:46:D3…`, else "debug key".
3. Server: address, version, the capabilities it enables.
4. Links: source code, issues (`github.com/13/logb_mobile`), the server's web app.
5. Licences (existing text).
6. "Copy details": everything above as plain text on the clipboard.
7. Update check controls (phase 8).

### Archived icon

The "Archiviert" chip goes. The objects top bar gets an archive icon action (`Icons.Outlined.Inventory2`, filled
variant when active, content description "Archived"). Active: title reads "Archived", system back returns to
the active list. The empty state keeps its archived wording.

## Phase 2: objects list parity

Port `object-list.ts` line for line into `feature/objects/ObjectListing.kt`, with its tests:

- Sorts exactly as the web: `name`, `last-activity`, `changed`, `cost`, `counter`; null values last.
- Accent-insensitive search (`matchesQuery`): name, type label, and (phase 3) tags.
- `visibleRows(active, archived, tab, query, sort, tag)`; the tab is the archived icon's state.
- Cards show the last-activity label and usage rate as the web cards do.
- The chosen sort is remembered per device (DataStore), as today.

## Phase 3: tags

### Data

- Room version 2 migration: `tags TEXT NOT NULL DEFAULT '[]'` on `objects` and `activities`. One migration for
  phases 3 and 4 (the `object_types` table is added in the same step).
- `FieldSpecs`: `tags` as `Text` on `object` and `activity`. Bootstrap maps `tags`; `FieldWriter` writes it.
- A pushed value the server rejects (too many, too long) is dropped like any other rejected op today.
- The version 2 migration sets `sync_state.bootstrap_needed = 1` (when the row exists), so the first sync
  after updating the app pulls a snapshot and the tags and own types the server already holds reach the
  mirror. From then on, `ServerCapabilities.refresh` returning true (a server updated to 0.8.0 while the
  app already runs 0.8.0) requests a bootstrap before the pull; that branch gets a unit test.

### Rules

`core/domain/Tags.kt`, a port of `tags.ts` with its tests: `MAX_TAGS = 10`, `MAX_TAG_CHARS = 32`, `foldTag`
(locale-independent lower case, accents stripped), `normalizeTag`, `addTag` (errors `too-long`, `too-many`,
`empty`), `removeTag`, `suggestTags`, `tagColorIndex` (8 slots), `contrastRatio`. The palette is 8 colour pairs
for light and dark themes, each tested for WCAG AA contrast against its chip text.

### Interface

- Tag input on the object and entry forms: chips plus a text field; suggestions come from tag counts computed
  from the local database, so they work offline.
- Tag chips on object cards, the object header and timeline entries.
- Tapping a chip filters the objects list by that tag; the filter shows as a removable chip under the top bar.
- Search matches tags.
- Hidden when the server lacks the `tags` capability.

## Phase 4: own types

### Data

- `object_types` table (same version 2 migration): `uuid` primary key, `server_id`, `name`, `icon`,
  `categories` (JSON text), `counter_unit`, `created_at`, `deleted_at`.
- `object_type` joins `FieldSpecs` with `name`, `icon`, `categories`, `counter_unit`; bootstrap and pull apply
  it; creates go through the op queue with a phone-minted uuid, like objects.
- An object's `type` holds `custom:<uuid>` for an own type, as the server does (`TypeOut.key`).
- Push order: types before objects, so an object created offline with a new type never arrives first.

### Registry

`core/domain/TypeRegistry` merges built-in `ObjectTypes` with the table and answers label, icon, categories and
counter unit for a type key. The object form, card icons, the entry form's categories, search and statistics
use the registry instead of `ObjectTypes`. An unknown key (a type not pulled yet) gets a generic icon and no
categories.

### Types page

Settings → Types: the user's own types. Add and edit: name, icon (chosen from `LogbIcons`), categories as
chips, counter unit. Delete is offered only when no live object uses the type. Names are unique after folding,
checked on the phone; the server's `name_taken` error is translated too. Hidden when the server lacks the
`ownTypes` capability.

## Phase 5: settings the web has

All three pages need a connection and show "Needs a connection" offline.

### API access

- List tokens: name, created, last used. The phone's own token (`ServerRecord.tokenId`) is marked
  "This phone" and has no revoke button.
- Revoke others after a confirmation.
- Create: logb creates tokens only for an interactive session, so the app asks for the password, logs in with
  the cookie client, calls `POST /auth/tokens`, logs out. The new token is shown once with Copy and a
  "shown only once" warning.

### Data

- Export all: `GET /api/export` streamed to a file the user picks with the system file picker
  (`ACTION_CREATE_DOCUMENT`, `logb-export-<date>.zip`). Runs as a WorkManager job with a progress
  notification, so leaving the screen does not cancel it.
- Import: pick a zip (`ACTION_OPEN_DOCUMENT`), a warning that import adds everything and a second import
  duplicates objects and entries, confirm, upload to `POST /api/import`. Shows the returned counts, then
  requests a bootstrap. 413 shows "The archive is larger than this server accepts".

### Notifications

The existing page (phone permission, local digest) gains a "Server digest" section: webhook address (http or
https with a host, blank for none), format (text or JSON), the server's send hour (read-only), and
"Send test" showing sent and failed counts. Push subscriptions stay web-only (decision 7).

## Phase 6: QR sign-in

### logb (0.9.0)

- `pairing_codes` table: `id`, `user_id`, `code_hash`, `created_at`, `expires_at`, `used_at`.
- `POST /api/auth/pair` (`SessionUser`): 32 random bytes, base64url; stores the SHA-256; valid 5 minutes;
  returns `{code, expires_at}`.
- `POST /api/auth/pair/redeem` (no auth): `{code, device_name}` → mints a token named after the device, marks
  the code used in the same transaction, returns `{token, token_id}`. Unknown, expired and used codes return
  the same 401. Rate-limited like login.
- Web Account page: "Connect a phone" shows a QR code for
  `logb://pair?server=<url-encoded base>&code=<code>`, a countdown, and "Show code" to type it instead.

### App

- Server screen: "Scan QR code" above the address field; camera permission asked on tap.
- Scanner: `com.journeyapps:zxing-android-embedded`, QR format only.
- A scanned or opened `logb://pair` link: check the server is reachable, redeem, store token and server record
  (`/api/me` for the user), start the bootstrap. The username and password step is skipped.
- The link is also a deep link, so the phone's own camera app works.
- 404 from redeem: "This server does not support QR sign-in (needs LogB 0.9.0). Sign in with your password."
- A signed-in app opening a pairing link asks before replacing the current account.

## Phase 7: reminders outside the app

### Notification actions

- One due reminder: the digest notification gets **Done** and **Snooze 7 days**.
- Several: a notification group, one child per due reminder (at most 5, the summary says "and N more"), each
  with Done and Snooze.
- Reading reminders get **Log reading** (opens the reading form) instead of Done.
- Actions go to `ReminderActionReceiver`, which calls `ReminderRepository.done` / `snooze` through the op queue
  (`goAsync`), cancels that notification and enqueues a one-off sync. Works offline, does not open the app,
  does not ask for the screen lock.

### Widget

- Glance 1.2.0 (as apexweather). Header "Due", up to 5 reminders due or due within seven days
  (`DueListModel.items(withinDays = 7)`, as the digest): object, title, when. "Nothing due" when empty.
- Tapping a row opens that object's reminders tab; the check button marks it done through the same code path
  as the notification action.
- Refreshed after each sync, after each local reminder change, and at midnight.
- Resizable; the smallest size shows the count and the next reminder only.
- With the screen lock on, only the count is shown ("3 due").

## Phase 8: photo-first, shortcuts, update check

### Photo-first entry

A "Photo" static shortcut and a camera action on the objects list open the system camera
(`ActivityResultContracts.TakePicture` into app cache, no camera permission), then the object picker the share
target uses, then the entry form with the photo attached.

### Recent objects

Up to 3 dynamic shortcuts for the most recently opened objects, updated on open; archived and deleted objects
are removed. Opening one asks for the screen lock when it is on.

### Update check

Built ahead of the rest of this phase, on branch `release-0.7.1`, as
`docs/superpowers/plans/2026-09-15-release-0.7.1-in-app-update.md`: see that plan for the design and its
deviations from the sketch originally here (an OkHttp-based downloader and `PackageInstaller` session instead
of `DownloadManager`, the signing check run before the installer is involved, and the found-update text on the
Settings hub's About row instead of a notification). Executed end to end on an emulator 2026-09-15; see that
plan's status line and `docs/smoke-checklist.md`'s "Release 0.7.1 (in-app update)" section for the record.

## Testing

- Unit (JVM): `Tags` and `ObjectListing` ports with the web's cases; `ServerCapabilities` version comparison;
  type registry and name folding; update version comparison; widget row and shortcut selection.
- Room: migration test from `1.json` to version 2, existing rows intact, defaults applied.
- Contract (`SyncContractTest`, real logb from `main`): tags and own types set on the phone and seen by REST,
  and back; a pairing redeem test once logb 0.9.0 exists.
- Roborazzi goldens re-recorded and looked at: About, objects list (icon, tags, tag filter), Types page, API
  access, Data, notification settings, widget.
- Device smoke checklist (SM-A346B): notification actions offline and online, widget on the home screen, QR scan
  from the web page and from the camera app, update install over a signed release.

## Risks

- **Notification actions and the op queue.** A Done from a notification while a sync is running must not race
  the pull; it goes through `LocalWriter` like any other write, which already serialises with the engines.
- **Update install signature check.** A wrong comparison would offer an APK the installer then refuses; the
  check compares the full certificate digest, and the smoke checklist installs a real release.
- **Pairing endpoint abuse.** Codes are 256 bits, single-use, 5 minutes, hashed, rate-limited.
- **Migration 1 → 2 on phones with unsynced ops.** The migration only adds columns and a table; pending ops keep
  their format.

## Out of scope

Local-only mode without a server. Web push on the phone (Firebase). User administration and database switching
(spec 1, decision 3). Multiple servers at once. A Play Store listing.
