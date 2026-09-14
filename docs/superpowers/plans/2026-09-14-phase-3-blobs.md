# Phase 3: Photos and documents, both directions — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Photos and documents work offline in both directions: take or pick one against an entry or an object and it is stored, shown and queued; everything the server holds is mirrored as thumbnails always and originals within a budget; a shared image lands in an entry form.

**Architecture:** A content-addressed blob store under `filesDir` (`blobs/<sha[0..2]>/<sha>`, `thumbs/<sha>.jpg`) mirrors the server's layout. Capture writes the blob, the `files` + `attachments` rows and an `attachment` create op in one transaction; the push engine turns that op into the multipart upload. After every pull a download pass fetches missing thumbnails on any connection and originals on unmetered ones until the budget binds. Coil loads every image through one fetcher keyed on `sha256`, so screens never know where bytes came from.

**Tech Stack:** As phase 2, plus `androidx.exifinterface`, `BitmapFactory` with inSampleSize, `FileProvider`, Activity Result contracts (`TakePicture`, `PickVisualMedia`, `OpenDocument`), Coil 3 custom `Fetcher`.

Spec: `docs/superpowers/specs/2026-09-14-logb-android-design.md`, section *Blobs*, plus *Share target* under *Interface*.

## Global Constraints

- Blobs are keyed by `sha256`; the `files` row's `sha256` is the join. A capture's sha is computed while copying the bytes, never trusted from anywhere else.
- Thumbnails: 400 px on the longest side, JPEG quality 80, EXIF-rotated, never evicted. Originals: an LRU cache under `blobs.budgetBytes` (default 4 GB) over `last_access_at`; evicted originals are re-downloaded on demand.
- Downloads: thumbnails on any connection; originals only when `Connectivity.isUnmetered` unless the person turned that rule off in Settings › Sync; an explicit tap on a photo fetches its original on any connection.
- The upload is `POST /api/objects/{id}/attachments` multipart with `file`, optional `activity_id`, `caption`, `client_op_id`, `client_uuid`; it needs the object's (and the entry's) `server_id`, else it waits like any create. A `409` marks the op dead. The response's `file_uuid` may differ from the local file row's uuid (identical bytes dedup on the server): the attachment is re-pointed at a file row carrying the server's uuid and the local-only file row goes.
- A `file` row that arrives through the feed as a placeholder (no sha) is healed by the bootstrap the applier already requests; nothing in this phase invents metadata.
- Photos from the camera are stored as taken; the app never recompresses an original.
- Lint clean, unit tests green, EN and DE strings, before every commit.

## File map

```
core/blobs/BlobStore.kt         paths, write-with-hash, thumbnail, usage, evict, delete
core/blobs/Thumbnails.kt        decode + EXIF rotate + scale, pure function over a File
core/blobs/BlobDownloader.kt    the download pass after a pull; on-demand original
core/blobs/BlobFetcher.kt       Coil Fetcher/Keyer for BlobImage(sha, thumb)
core/blobs/BlobPrefs.kt         budget, unmetered-only (DataStore)
feature/entries/AttachmentRepository.kt   import(uri...), delete, setCaption, setCover
core/sync/PushEngine.kt         attachment create → upload, re-key on dedup
feature/entries/AttachmentPicker.kt       camera / gallery / document buttons + result handling
feature/entries/AttachmentViewer.kt       full-screen photo, document open, delete, caption, cover
feature/share/ShareTargetScreen.kt        pick an object for a shared file
feature/settings/SyncScreen.kt            storage: used, budget, unmetered rule
AndroidManifest: FileProvider, ACTION_SEND filter; res/xml/file_paths.xml
```

---

### Task 1: BlobStore and thumbnails
**Files:** create `core/blobs/{BlobStore,Thumbnails,BlobPrefs}.kt`; test `core/blobs/BlobStoreTest.kt` (Robolectric, NATIVE graphics).
**Produces:** `class BlobStore(context) { fun original(sha): File; fun thumb(sha): File; suspend fun writeOriginal(source: InputStream): Written(sha, size); suspend fun makeThumb(sha, mime): Boolean; fun hasOriginal/hasThumb; fun usageBytes(): Long; suspend fun evictOriginalsOver(budget, keep: Set<String>, lru: List<String>); fun deleteAll(sha) }`, `Thumbnails.render(src: File, maxPx = 400): ByteArray?` (null for non-images), `Thumbnails.exif(src): Exif(takenAt: String?, width, height)`.
- [ ] Tests: writing bytes yields the sha of those bytes and a file under `blobs/ab/abcd…`; writing the same bytes twice keeps one file; `render` of a 1200×800 JPEG gives a 400×267 JPEG and rotates an EXIF-orientation-6 image to portrait; usage counts originals only; eviction removes the least recently used originals until under budget and never touches thumbs or `keep`.
- [ ] Implement; commit `feat: content-addressed blob store with thumbnails and an LRU budget`.

### Task 2: AttachmentRepository — capture into the mirror
**Files:** create `feature/entries/AttachmentRepository.kt`; test `feature/entries/AttachmentRepositoryTest.kt`.
**Produces:** `suspend fun import(source: InputStream, name: String, mime: String, objectUuid: String, activityUuid: String?, caption: String = ""): String` (attachment uuid) — writes the blob, the thumb, `files` (uuid minted, sha, name, mime, size, width/height, taken_at from EXIF) unless a live file row with that sha exists (then reuse it), `attachments`, `blobs`, and a `create` op through `LocalWriter`; `delete(attachmentUuid)` (tombstone through `LocalWriter.delete`, blob kept until nothing references it); `setCaption(uuid, caption)`; `setCover(objectUuid, attachmentUuid?)`.
- [ ] Tests: import creates the three rows and the op with kind `create`, entity `attachment`; the same bytes on a second import reuse the file row; delete queues a delete op for a pushed attachment and hard-deletes an unpushed one; the thumbnail exists after import of a PNG; `taken_at` comes from EXIF.
- [ ] Commit `feat: attachments captured into the mirror and the op queue`.

