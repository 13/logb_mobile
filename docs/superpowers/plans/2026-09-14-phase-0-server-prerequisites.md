# Phase 0: Server prerequisites for the Android client — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the sync protocol the four additive things a phone-side mirror needs: client-minted identity on REST creates (idempotent), `client_uuid` in every entity response, the server id on every pull row, and change-feed entries for the two reference cleanups that currently happen silently.

**Architecture:** All changes are in the `logb` repository (`~/repo/logb`), Rust, on a feature branch. Each addition is backward compatible: the web client sends none of the new inputs and ignores the new outputs. Tests are HTTP-level integration tests beside the existing ones, run against SQLite by default and PostgreSQL via `LOGB_TEST_DATABASE_URL` in CI.

**Tech Stack:** Rust 2021, axum 0.8, sqlx 0.9 (SQLite + PostgreSQL through `Any`), serde, the `tests/common` harness.

Spec: `docs/superpowers/specs/2026-09-14-logb-android-design.md` (this repo), section *Server prerequisites*.

## Global Constraints

- Work happens in `~/repo/logb` on a branch `android-prereqs` from `main`. Commit after every task.
- Every SQL statement must run on both SQLite and PostgreSQL. No `INSERT OR IGNORE`, no `datetime()`; scalar subqueries and `CASE` are fine on both.
- `client_uuid` is accepted as any string of 8–64 characters containing no whitespace. It is never validated as a UUID shape (backfilled rows are 32 hex characters, new ones 36).
- A create that replays a caller's own live `client_uuid` answers `200` with that row. One naming another user's row, or a tombstoned row, answers `409 conflict`.
- `docs/openapi.json` is edited by hand in the task that changes the shape it describes. `cargo test --test openapi` must stay green.
- Before claiming any task done: `cargo clippy --all-targets --locked -- -D warnings && cargo test --locked` from `~/repo/logb`. `frontend/dist` must exist for the binary to build in tests: `mkdir -p frontend/dist && cp frontend/public/icon.svg frontend/dist/` if it does not.
- Commit messages end with the attribution lines the session provides.

---

### Task 1: `client_uuid` on `POST /objects`, idempotent

**Files:**
- Modify: `src/api/mod.rs` (add `normalize_client_uuid`)
- Modify: `src/api/objects.rs` (`ObjectInput`, `create`)
- Test: `tests/objects.rs`

**Interfaces:**
- Produces: `pub(crate) fn normalize_client_uuid(raw: Option<String>) -> Result<Option<String>, AppError>` in `src/api/mod.rs`, reused by Tasks 2 and 3.
- Produces: `pub(crate) const CLIENT_UUID_TAKEN: &str = "client_uuid names a row you cannot reuse"` in `src/api/mod.rs`.

- [ ] **Step 1: Write the failing tests**

Append to `tests/objects.rs` (the harness helpers used here — `spawn`, `setup`, `create_user_client`, `create_object`, `url` — all exist in `tests/common/mod.rs`):

```rust
#[tokio::test]
async fn a_create_may_carry_its_own_client_uuid() {
    let app = common::spawn().await;
    app.setup("ben", "correct horse").await;
    let res = app.client.post(app.url("/objects"))
        .json(&json!({ "name": "Golf", "type": "car", "client_uuid": "phone-0001-golf" }))
        .send().await.unwrap();
    assert_eq!(res.status(), 201, "{}", res.text().await.unwrap());
    let created: serde_json::Value = res.json().await.unwrap();
    // The sync bootstrap is the read path that exposes uuids today.
    let boot: serde_json::Value = app.client.get(app.url("/sync/bootstrap")).send().await.unwrap().json().await.unwrap();
    let mine = boot["objects"].as_array().unwrap().iter()
        .find(|o| o["id"] == created["id"]).expect("the object is in the snapshot");
    assert_eq!(mine["client_uuid"], "phone-0001-golf");
}

#[tokio::test]
async fn replaying_a_create_with_the_same_client_uuid_returns_the_same_row() {
    let app = common::spawn().await;
    app.setup("ben", "correct horse").await;
    let body = json!({ "name": "Golf", "type": "car", "client_uuid": "phone-0001-golf" });
    let first: serde_json::Value = app.client.post(app.url("/objects")).json(&body).send().await.unwrap().json().await.unwrap();
    let res = app.client.post(app.url("/objects")).json(&body).send().await.unwrap();
    assert_eq!(res.status(), 200, "a replay is not a second create");
    let second: serde_json::Value = res.json().await.unwrap();
    assert_eq!(first["id"], second["id"]);
    let list: Vec<serde_json::Value> = app.client.get(app.url("/objects")).send().await.unwrap().json().await.unwrap();
    assert_eq!(list.len(), 1);
}

#[tokio::test]
async fn a_client_uuid_belonging_to_someone_else_or_to_a_tombstone_is_a_conflict() {
    let app = common::spawn().await;
    app.setup("ben", "correct horse").await;
    let body = json!({ "name": "Golf", "type": "car", "client_uuid": "phone-0001-golf" });
    let created: serde_json::Value = app.client.post(app.url("/objects")).json(&body).send().await.unwrap().json().await.unwrap();

    // Another account, same uuid.
    let other = app.create_user_client("anna", "another horse").await;
    let res = other.post(app.url("/objects")).json(&body).send().await.unwrap();
    assert_eq!(res.status(), 409);

    // The owner deletes it; the uuid now names a tombstone and cannot be revived by a replay.
    app.client.delete(app.url(&format!("/objects/{}", created["id"]))).send().await.unwrap();
    let res = app.client.post(app.url("/objects")).json(&body).send().await.unwrap();
    assert_eq!(res.status(), 409);
}

#[tokio::test]
async fn a_malformed_client_uuid_is_a_bad_request() {
    let app = common::spawn().await;
    app.setup("ben", "correct horse").await;
    for bad in ["short", "has space in it", &"x".repeat(65)] {
        let res = app.client.post(app.url("/objects"))
            .json(&json!({ "name": "Golf", "type": "car", "client_uuid": bad }))
            .send().await.unwrap();
        assert_eq!(res.status(), 400, "{bad:?}");
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd ~/repo/logb && cargo test --locked --test objects client_uuid`
Expected: FAIL — the first asserts `client_uuid` equals a value the server ignored; the second gets `201` twice.

