# Database encryption at rest: a measured go/no-go

Spike branch: `spike/sqlcipher` (throwaway, not merged). Worktrees used: `logb-mobile-spike` (from
`release-0.13.0` @ `a2d5596`, SQLCipher applied) and `logb-mobile-baseline` (same commit,
unmodified) — built and measured side by side so every number below is a same-commit comparison.

## TL;DR

**Recommendation: no-go for 0.13.0.** The threat SQLCipher closes is real but narrow (root/forensic
access to an unlocked-since-boot device, without Keystore/TEE extraction) and sits behind two
layers LogB already has (FBE, app lock). Measured against that narrow benefit: +31% APK size,
roughly 2.5-3.5x slower bootstrap ingest of a 2,000-entry mirror, an untested and only
partially-verified migration path for existing installs, and new test friction from the Android
Keystore. None of these are individually disqualifying, but together they are not worth paying
for a gap this narrow in this release. See "What would change this recommendation" for the
conditions under which the answer flips.

## What SQLCipher would add on top of what the phone already does

LogB already has two protections in place without SQLCipher:

1. **Android's file-based encryption (FBE).** Every app's `/data/user/0/<pkg>` directory is
   encrypted at rest by the OS with a key tied to the device lock. LogB's Room database
   (`logb-<hash>-<user>.db`) already benefits from this on every device it ships to (minSdk 28).
2. **LogB's own app lock** (biometric/PIN gate on app foreground, `LockPrefs`/biometric prompt).

What SQLCipher (page-level AES-256 encryption inside SQLite, keyed by a passphrase this spike
wraps with an Android Keystore AES key and stores in DataStore) adds on top of both:

- **Phone locked, attacker has root or a forensic image, device has been unlocked at least once
  since boot (the common case — FBE's "unlocked" / credential-encrypted state, which is what a
  live root shell or a live-imaged running device gives you):** FBE's per-app CE key is already
  unwrapped in the kernel keyring for a running, unlocked-since-boot device, so a root shell (or
  an image taken while the device is in this state, e.g. via `adb backup`-style extraction or a
  compromised app with root) can read the plaintext Room database today, app lock or not — the
  app lock is a UI gate, not a storage gate; root bypasses it trivially. **This is exactly the
  gap SQLCipher closes**: with SQLCipher, that same root shell reads ciphertext; the passphrase
  is wrapped by an Android Keystore key, and pulling it back out needs either the Keystore's
  hardware-backed key (StrongBox/TEE — not extractable by a root shell alone on most devices) or
  the app's own decrypt call (which still needs the app unlocked/running).
- **Phone locked, attacker has a full forensic image taken while the phone has never been
  unlocked since boot (before-first-unlock, BFU):** FBE alone already leaves the CE-encrypted
  files unreadable (the key is only in memory after first unlock) — SQLCipher adds nothing here;
  the OS already wins.
- **Phone unlocked in the attacker's hands (a snatched, unlocked phone; a live root/debug session
  on an unlocked device), or the app already inside a live app-lock session:** neither FBE nor
  SQLCipher helps — both keys are unwrapped in memory for a running unlocked session, and the
  app's own UI already shows the data. App lock is the only line of defence left here, and it
  already exists.

So the real, narrow threat SQLCipher buys protection against is: **root or an imaging tool that
can read app-private files after the device has been unlocded at least once since boot, without
also being able to extract the Keystore key or drive the app's own UI.** That is a real scenario
(a forensic tool with root/system access but not full Keystore/TEE extraction, or a
malicious app with root that dumps files but can't call into another app's Keystore-backed
crypto), not a strawman, but it sits behind two other layers (FBE, app lock) that already close
the more common versions of "attacker has the phone."

## Measured costs