### Task 3: Upload in the push engine
**Files:** modify `core/sync/PushEngine.kt`, `core/network/LogbApi.kt` (`@Multipart @POST("api/objects/{id}/attachments") suspend fun upload(...)`); test additions in `core/sync/PushEngineTest.kt`.
- [ ] Tests: an attachment create posts multipart with `file`, `client_uuid`, `client_op_id`, `activity_id` (once the entry has a server id) and `caption`; the response's `id` becomes the attachment's `server_id` and its `file_id` the file's; when `file_uuid` differs from the local file uuid, the attachment is re-pointed at a file row with the server's uuid and the old row is gone; a create for an attachment whose entry has no `server_id` yet waits; a missing blob (evicted before upload) marks the op dead with a clear reason.
- [ ] Implement (remove the phase-2 skip); commit `feat: photos and documents upload with the entry they belong to`.

### Task 4: Downloads, the Coil fetcher, cover thumbnails
**Files:** create `core/blobs/{BlobDownloader,BlobFetcher}.kt`; modify `di/SyncModule.kt` (runner: push → pull → download pass), `LogbApp.kt` (Coil components), `feature/objects/ObjectsScreen.kt` (card cover), `feature/objects/tabs/{TimelineTab,DocumentsTab}.kt` (BlobImage models); test `core/blobs/BlobDownloaderTest.kt`.
**Produces:** `data class BlobImage(val sha: String, val thumb: Boolean)`; `BlobDownloader.runAfterPull()`: every live file with `server_id` and no thumb → `GET /api/files/{id}/thumb` (any network); originals `GET /api/files/{id}` while unmetered (or the rule is off) and under budget, newest entries first; `suspend fun ensureOriginal(sha): File?` on demand; then `evictOriginalsOver(budget)`. Coil: `BlobFetcher` serves the thumb or original file for a `BlobImage`; if absent and online, fetches via the downloader first.
- [ ] Tests (MockWebServer + fake connectivity): a thumb is fetched for a file without one; originals are skipped on metered and fetched on unmetered; a failed download leaves the row for next time; `ensureOriginal` fetches once and returns the file.
- [ ] Cards show the cover thumbnail in place of the type icon when the object has one; timeline strips and the Documents grid use `BlobImage`; an attachment whose create op is still queued shows a small cloud-off badge.
- [ ] Commit `feat: thumbnails mirror eagerly, originals within a budget; images load from the blob store`.

### Task 5: Picking, viewing, deleting
**Files:** create `feature/entries/{AttachmentPicker,AttachmentViewer}.kt`, `res/xml/file_paths.xml`; modify `AndroidManifest.xml` (FileProvider), `ActivityFormScreen.kt`/`ActivityFormViewModel.kt` (photo row; new entry attaches on save, edit attaches now), `DocumentsTab.kt` (object-level add, tap → viewer), `TimelineTab.kt` (thumb tap → viewer), routes (`Viewer(attachmentUuid)`), strings.
- [ ] Picker: three buttons — camera (`TakePicture` to a FileProvider temp file), gallery (`PickMultipleVisualMedia`), document (`OpenMultipleDocuments` for `application/pdf`, text and office types) — each returning content URIs the view model imports through `AttachmentRepository` with the display name and MIME from the resolver. The entry form lists pending files as thumbnails with a remove cross before save; the EXIF date of the first photo is offered as the entry date when the date is still today.
- [ ] Viewer: full-screen photo (original via `BlobImage(thumb = false)`, fetched on demand with a progress ring), pinch-zoom, caption edit, *Use as cover* (photos on an object), *Open* for documents (`ACTION_VIEW` through the FileProvider), *Delete* with confirmation.
- [ ] Device check: take a photo offline against an entry; it shows in the timeline strip and Documents with the cloud-off badge; reconnect; the badge goes; the browser shows it; delete it on the phone; the browser loses it.
- [ ] Commit `feat: take, pick, view and delete photos and documents offline`.

### Task 6: Share target, storage settings, contract test, docs
**Files:** create `feature/share/ShareTargetScreen.kt`; modify `AndroidManifest.xml` (ACTION_SEND / SEND_MULTIPLE for `image/*` and `application/pdf`), `MainActivity.kt` (intent → route), `feature/settings/SettingsScreens.kt` (storage section), contract test, `docs/smoke-checklist.md`, `README.md`.
- [ ] Share target: a shared image or PDF opens a picker of the person's objects (search box, type icon), then the entry form with the file attached and the category defaulted to the type's first.
- [ ] Settings › Sync gains *Storage*: used space, budget (1 / 2 / 4 / 8 GB), *Download originals on unmetered connections only* switch, *Free up space* (evict every original now).
- [ ] Contract test: capture a PNG offline against a new entry, push, verify `GET /objects/{id}/attachments` lists it with the phone's `client_uuid`; upload the same bytes again → the server dedups and the phone re-keys the file row; bootstrap on a fresh mirror and download the thumb, compare bytes with `/files/{id}/thumb`.
- [ ] Commit `test: contract test for uploads and dedup; share target; storage settings`.

## Self-review
Spec *Blobs*: capture ✔ T2/T5, download rules and eviction ✔ T1/T4, Coil fetcher ✔ T4, viewer ✔ T5, cloud-off badge ✔ T4, share target ✔ T6, storage budget setting ✔ T6. The `kind` (photo/document) is the server's call from the MIME; the phone mirrors that rule (`image/*` → photo) for local rows.