- [ ] **Step 3: Add the validator**

In `src/api/mod.rs`, beside `normalize_op_id`:

```rust
/// The wording every create answers when a `client_uuid` names a row the caller may not adopt:
/// another account's, or a tombstone. One sentence for both, so the response does not say
/// which -- the same reasoning as the 404-not-403 rule on ownership checks.
pub(crate) const CLIENT_UUID_TAKEN: &str = "client_uuid names a row you cannot reuse";

/// A client-minted identity for a row a device made before the server saw it. Only the shape
/// is checked -- length and no whitespace -- never a UUID grammar: backfilled rows carry
/// 32-character hex and new ones 36-character v4, and a client is free to mint either.
pub(crate) fn normalize_client_uuid(raw: Option<String>) -> Result<Option<String>, AppError> {
    let Some(s) = raw else { return Ok(None) };
    let s = s.trim().to_string();
    if s.is_empty() { return Ok(None); }
    if s.len() < 8 || s.len() > 64 || s.chars().any(char::is_whitespace) {
        return Err(AppError::BadRequest("client_uuid must be 8-64 characters with no whitespace".into()));
    }
    Ok(Some(s))
}
```

- [ ] **Step 4: Accept it on `ObjectInput` and honour it in `create`**

In `src/api/objects.rs`, add to `ObjectInput`:

```rust
    /// Identity minted by the client before the server saw the row. A replay carrying the
    /// same value answers with the row the first attempt made. See `super::normalize_client_uuid`.
    #[serde(default)]
    pub client_uuid: Option<String>,
```

In `create`, after `body.validate()?;`:

```rust
    let client_uuid = super::normalize_client_uuid(body.client_uuid.take())?;
    if let Some(uuid) = client_uuid.as_deref() {
        // Idempotent on the caller's own live row; a conflict on anyone else's or on a
        // tombstone. Checked outside the write transaction on purpose: a hit answers without
        // ever taking the write lock, and a miss that races another replay trips the unique
        // index on `client_uuid` below, which is answered the same way.
        let existing: Option<(i64, i64, Option<String>)> = sqlx::query_as(
            "SELECT id, user_id, deleted_at FROM objects WHERE client_uuid = $1")
            .bind(uuid).fetch_optional(&state.db).await?;
        match existing {
            Some((id, owner, None)) if owner == user.id => {
                let row = load_owned_object(&state, user.id, id).await?;
                return Ok((StatusCode::OK, Json(with_stats(&state, row).await?)));
            }
            Some(_) => return Err(AppError::Conflict(super::CLIENT_UUID_TAKEN.into())),
            None => {}
        }
    }
    let object_uuid = client_uuid.unwrap_or_else(|| uuid::Uuid::new_v4().to_string());
```

and delete the old `let object_uuid = uuid::Uuid::new_v4().to_string();` line. Wrap the `INSERT … RETURNING` in the same unique-violation handling `activities::create` uses for `client_op_id`: on `is_unique_violation()`, roll back and answer `Conflict(CLIENT_UUID_TAKEN)`.