Machine: shared dev host, other agents building concurrently (noted per the task's rules);
emulator `r8verify34`, headless (`-memory 2048 -no-window -no-audio -no-snapshot-save`), API 34.

- **APK size delta (release, real signing key, same commit `a2d5596`):** baseline
  `LogB-release.apk` = 10,963,134 bytes (10.46 MiB); spike = 14,376,073 bytes (13.71 MiB).
  **Delta: +3,412,939 bytes (+3.25 MiB, +31%).** All of it is `libsqlcipher.so` for four ABIs
  (arm64-v8a, armeabi-v7a, x86, x86_64) bundled by `net.zetetic:sqlcipher-android:4.19.0`; an
  App Bundle split by ABI would cut a single device's download to roughly a quarter of that delta
  (~800 KB), but the flat APK numbers above are what `ls -l` shows.
- **Cold start, `am start -W` × 10, median:** baseline 1,026.5 ms (range 836-1,811 ms); spike
  835.5 ms (range 796-952 ms). **Spike measured faster, but do not read this as "encryption speeds
  up cold start."** Neither build touches `DatabaseProvider` before sign-in (cold start here is an
  empty, signed-out launch), so this is pure app-launch noise — the spike happened to run second
  on an emulator with warmer OS/dexopt caches, and the host was shared with other agents' Gradle
  builds the whole session (two emulator crashes during this spike, both with "hanging thread"
  errors consistent with host CPU/memory contention — see Concerns). The honest read: cold start
  is unaffected by this change (SQLCipher's native library and Keystore call only happen once the
  user is signed in and `DatabaseProvider.open()` runs), and the ~200 ms spread between the two
  medians is within this host's run-to-run noise, not a real signal either way.
- **Bootstrap (pull) of a 2,000-entry seeded mirror, on-device, real `DatabaseProvider`:**
  baseline (unencrypted) 802, 858, 766, 763, 764 ms (settles ~765 ms after the first run's JIT
  warmup); spike (encrypted) 2,853, 2,025, 1,995 ms (settles ~2,000 ms). **Roughly 2.5-3.7x
  slower encrypted**, all of it the AES-256 page encryption cost on ~2,000 inserts plus the
  Keystore-backed key unwrap on open. In absolute terms this is still low single-digit seconds for
  a 2,000-entry account (most real accounts are smaller), but it is a real, consistent, repeatable
  cost, not noise — the two builds' bootstrap numbers never overlapped across 8 runs.
- **Time to `ObjectsModel.allCards()` first emission after that bootstrap:** baseline 264, 257,
  254, 257, 295 ms (median 257 ms); spike 270, 273, 268 ms (median 270 ms). **No meaningful
  difference (~5%, within noise)** — this is a read over data already local (one object, from
  cache-warm Room queries), so per-page decryption cost on a handful of reads is negligible; the
  entire encryption cost shows up at bootstrap/write time, not at query/read time.