Note `ObjectInput` is also deserialized by `api::export` for imports; `client_uuid` there stays `None` because import archives never carry it. Nothing to change.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cargo test --locked --test objects client_uuid`
Expected: PASS (4 tests).

- [ ] **Step 6: Document in `docs/openapi.json`**

Add to `components.schemas.ObjectInput.properties`:

```json
"client_uuid": {
  "type": "string",
  "minLength": 8,
  "maxLength": 64,
  "description": "Identity the client minted before the server saw the row. A second create carrying the same value answers 200 with the row the first one made; one naming another account's row or a tombstone answers 409."
}
```

and to `paths./objects.post.responses` a `200` entry: `"description": "Already created: the row this client_uuid names"` with the same `ObjectOut` schema as `201`.

- [ ] **Step 7: Verify and commit**

Run: `cargo clippy --all-targets --locked -- -D warnings && cargo test --locked`
Expected: all green.

```bash
git add -A && git commit -m "feat: POST /objects accepts a client_uuid and replays idempotently"
```

---

### Task 2: `client_uuid` on activity and reminder creates

**Files:**
- Modify: `src/api/activities.rs` (`ActivityInput`, `create`)
- Modify: `src/api/reminders.rs` (`ReminderInput`, `insert`, `create`, the `done` successor)
- Test: `tests/activities.rs`, `tests/reminders.rs`

**Interfaces:**
- Consumes: `normalize_client_uuid`, `CLIENT_UUID_TAKEN` from Task 1.
- Produces: `async fn insert(tx, object_id, b: &ReminderInput, uuid: String) -> Result<(i64, String), AppError>` in `reminders.rs` (the uuid is now a parameter).

- [ ] **Step 1: Write the failing tests**

In `tests/activities.rs`:

```rust
#[tokio::test]
async fn an_activity_create_honours_and_replays_on_client_uuid() {
    let app = common::spawn().await;
    app.setup("ben", "correct horse").await;
    let car = app.create_object(&app.client, "Golf", Some("km")).await;
    let url = app.url(&format!("/objects/{}/activities", car["id"]));
    let body = json!({ "date": "2026-09-01", "category": "repair", "title": "Wipers", "client_uuid": "phone-0002-wipers" });
    let res = app.client.post(&url).json(&body).send().await.unwrap();
    assert_eq!(res.status(), 201);
    let first: serde_json::Value = res.json().await.unwrap();
    let res = app.client.post(&url).json(&body).send().await.unwrap();
    assert_eq!(res.status(), 200);
    let second: serde_json::Value = res.json().await.unwrap();
    assert_eq!(first["id"], second["id"]);
    let boot: serde_json::Value = app.client.get(app.url("/sync/bootstrap")).send().await.unwrap().json().await.unwrap();
    let a = boot["activities"].as_array().unwrap().iter().find(|a| a["id"] == first["id"]).unwrap();
    assert_eq!(a["client_uuid"], "phone-0002-wipers");
}

#[tokio::test]
async fn an_activity_client_uuid_cannot_adopt_a_row_on_another_object() {
    let app = common::spawn().await;
    app.setup("ben", "correct horse").await;
    let golf = app.create_object(&app.client, "Golf", Some("km")).await;
    let bike = app.create_object(&app.client, "Bike", None).await;
    let body = json!({ "date": "2026-09-01", "category": "repair", "title": "Wipers", "client_uuid": "phone-0002-wipers" });
    app.client.post(app.url(&format!("/objects/{}/activities", golf["id"]))).json(&body).send().await.unwrap();
    let res = app.client.post(app.url(&format!("/objects/{}/activities", bike["id"]))).json(&body).send().await.unwrap();
    assert_eq!(res.status(), 409, "the uuid names a row under a different object");
}
```

In `tests/reminders.rs`:

```rust
#[tokio::test]
async fn a_reminder_create_honours_and_replays_on_client_uuid() {
    let app = common::spawn().await;
    app.setup("ben", "correct horse").await;
    let car = app.create_object(&app.client, "Golf", Some("km")).await;
    let url = app.url(&format!("/objects/{}/reminders", car["id"]));
    let body = json!({ "title": "Oil", "due_date": "2027-01-01", "client_uuid": "phone-0003-oil" });
    let first: serde_json::Value = app.client.post(&url).json(&body).send().await.unwrap().json().await.unwrap();
    let res = app.client.post(&url).json(&body).send().await.unwrap();
    assert_eq!(res.status(), 200);
    let second: serde_json::Value = res.json().await.unwrap();
    assert_eq!(first["id"], second["id"]);
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cargo test --locked --test activities client_uuid && cargo test --locked --test reminders client_uuid`
Expected: FAIL on the `200` / equality assertions.

- [ ] **Step 3: Activities**

Add `#[serde(default)] pub client_uuid: Option<String>` to `ActivityInput` with the same doc comment as on objects. In `create`, after the `client_op_id` pre-check block:

```rust
    let client_uuid = super::normalize_client_uuid(body.client_uuid.take())?;
    if let Some(uuid) = client_uuid.as_deref() {
        let existing: Option<(i64, i64, i64, Option<String>)> = sqlx::query_as(
            "SELECT a.id, a.object_id, o.user_id, a.deleted_at FROM activities a \
             JOIN objects o ON o.id = a.object_id WHERE a.client_uuid = $1")
            .bind(uuid).fetch_optional(&state.db).await?;
        match existing {
            Some((id, oid, owner, None)) if owner == user.id && oid == object_id => {
                let row = load_owned_activity(&state, user.id, id).await?;
                return Ok((StatusCode::OK, Json(one_out(&state, row).await?)).into_response());
            }
            Some(_) => return Err(AppError::Conflict(super::CLIENT_UUID_TAKEN.into())),
            None => {}
        }
    }
    let activity_uuid = client_uuid.unwrap_or_else(|| uuid::Uuid::new_v4().to_string());
```

The existing unique-violation arm on the INSERT handles `client_op_id`; extend it so that when `body.client_op_id` is `None` the violation can only be `client_uuid` and is answered with `Conflict(CLIENT_UUID_TAKEN)`. (`load_owned_activity` already exists in this file; if its exact name differs, use the one `PATCH /activities/{id}` uses.)

- [ ] **Step 4: Reminders**

Add `client_uuid` to `ReminderInput` the same way. Change `insert` to take `uuid: String` instead of minting one; `create` computes it:

```rust
    let client_uuid = super::normalize_client_uuid(body.client_uuid.take())?;
    if let Some(uuid) = client_uuid.as_deref() {
        let existing: Option<(i64, i64, i64, Option<String>)> = sqlx::query_as(
            "SELECT r.id, r.object_id, o.user_id, r.deleted_at FROM reminders r \
             JOIN objects o ON o.id = r.object_id WHERE r.client_uuid = $1")
            .bind(uuid).fetch_optional(&state.db).await?;
        match existing {
            Some((id, oid, owner, None)) if owner == user.id && oid == object_id => {
                let row = load_owned(&state, user.id, id).await?;
                return Ok((StatusCode::OK, Json(out(&state, user.id, row).await?)));
            }
            Some(_) => return Err(AppError::Conflict(super::CLIENT_UUID_TAKEN.into())),
            None => {}
        }
    }
    let uuid = client_uuid.unwrap_or_else(|| uuid::Uuid::new_v4().to_string());
    let (id, uuid) = insert(&mut tx, object_id, &body, uuid).await?;
```

The `done` handler's successor insert passes `uuid::Uuid::new_v4().to_string()`. Wrap the INSERT in `insert` in the same unique-violation → `Conflict` handling.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cargo test --locked --test activities && cargo test --locked --test reminders`
Expected: PASS.

- [ ] **Step 6: `docs/openapi.json`**

Add the same `client_uuid` property (copy the text from Task 1) to `ActivityInput` and `ReminderInput`, and a `200` response to `paths./objects/{id}/activities.post` and `paths./objects/{id}/reminders.post`.

- [ ] **Step 7: Verify and commit**

Run: `cargo clippy --all-targets --locked -- -D warnings && cargo test --locked`

```bash
git add -A && git commit -m "feat: activity and reminder creates accept a client_uuid and replay idempotently"
```

---

### Task 3: `client_uuid` on the attachment upload

**Files:**
- Modify: `src/api/attachments.rs` (`upload`)
- Test: `tests/attachments.rs`

- [ ] **Step 1: Write the failing test**

```rust
#[tokio::test]
async fn an_upload_honours_and_replays_on_client_uuid() {
    let app = common::spawn().await;
    app.setup("ben", "correct horse").await;
    let car = app.create_object(&app.client, "Golf", Some("km")).await;
    let base = app.url(&format!("/objects/{}/attachments", car["id"]));
    let make = || form(png(64, 64), "a.png", "image/png").text("client_uuid", "phone-0004-photo");
    let res = app.client.post(&base).multipart(make()).send().await.unwrap();
    assert_eq!(res.status(), 201, "{}", res.text().await.unwrap());
    let first: serde_json::Value = res.json().await.unwrap();
    let res = app.client.post(&base).multipart(make()).send().await.unwrap();
    assert_eq!(res.status(), 200);
    let second: serde_json::Value = res.json().await.unwrap();
    assert_eq!(first["id"], second["id"]);
    let list: Vec<serde_json::Value> = app.client.get(&base).send().await.unwrap().json().await.unwrap();
    assert_eq!(list.len(), 1);
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cargo test --locked --test attachments client_uuid`
Expected: FAIL (`201` twice, two rows).

- [ ] **Step 3: Read the field and check it**

In `upload`'s multipart loop add an arm beside `"client_op_id"`:

```rust
            "client_uuid" => {
                let t = field.text().await.map_err(|e| AppError::BadRequest(e.body_text()))?;
                client_uuid = super::normalize_client_uuid(Some(t))?;
            }
```

with `let mut client_uuid: Option<String> = None;` declared beside `client_op_id`. After the `client_op_id` pre-check (and before hashing the bytes — the replay skips the blob write exactly as the op-id replay does):

```rust
    if let Some(uuid) = client_uuid.as_deref() {
        let existing: Option<(i64, i64, i64, Option<String>)> = sqlx::query_as(
            "SELECT t.id, t.object_id, o.user_id, t.deleted_at FROM attachments t \
             JOIN objects o ON o.id = t.object_id WHERE t.client_uuid = $1")
            .bind(uuid).fetch_optional(&state.db).await?;
        match existing {
            Some((id, oid, owner, None)) if owner == user.id && oid == object_id => {
                return Ok((StatusCode::OK, Json(load_owned(&state, user.id, id).await?)));
            }
            Some(_) => return Err(AppError::Conflict(super::CLIENT_UUID_TAKEN.into())),
            None => {}
        }
    }
```

Replace `let attachment_uuid = uuid::Uuid::new_v4().to_string();` with `let attachment_uuid = client_uuid.unwrap_or_else(|| uuid::Uuid::new_v4().to_string());`. In the unique-violation arm, when `client_op_id` is `None`, purge the orphan file (as the arm already does) and answer `Conflict(CLIENT_UUID_TAKEN)`.

The return type of `upload` is `(StatusCode, Json<AttachmentOut>)`, so a `200` fits without changing it.

- [ ] **Step 4: Run the test to verify it passes**

Run: `cargo test --locked --test attachments`
Expected: PASS.

- [ ] **Step 5: `docs/openapi.json`**

Add `client_uuid` (string, 8–64) to the multipart schema of `paths./objects/{id}/attachments.post.requestBody` and a `200` response.

- [ ] **Step 6: Verify and commit**

```bash
cargo clippy --all-targets --locked -- -D warnings && cargo test --locked
git add -A && git commit -m "feat: attachment upload accepts a client_uuid and replays idempotently"
```

---

### Task 4: `client_uuid` (and `file_uuid`) in every entity response

**Files:**
- Modify: `src/api/objects.rs` (`ObjectRow` + every `SELECT`/`RETURNING` list that feeds it), `src/api/export.rs:481` (the `ObjectRow` stub literal), `src/api/activities.rs` (`ActivityRow` + lists), `src/api/reminders.rs` (`ReminderRow` + lists), `src/api/attachments.rs` (`AttachmentOut` + lists)
- Modify: `docs/openapi.json` (`Object`, `Activity`, `Reminder`, `Attachment` schemas)
- Test: `tests/objects.rs`, `tests/attachments.rs`

**Interfaces:**
- Produces: JSON field `client_uuid: string | null` on every object, activity, reminder and attachment; `file_uuid: string | null` on every attachment.

- [ ] **Step 1: Write the failing tests**

```rust
// tests/objects.rs
#[tokio::test]
async fn every_object_response_carries_its_client_uuid() {
    let app = common::spawn().await;
    app.setup("ben", "correct horse").await;
    let created: serde_json::Value = app.client.post(app.url("/objects"))
        .json(&json!({ "name": "Golf", "type": "car", "client_uuid": "phone-0001-golf" }))
        .send().await.unwrap().json().await.unwrap();
    assert_eq!(created["client_uuid"], "phone-0001-golf");
    let read: serde_json::Value = app.client.get(app.url(&format!("/objects/{}", created["id"]))).send().await.unwrap().json().await.unwrap();
    assert_eq!(read["client_uuid"], "phone-0001-golf");
    let list: Vec<serde_json::Value> = app.client.get(app.url("/objects")).send().await.unwrap().json().await.unwrap();
    assert_eq!(list[0]["client_uuid"], "phone-0001-golf");
    // A row the server minted has one too -- some 36-character v4.
    let other: serde_json::Value = app.client.post(app.url("/objects"))
        .json(&json!({ "name": "Bike", "type": "bike" })).send().await.unwrap().json().await.unwrap();
    assert_eq!(other["client_uuid"].as_str().unwrap().len(), 36);
}

// tests/attachments.rs
#[tokio::test]
async fn an_attachment_response_names_its_own_uuid_and_its_files_uuid() {
    let app = common::spawn().await;
    app.setup("ben", "correct horse").await;
    let car = app.create_object(&app.client, "Golf", Some("km")).await;
    let base = app.url(&format!("/objects/{}/attachments", car["id"]));
    let a: serde_json::Value = app.client.post(&base)
        .multipart(form(png(64, 64), "a.png", "image/png").text("client_uuid", "phone-0004-photo"))
        .send().await.unwrap().json().await.unwrap();
    assert_eq!(a["client_uuid"], "phone-0004-photo");
    let file_uuid = a["file_uuid"].as_str().unwrap().to_string();
    // The same bytes again, as a second attachment: dedup means the same file uuid.
    let b: serde_json::Value = app.client.post(&base)
        .multipart(form(png(64, 64), "b.png", "image/png").text("client_uuid", "phone-0005-photo"))
        .send().await.unwrap().json().await.unwrap();
    assert_eq!(b["file_uuid"], file_uuid);
    let boot: serde_json::Value = app.client.get(app.url("/sync/bootstrap")).send().await.unwrap().json().await.unwrap();
    assert!(boot["files"].as_array().unwrap().iter().any(|f| f["client_uuid"] == file_uuid));
}
```

Also add one assertion each to an existing activity test and reminder test: the create response has a `client_uuid` string of length 36.

- [ ] **Step 2: Run to verify they fail**

Run: `cargo test --locked --test objects client_uuid && cargo test --locked --test attachments uuid`
Expected: FAIL — the field is absent (`null`).

- [ ] **Step 3: Add the column to each row struct and every list that feeds it**

`ObjectRow` gains `pub client_uuid: Option<String>` (nullable in the schema; every real row has one). Then, in `src/api/objects.rs`, `src/api/export.rs`, `src/api/search.rs` (if it selects `ObjectRow`), add `client_uuid` to every column list that is fetched `as ObjectRow` — `grep -n "query_as::<_, ObjectRow>" src/api/*.rs` finds them; each SELECT and each `RETURNING` list gets `, client_uuid` appended. `export.rs:481` builds an `ObjectRow` literal: give it `client_uuid: None`.

Same for `ActivityRow` (`grep -n "query_as::<_, ActivityRow>"`; the RETURNING lists in `create`, the op-id lookups, `list`, `read`), and `ReminderRow` (its SELECT at `reminders.rs:165` and siblings).

`AttachmentOut` gains `pub client_uuid: Option<String>` and `pub file_uuid: Option<String>`; the two SELECTs at `attachments.rs:51` and `:61` add `a.client_uuid, f.client_uuid AS file_uuid`.

sqlx maps by column name, so a missed list fails at test time with "no column named client_uuid", not silently. Run the whole suite, not only the new tests.

- [ ] **Step 4: Run everything**

Run: `cargo test --locked`
Expected: PASS. Any `ColumnNotFound("client_uuid")` names a list you missed.

- [ ] **Step 5: `docs/openapi.json`**

Add to `Object`, `Activity`, `Reminder`, `Attachment`:

```json
"client_uuid": { "type": ["string", "null"], "description": "The row's sync identity: what a sync op names it by, and what `client_uuid` on a create set it to. Null only for a row that predates the sync tables and has not been backfilled, which no shipped migration leaves behind." }
```

and to `Attachment`: `"file_uuid": { "type": ["string", "null"], "description": "The sync identity of the file row this attachment references. Two attachments of identical bytes share one." }`.

- [ ] **Step 6: Verify and commit**

```bash
cargo clippy --all-targets --locked -- -D warnings && cargo test --locked
git add -A && git commit -m "feat: every object, activity, reminder and attachment response carries its client_uuid"
```

---

### Task 5: `entity_id` on every pull row

**Files:**
- Modify: `src/sync/feed.rs` (`ChangeRow`, `pull`)
- Modify: `docs/openapi.json` (`ChangeRow` schema)
- Test: `tests/sync.rs`

**Interfaces:**
- Produces: `ChangeRow.entity_id: Option<i64>` — the server's integer id for `entity_uuid`, null once the row has been hard-purged.

- [ ] **Step 1: Write the failing test**

Append to `tests/sync.rs`, using the file's existing `push_body` helper and its pattern for reading `/sync/pull`:

```rust
#[tokio::test]
async fn every_pulled_change_names_the_servers_id_for_its_row() {
    let app = common::spawn().await;
    app.setup("ben", "correct horse").await;
    let car = app.create_object(&app.client, "Golf", Some("km")).await;
    let activity: serde_json::Value = app.client
        .post(app.url(&format!("/objects/{}/activities", car["id"])))
        .json(&json!({ "date": "2026-09-01", "category": "repair", "title": "Wipers" }))
        .send().await.unwrap().json().await.unwrap();

    let pulled: serde_json::Value = app.client.get(app.url("/sync/pull?since=0")).send().await.unwrap().json().await.unwrap();
    let changes = pulled["changes"].as_array().unwrap();
    let object_create = changes.iter().find(|c| c["entity"] == "object" && c["op"] == "create").unwrap();
    assert_eq!(object_create["entity_id"], car["id"]);
    let activity_create = changes.iter().find(|c| c["entity"] == "activity" && c["op"] == "create").unwrap();
    assert_eq!(activity_create["entity_id"], activity["id"]);
    // A set row names the same id as the create for the same uuid.
    let activity_set = changes.iter().find(|c| c["entity"] == "activity" && c["op"] == "set").unwrap();
    assert_eq!(activity_set["entity_id"], activity["id"]);
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cargo test --locked --test sync servers_id`
Expected: FAIL — `entity_id` is `null`.

- [ ] **Step 3: Resolve the id at feed time**

In `src/sync/feed.rs`:

```rust
#[derive(Serialize, sqlx::FromRow, Debug, Clone)]
pub struct ChangeRow {
    pub seq: i64,
    pub entity: String,
    pub entity_uuid: String,
    pub op: String,
    pub field: Option<String>,
    pub value: Option<String>,
    pub edited_at: String,
    pub device_id: String,
    /// The server's integer id for `entity_uuid`, so a device that first hears of a row here
    /// can relate it to the ids REST responses and file URLs use. Null once the row has been
    /// hard-purged by retention, by which point there is nothing to apply the change to.
    pub entity_id: Option<i64>,
}
```

and the query:

```rust
        "SELECT c.seq, c.entity, c.entity_uuid, c.op, c.field, c.value, c.edited_at, c.device_id, \
         CASE c.entity \
           WHEN 'object'     THEN (SELECT id FROM objects     WHERE client_uuid = c.entity_uuid) \
           WHEN 'activity'   THEN (SELECT id FROM activities  WHERE client_uuid = c.entity_uuid) \
           WHEN 'reminder'   THEN (SELECT id FROM reminders   WHERE client_uuid = c.entity_uuid) \
           WHEN 'attachment' THEN (SELECT id FROM attachments WHERE client_uuid = c.entity_uuid) \
           WHEN 'file'       THEN (SELECT id FROM files       WHERE client_uuid = c.entity_uuid) \
         END AS entity_id \
         FROM changes c WHERE c.user_id = $1 AND c.seq > $2 ORDER BY c.seq LIMIT $3"
```

Every `client_uuid` column has a unique index (`migrations/sqlite/0007_sync.sql`), so each subquery is an index lookup. Keep the existing `derive`s on `ChangeRow` — only add `entity_id`.

- [ ] **Step 4: Run the sync suite**

Run: `cargo test --locked --test sync`
Expected: PASS, including `a_pulled_change_rows_fields_match_the_op_that_produced_it`, which compares fields by name and is unaffected by a new one.

- [ ] **Step 5: `docs/openapi.json`**

Add to `ChangeRow.properties`:

```json
"entity_id": { "type": ["integer", "null"], "format": "int64", "description": "The server's integer id for `entity_uuid`: what REST responses and `/files/{id}` use. Null once retention has hard-purged the row." }
```

- [ ] **Step 6: Verify and commit**

```bash
cargo clippy --all-targets --locked -- -D warnings && cargo test --locked
git add -A && git commit -m "feat: GET /sync/pull names the server id on every change row"
```

---

### Task 6: Log the cover and done-activity cleanups to the change feed

**Files:**
- Modify: `src/sync/record.rs` (`clear_cover_of`, `cascade_activity`, `cascade_object`)
- Modify: call sites: `src/api/attachments.rs::delete`, `src/api/activities.rs::delete`, `src/api/objects.rs::delete`, `src/sync/apply.rs` (`Delete` arm)
- Test: `tests/sync.rs`

**Interfaces:**
- Produces: `clear_cover_of(tx, user_id, attachment_uuid, edited_at)` and `cascade_activity(tx, user_id, activity_uuid, now, edited_at)` — two new parameters each. Both log a `set … null` through `record_update` for every row they change.

- [ ] **Step 1: Write the failing tests**

```rust
#[tokio::test]
async fn deleting_a_cover_attachment_logs_the_cover_being_cleared() {
    let app = common::spawn().await;
    app.setup("ben", "correct horse").await;
    let car = app.create_object(&app.client, "Golf", Some("km")).await;
    let id = car["id"].as_i64().unwrap();
    let a: serde_json::Value = app.client.post(app.url(&format!("/objects/{id}/attachments")))
        .multipart(reqwest::multipart::Form::new().part("file",
            reqwest::multipart::Part::bytes(png()).file_name("a.png").mime_str("image/png").unwrap()))
        .send().await.unwrap().json().await.unwrap();
    // Make it the cover, then delete it.
    app.client.patch(app.url(&format!("/objects/{id}")))
        .json(&json!({ "name": "Golf", "type": "car", "counter_unit": "km", "cover_attachment_id": a["id"] }))
        .send().await.unwrap();
    let before: serde_json::Value = app.client.get(app.url("/sync/pull?since=0")).send().await.unwrap().json().await.unwrap();
    let since = before["next_seq"].as_i64().unwrap();
    let epoch = before["epoch"].as_str().unwrap().to_string();
    app.client.delete(app.url(&format!("/attachments/{}", a["id"]))).send().await.unwrap();

    let after: serde_json::Value = app.client.get(app.url(&format!("/sync/pull?since={since}&epoch={epoch}"))).send().await.unwrap().json().await.unwrap();
    let cleared = after["changes"].as_array().unwrap().iter().find(|c|
        c["entity"] == "object" && c["op"] == "set" && c["field"] == "cover_attachment_id");
    let cleared = cleared.expect("the cover clear is in the feed");
    assert_eq!(cleared["entity_id"], id);
    assert!(cleared["value"].is_null(), "a set to null is stored as SQL NULL");
}

#[tokio::test]
async fn deleting_a_done_activity_logs_the_reminder_being_unlinked() {
    let app = common::spawn().await;
    app.setup("ben", "correct horse").await;
    let car = app.create_object(&app.client, "Golf", Some("km")).await;
    let oid = car["id"].as_i64().unwrap();
    let activity: serde_json::Value = app.client.post(app.url(&format!("/objects/{oid}/activities")))
        .json(&json!({ "date": "2026-09-01", "category": "maintenance", "title": "Oil" }))
        .send().await.unwrap().json().await.unwrap();
    let reminder: serde_json::Value = app.client.post(app.url(&format!("/objects/{oid}/reminders")))
        .json(&json!({ "title": "Oil", "due_date": "2026-09-01" }))
        .send().await.unwrap().json().await.unwrap();
    app.client.post(app.url(&format!("/reminders/{}/done", reminder["id"])))
        .json(&json!({ "activity_id": activity["id"] })).send().await.unwrap();
    let before: serde_json::Value = app.client.get(app.url("/sync/pull?since=0")).send().await.unwrap().json().await.unwrap();
    let since = before["next_seq"].as_i64().unwrap();
    let epoch = before["epoch"].as_str().unwrap().to_string();
    app.client.delete(app.url(&format!("/activities/{}", activity["id"]))).send().await.unwrap();

    let after: serde_json::Value = app.client.get(app.url(&format!("/sync/pull?since={since}&epoch={epoch}"))).send().await.unwrap().json().await.unwrap();
    let unlinked = after["changes"].as_array().unwrap().iter().find(|c|
        c["entity"] == "reminder" && c["op"] == "set" && c["field"] == "done_activity_id");
    assert!(unlinked.is_some(), "the unlink is in the feed");
    assert_eq!(unlinked.unwrap()["entity_id"], reminder["id"]);
}
```

`png()` already exists in `tests/sync.rs` (line ~96).

- [ ] **Step 2: Run to verify they fail**

Run: `cargo test --locked --test sync logs_the`
Expected: FAIL — no such `set` row in the feed.

- [ ] **Step 3: Log in `clear_cover_of`**

```rust
pub(crate) async fn clear_cover_of(
    tx: &mut sqlx::AnyConnection,
    user_id: i64,
    attachment_uuid: &str,
    edited_at: &str,
) -> Result<(), AppError> {
    // Which objects are about to change, before they do: the log needs their uuids.
    let cleared: Vec<Option<String>> = sqlx::query_scalar(
        "SELECT client_uuid FROM objects WHERE deleted_at IS NULL \
         AND cover_attachment_id = (SELECT id FROM attachments WHERE client_uuid = $1)")
        .bind(attachment_uuid).fetch_all(&mut *tx).await?;
    sqlx::query(
        "UPDATE objects SET cover_attachment_id = NULL WHERE deleted_at IS NULL \
         AND cover_attachment_id = (SELECT id FROM attachments WHERE client_uuid = $1)")
        .bind(attachment_uuid).execute(&mut *tx).await?;
    for uuid in cleared.into_iter().flatten() {
        record_update(tx, user_id, Entity::Object, &uuid,
            &[("cover_attachment_id", serde_json::Value::Null)], edited_at).await?;
    }
    Ok(())
}
```

Do the same inside `cascade_activity` for its cover clear (select the object uuids first) and its `done_activity_id` unlink (select the reminder uuids first), logging `("done_activity_id", Null)` on `Entity::Reminder`. `cascade_activity` gains `user_id: i64` and `edited_at: &str` parameters. `record_update` stamps with the server's `DEVICE_ID` ("rest") unconditionally, which is what the existing REST edits do too.

- [ ] **Step 4: Update the call sites**

- `src/api/attachments.rs::delete`: `record::clear_cover_of(&mut tx, user.id, &attachment_uuid, &edited_at).await?;`
- `src/api/activities.rs::delete`: `record::cascade_activity(&mut tx, user.id, &activity_uuid, &now, &edited_at).await?;`
- `src/sync/apply.rs` `Delete` arm: pass `user_id` and `&op.edited_at` (the canonicalised one the handler already wrote back into `op`) to both.
- `cascade_object` calls `cascade_activity` for each activity it tombstones — thread the two parameters through it and its callers (`objects.rs::delete`, `apply.rs`).

- [ ] **Step 5: Run the whole suite**

Run: `cargo test --locked`
Expected: PASS. `create_and_delete_ops_never_store_a_field_or_value` and the cascade tests must still pass — the new rows are `set` rows, separate from the `delete` row.

- [ ] **Step 6: Commit**

```bash
cargo clippy --all-targets --locked -- -D warnings
git add -A && git commit -m "fix: cover clears and done-activity unlinks are logged to the change feed"
```

---

### Task 7: README note, spec status, merge

**Files:**
- Modify: `README.md` (the API / sync section that already documents the outbox and PAT)
- Modify: `docs/superpowers/specs/2026-09-08-offline-sync-design.md` (status line)

- [ ] **Step 1: README**

Under the section that documents the sync endpoints, add one paragraph: creates accept `client_uuid` and replay idempotently; every entity response carries `client_uuid`; every pull row carries `entity_id`. Point at `docs/openapi.json`.

- [ ] **Step 2: Spec status**

Change the offline-sync spec's status line to say the client is a native Android app designed in `logb_mobile/docs/superpowers/specs/2026-09-14-logb-android-design.md`, and that the "prerequisite for phase 4" was resolved this way: creates arrive through REST carrying the row's final offline values and its `client_uuid`, so no queued `set` predates the create.

- [ ] **Step 3: Final verification and commit**

```bash
cargo clippy --all-targets --locked -- -D warnings && cargo test --locked
cd frontend && npm run check && npm test && cd ..
git add -A && git commit -m "docs: sync additions for the Android client"
```

- [ ] **Step 4: Merge**

Use superpowers:finishing-a-development-branch. Then tag a server release per the project's `chore: release x.y.z` convention (the Android app's phase 1 plan pins the minimum server version to it).