- **In-place migration of an existing unencrypted mirror via `sqlcipher_export`:** attempted, not
  cleanly obtained — see Migration risk below. SQLCipher's own documented recipe (`ATTACH DATABASE
  ... KEY "x'<hex>'"`, `SELECT sqlcipher_export('encrypted')`, `DETACH`) against a real,
  previously-bootstrapped 782 KB / 2,000-activity plaintext mirror pulled off the baseline build
  consistently failed with SQLite error `"invalid target database encrypted"` on the export step,
  across every Android SQL entry point tried (`rawExecSQL`+`rawQuery`, `execSQL`+`compileStatement`)
  — the `ATTACH` call itself reports success but `PRAGMA database_list` on the same connection
  afterwards shows only `main`, meaning the attach is not actually visible to later statements on
  that session. This smells like a session/connection-pool subtlety specific to
  `net.zetetic:sqlcipher-android`'s Android bindings (its `net.zetetic.database.sqlcipher.
  SQLiteDatabase`, not the plain `sqlcipher` CLI, where this exact recipe is documented and
  presumably works) rather than a fundamental blocker, but this spike's time budget ran out before
  finding the right API incantation. **This is itself a real data point**: the migration path is
  not a drop-in call from application code the way the plan's framing implied; a real
  implementation needs its own small spike to get the on-device API right before it can be trusted
  with a user's only copy of their data.
- Server-side seeding of 2,000 entries over REST (for context, not a spike/baseline delta — same
  unmodified `logb` binary both times): ~11.5 s wall clock for 2,001 sequential HTTP requests
  (curl round-trip dominates; the server itself is not the bottleneck).

## Migration risk for existing installs

No existing LogB install ships an encrypted mirror today, so every upgrade to a SQLCipher build
is a **migrate-in-place or lose-and-rebuild** decision, not a no-op:

- SQLCipher ships `sqlcipher_export()` (attach a new encrypted DB, `SELECT sqlcipher_export()`,
  detach, swap files) specifically for plaintext→encrypted conversion; it re-writes the whole
  file, so its cost scales with mirror size, not with the number of pending sync operations. It
  is well-trodden (SQLCipher's own documented migration path), but it must run on first launch
  after upgrade, before Room opens the database — this is a new, untested code path with a real
  failure mode: the app must handle a mid-export crash or power loss (a partially exported file)
  and fall back to a clean re-bootstrap from the server rather than corrupt or lose the mirror.
  LogB already has a "mirror rebuilds from the server" recovery path in `PullEngine`/bootstrap,
  which reduces the blast radius of a failed export (worst case: re-pull, not data loss), but
  that fallback itself was not exercised by this spike.
- **This spike attempted but did not cleanly complete a real `sqlcipher_export` run** (see
  Measured costs above) — every attempt against a real, previously-seeded 782 KB / 2,000-activity
  plaintext mirror failed at the export step with `"invalid target database"`, despite following
  SQLCipher's own documented recipe. Given the write-path overhead this spike did measure
  (encrypting ~2,000 inserts costs an extra ~1.2-2.1 s versus plaintext), a full-file export/rewrite
  of a similarly sized database is plausibly in the same low-single-digit-second range for a
  typical account — but that is an estimate extrapolated from the bootstrap numbers, not a
  direct measurement, and should not be treated as validated. **Getting this path working and
  timed on-device is a prerequisite for a "go" decision**, not optional follow-up: a migration
  that silently fails or produces an unreadable file on a real user's only local copy is a data-loss
  bug, not a performance question.
- Every device's Keystore-wrapped passphrase is device-local and non-exportable by design: a
  factory reset, a "keystore was cleared" event (rare but real — matches the exact recovery gap
  `KeystoreTokenStore`/`SqlCipherKeyStore` already accept for the auth token today), or restoring
  app data to a different device all strand the encrypted mirror unreadable. Today a lost/garbled
  Room file just re-bootstraps from the server silently; with SQLCipher, the same event needs an
  explicit "we can't read your local data, re-downloading" path — undesigned today.

## Engineering cost of leaving the bundled driver

The 0.13.0 plan's premise was that SQLCipher only exposes a `SupportSQLiteOpenHelper.Factory`
(the pre-driver Room API), forcing Room off `BundledSQLiteDriver` and its `useWriterConnection`/
`immediateTransaction` transaction path back onto `room-ktx`'s `withTransaction`. **That premise
is now out of date for the current stable release**: `net.zetetic:sqlcipher-android:4.19.0`
(current stable, checked against Maven Central during this spike) ships both
`net.zetetic.database.sqlcipher.SupportOpenHelperFactory` (the classic factory, what the plan
described, what this spike used per its instructions) **and**
`net.zetetic.database.sqlcipher.driver.SQLCipherDriver implements androidx.sqlite.SQLiteDriver`
— a real driver, installable the same way as `BundledSQLiteDriver` via `.setDriver(...)`. This
spike followed the brief's explicit instructions (factory + revert to `withTransaction`) so the
numbers above are measured against that path, but **a driver-based integration may avoid the
`useWriterConnection`→`withTransaction` reversion entirely** — worth a short follow-up spike of
its own if the decision is close, since it changes the "leaving the bundled driver" framing from
"give up the driver API" to "swap which driver."

What this spike actually had to change, following the brief's factory-based instructions:

- `DatabaseProvider.open()`: `.setDriver(BundledSQLiteDriver())` → `.openHelperFactory(
  SupportOpenHelperFactory(keyStore.passphrase()))`; lost `.setQueryCoroutineContext(...)` (a
  driver-only builder method) in the process — queries now run on the caller's dispatcher, same
  as pre-driver Room, not a measured regression here but a small behavioural change worth a
  glance in review.
- `Transactions.inTransaction`: `useWriterConnection { it.immediateTransaction { block() } }` →
  `withTransaction(block)`. One call site, one file, no callers changed (same signature).
- New `SqlCipherKeyStore` (32 random bytes, Keystore-AES-GCM-wrapped, DataStore-persisted) —
  same construction as the existing `KeystoreTokenStore`/`KeystoreCipher`, ~80 lines, no surprises.
- **Unplanned cost #1, found only by running on a real device**: unlike the legacy
  `net.zetetic:android-database-sqlcipher` artifact (whose `SQLiteDatabase.loadLibs(context)` was
  the well-known setup call), the current `net.zetetic:sqlcipher-android:4.19.0` artifact's
  classes contain **no call to `System.loadLibrary` anywhere** — confirmed by disassembling every
  class in the AAR. The app must call `System.loadLibrary("sqlcipher")` itself before first use, or
  every open fails with `UnsatisfiedLinkError: No implementation found for ... nativeOpen`. This
  is a one-line fix once found, but it is undocumented in the obvious place (the class that needs
  it has no such call and no comment pointing at one) and this spike only found it by actually
  running the app on-device — a unit-test-only verification pass would never have caught it, since
  Robolectric's tests never open a real `DatabaseProvider` database (see below).
- **Unplanned cost #2, found only by actually running the suite**: `ActiveAccount.refresh()` opens
  the database as a side effect of resolving `.api`, `.db`, *or* `.httpClient` (they share one
  cache keyed by account) — so any code path that merely wants the API client now also opens the
  SQLCipher-backed database and asks the Android Keystore for the passphrase. Robolectric (the
  unit-test JVM) has no `AndroidKeyStore` security provider, so this throws
  `java.security.KeyStoreException: AndroidKeyStore not found` wherever a real `ActiveAccount`
  reaches a signed-in `.api`/`.db`/`.httpClient` under a unit test — it surfaced as **3 failing
  tests in `TokensViewModelTest`** (of 496 total; see below), the only test class that both
  constructs a real `DatabaseProvider` and exercises a signed-in `.api` call. Before this spike,
  `DatabaseProvider` was "safe" to construct for real in a unit test (bundled driver, no
  Android-specific dependency); after, it is not, and every such test needs either a fake
  `SqlCipherKeyStore`/`DatabaseProvider` or a Robolectric `AndroidKeyStore` shadow that does not
  exist today. This is a real, if narrow, new category of test friction the plan did not
  anticipate.

### Unit suite / contract test results (in the spike worktree, same commit as baseline)

- `./gradlew :app:testDebugUnitTest -PlogbBin=<logb>`: **496 tests, 3 failed** — all three in
  `TokensViewModelTest`, all root-caused to the Keystore-under-Robolectric gap above (confirmed:
  the baseline worktree's identical test class passes cleanly; the failure is caused by this
  spike's change, not a pre-existing flake).
- Contract test (`SyncContractTest`, the one that talks to a real `logb` binary): **all 5
  sub-tests passed**, unaffected — it opens its mirror through `TestDatabase.inMemory()` (a plain
  Robolectric in-memory Room database, not `DatabaseProvider`), which this spike does not touch,
  so it never exercises SQLCipher or the Keystore. That also means the contract test gives no
  signal at all on SQLCipher's correctness or performance — it is validating unrelated sync logic
  both before and after this change.
- `:app:compileDebugKotlin`, `:app:compileDebugAndroidTestKotlin`, `:app:assembleRelease`: all
  green with no other source changes needed beyond the three files above.

## Recommendation

**No-go for 0.13.0.** Weighing what was actually measured:

- The threat SQLCipher closes — root or forensic access to app-private files on a device that has
  been unlocked at least once since boot, by an attacker who cannot also extract the Keystore key
  or drive the app's own UI — is real but narrow, and sits behind two layers LogB already ships
  (Android FBE, LogB's app lock). It does not protect against the more common "phone was unlocked
  when it was taken" or "device was imaged before first unlock" scenarios, where the OS already
  wins or already loses regardless of SQLCipher.
- The measured costs are consistent and repeatable, not noise: +31% release APK size (all four
  ABIs' `libsqlcipher.so`), and a 2.5-3.7x slower on-device bootstrap for a 2,000-entry mirror
  (extra ~1.2-2.1 s in absolute terms — real, but not egregious for a one-time first-sync cost).
  Steady-state read performance (the `allCards()` first emission) is unaffected.
- The migration story for every existing install is the deciding factor: this spike could not get
  `sqlcipher_export` to work cleanly against a real 2,000-entry mirror inside its time budget, and
  a migration path that is not proven end-to-end is not something to ship against live user data.
  This alone would block a "go" today even if every other number looked better.
- The engineering cost of leaving the bundled driver, as the plan framed it, turned out smaller in
  code (three files, ~100 lines) than in test friction (Android Keystore has no Robolectric
  provider, so `DatabaseProvider` — previously safe to construct for real in any unit test — now
  needs a fake wherever a signed-in `ActiveAccount` is touched) and than in on-device surprises
  (the missing `System.loadLibrary` call, found only by running on a device). None of these are
  hard blockers on their own, but they add up to "more spike needed before this is a sound plan,"
  not "ready to build."

None of this rules SQLCipher out forever — it rules out shipping it in 0.13.0 on the numbers and
open questions this spike produced.

### What would change this recommendation

- If LogB's threat model explicitly includes a well-resourced forensic adversary (the kind that
  targets device owners under legal or state pressure, not opportunistic theft), the narrow gap
  SQLCipher closes stops being narrow and the size/complexity cost is worth paying regardless of
  the numbers above.
- If the driver-based `SQLCipherDriver` integration (see above) turns out to be a drop-in
  replacement for `BundledSQLiteDriver` with no `withTransaction` reversion needed, the
  engineering-cost side of this decision gets meaningfully cheaper and is worth re-measuring on
  its own — this alone might be worth a short follow-up spike regardless of the encryption
  decision, since it would change the framing from "give up the driver API" to "swap which driver."
- If Play Store delivery (App Bundle, per-ABI splits) is confirmed to cut the real per-device
  download delta to ~800 KB rather than the flat +3.25 MiB measured here, the size objection
  weakens substantially.
- If a follow-up spike gets `sqlcipher_export` working cleanly and timed against a realistically
  sized mirror (this one included), and the number comes back reasonable (single-digit seconds,
  with a tested crash/power-loss fallback to re-bootstrap), the migration-risk objection above is
  answered and the decision reduces to the size/threat trade-off alone.

## Concerns

- The shared dev host was under real load for this entire spike: two full emulator crashes
  (`qemu-system-x86_64` "detected a hanging thread" errors, consistent with host CPU/memory
  contention from other agents' Gradle builds running concurrently, per this task's own warning)
  required relaunching and re-unlocking the AVD mid-measurement. The bootstrap and first-emission
  numbers are internally consistent (baseline and spike numbers never overlapped across repeated
  runs) and are trusted; the cold-start numbers are explicitly flagged above as noise-dominated
  and should not be read as a real signal in either direction.
- The `sqlcipher_export` migration path is unresolved, not merely unmeasured — see above. A "go"
  decision should not proceed without this being nailed down first.
- This spike followed the brief's explicit instruction to use `SupportOpenHelperFactory` +
  `withTransaction`; it did not build or measure the `SQLCipherDriver`-based alternative found
  during the spike (see "Engineering cost" above), so the "leaving the bundled driver" cost may be
  overstated if that path works as well as it looks on paper.
