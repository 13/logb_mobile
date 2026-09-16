# Smoke checklist (phase 1)

On a real phone against a phase-0 server. `adb reverse tcp:8090 tcp:8090` and `http://localhost:8090` when the server runs on the development machine.

- [ ] First run shows the server screen; a wrong address says so inline; a right one moves to sign-in.
- [ ] A wrong password shows the server's message; a right one shows "Getting your logbook…" and then Objects.
- [ ] Objects lists root objects with counter, total spent, last entry and due badges; the due banner counts across objects.
- [ ] Airplane mode on. Kill the app. Reopen: everything above is still there; the sync line says Offline.
- [ ] Open an object: stats, breadcrumb (nested object), Timeline with year groups and category chips, Documents grid, Reminders with due and done, Info with Contents.
- [ ] Airplane mode off. Edit an object in the browser. Pull to refresh on Objects: the edit appears.
- [ ] Search finds an object by name and an entry by title, the entry naming its object.
- [ ] Settings: Appearance switches theme and language live; Sync shows a cursor and Sync now works; About shows the version.
- [ ] Sign out keeping data, sign in again: no bootstrap screen, the mirror is still there.
- [ ] Sign out removing data, sign in again: the bootstrap screen runs once.

## Phase 2 (offline writes)

- [ ] Airplane mode on. New object from the FAB with two templates ticked and a current reading: it appears on Objects with its reading; its reminders are on its Reminders tab.
- [ ] Log an entry from the card's plus with a counter lower than the current one: the warning shows; save; the timeline and the card's figures update.
- [ ] Log a reading from the long-press menu; edit an entry by tapping it; delete an entry.
- [ ] Mark a repeating reminder done ("just mark it done"): the successor appears with the right date; snooze another; the due banner drops it.
- [ ] Airplane mode off: the sync line goes from "n changes waiting" to "Synced just now" on its own; the browser shows every change with the phone's values.
- [ ] Edit the same object's name in the browser and on the phone while offline: the later edit wins on both after the sync.
- [ ] Settings › Sync lists nothing failed; if the server refuses something, it lists it with Retry and Discard.

## Phase 3 (photos and documents)

- [ ] Airplane mode on. Edit an entry, add a gallery photo: it appears in the form and the timeline strip with the cloud-off badge; add a PDF on the Documents tab.
- [ ] Airplane mode off: the badges go; the browser shows both files; open the photo in the viewer (pinch to zoom), set it as cover; the object card shows the cover.
- [ ] Share a photo from the gallery app into LogB: pick an object; the entry form opens with it attached and the capture date offered; save.
- [ ] Take a photo with the camera button on a new entry; drop it with the cross before saving; take another and save.
- [ ] Delete a file from the viewer: it disappears on the phone and in the browser.
- [ ] Settings › Sync › Storage: the used figure is plausible; set the budget to 1 GB; Free up space empties full-size files and the viewer re-downloads one on tap.

## Phase 4 (statistics and insights)

- [ ] Airplane mode on. Objects › chart icon opens Statistics: total, spend over time, by object (expand a house to see its garage; tap a name to open it), by type, by category. Pick a year: twelve month bars, zeros included. Turn on *Include purchase prices*: the total and the years grow, *Purchase price* leads the categories.
- [ ] Open a car › Info: total cost of ownership with "≈ … a year since …", spend per month, per year, per category, cost per km, fuel logged, consumption, usage "≈ … a month", usage per month with dashes for unmeasured months, consumption per fill once two fuel entries carry a counter and a quantity.
- [ ] Open a house › Info: *Include contents* folds the children's spend in and out.
- [ ] Reminders: a counter-target reminder shows "≈ <date> at recent usage" once the car has two readings at least 14 days apart; the due list includes it when that date is within 30 days.
- [ ] Airplane mode off. Compare every figure with the browser's Statistics page and the object's Cost block on the same server.
- [ ] Create a reminder in the browser; pull to refresh on the phone: it appears with its title and date on that very sync (a browser create is a bare `create` in the feed; the phone heals it with a bootstrap in the same run).

## Phase 5 (polish)

- [ ] Settings › Notifications: turn the daily summary on (Android 13+ asks for permission); set the time to the next minute; with a reminder due within seven days the notification "Golf: Tyre pressure in 2 days" arrives, tapping it opens the due list; with nothing due, nothing arrives.
- [ ] Settings › Account: *Lock with biometrics or screen lock* is greyed out without a screen lock; with one set, turn it on, force-stop and reopen: the prompt shows, cancelling leaves the *Locked* screen, *Unlock* prompts again, the credential opens the logbook; a camera round trip does not lock.
- [ ] Long-press the launcher icon: *Due*, *Search* and *New object* open the right screens.
- [ ] Settings › About shows the version and the build commit.
- [ ] Install the release APK over nothing (different signature from debug), sign in, open Statistics, an object's timeline with thumbnails, take a photo: R8 broke nothing.

## Phase 6 (parity with the web client)

- [ ] Objects: type "light" in the search field: *Main light* shows with "in Garage"; clear it; sort by *Highest cost*: the Golf leads; the Golf card says "≈ 122 km a month".
- [ ] Due list: *Snooze* on a service reminder hides it for a week; *Log reading* on a reading reminder opens the reading form.
- [ ] Reminders tab of a car: "Last reading X on date" and *Log reading*; without a reading reminder, *Remind me to log the reading* opens the form on the reading kind; a due repeating reminder offers *Skip this one* in its menu and lands one interval later.
- [ ] Reading form: type ten times the current counter: "Far more than usual since …" appears; a lower value still says lower.
- [ ] Airplane mode on, log an entry: the timeline shows *Waiting to send*; airplane mode off: the chip goes after the sync.
- [ ] Search: an archived object carries *archived*; Documents › viewer of the cover photo offers *Clear cover*.
- [ ] Settings › Account: *Server x.y.z* under the address; *Change password* to the same password reports success; *Sign out everywhere* signs the phone out and keeps the logbook.
- [ ] Object › Info › *Export this object*: the share sheet offers the zip; offline it says so.


## Release 0.7.0 (capabilities, About, archived)

- [ ] Settings › About: logo, *Version 0.7.0 (700)*, *Built* with the commit's date, *Commit* opens GitHub, *Release build, release key* on a tagged APK; server address, version and *Tags: no* against 0.7.1; *Copy details* copies the text.
- [ ] Settings › Account against a server older than 0.8.0: *Tags and own types need LogB 0.8.0 or newer on the server.*
- [ ] Objects: the archive icon in the top bar shows archived objects under the title *Archived*; system back returns to the active list.


## Release 0.7.1 (in-app update)

- [ ] A release build older than the newest GitHub release: after opening the app, Settings shows *<installed> · <newest> available* on About.
- [ ] Settings › About › Updates: *Download* shows progress, *Install* hands over to Android's confirmation, the app restarts as the new version.
- [ ] *Check once a day* off: no request to api.github.com at start (check with the network inspector or a proxy).
- [ ] A debug build shows no Updates section.

## Release 0.8.0 (tags and own types)

- [ ] Object and entry forms: type "winter, lease," — two chips; a 33-character tag is refused with the web's sentence; suggestions offer tags used elsewhere.
- [ ] Objects list: tap a tag chip — only carriers at every depth remain, "Tag: …" with clear; the timeline filters by an entry tag the same way.
- [ ] Settings › Types: add "Boat" (tool icon, repair + fuel, h); an object of type Boat shows the icon, the unit and only Boat's categories; delete is refused while an object uses it.
- [ ] The web shows the same tags, colours and the Boat type after a sync, and edits made there arrive on the phone.
- [ ] Against a server older than 0.8.0: no tag input, no Types row; existing data unaffected.

## Release 0.9.0 (the web's settings pages)

- [ ] Settings › API access: create a token with the password — shown once, Copy works; revoke another; this phone's own token has no Revoke; a wrong password says so.
- [ ] Settings › Data: export everything saves a zip where the picker says; import warns about duplicates, then shows the counts and the new rows arrive after the sync; an archive over the server limit says it is too large.
- [ ] Settings › Notifications: a webhook address and format save; "Send a test notification" reports the webhook result; an invalid address is refused before saving.
- [ ] Offline, each of the three pages says it needs a connection.

## Release 0.10.0 (fast lists and release checks)

- [ ] With a few hundred objects, the objects list and the due list open and update without a stall.
- [ ] CI `Device tests` and `Release APK on a device` are green.
- [ ] `tools/release-smoke.sh <emulator serial>` passes locally on the release APK.
- [ ] An update from 0.9.0 installs in-app.
- [ ] Settings › About shows 0.10.0.

## Release 0.11.0 (reminders outside the app)

- [ ] Airplane mode on: a service reminder's notification actions still work — *Done* completes it in the app, *Snooze 7 days* hides it for a week — and the write shows as a pending change in Settings › Sync until the radio comes back.
- [ ] A reading reminder's notification offers only *Log reading*, never *Done*; tapping it opens that object's reading form.
- [ ] Several reminders due at once collapse into one grouped notification: a child per reminder (up to five, in digest order) plus a summary; tapping a child's body opens that object's Reminders tab.
- [ ] Place the LogB widget on the home screen: it lists up to five due or soon-due reminders (object, title, when); tapping a row opens that reminder's object.
- [ ] The widget's check button marks a service reminder done and the row disappears; a reading row has no check button.
- [ ] Settings › Account: turn on *Lock with biometrics or screen lock* — the placed widget immediately drops to a bare count, with no object or reminder names visible.
- [ ] The widget refreshes on its own right after a sync, a local write, the daily digest, midnight, toggling the lock, and signing out (no manual refresh needed); signing out shows "Open LogB to sign in" instead of stale rows.
- [ ] With `animator_duration_scale`/`transition_animation_scale`/`window_animation_scale` at 10x: leaving an entry form slides the two screens past each other with no double exposure of the old screen, and tapping a bottom-bar tab from a screen pushed deep in a stack switches instantly, without a slide.

## Release 0.13.0 (updater hardening and clean-up)

- [ ] With a user CA installed on the device, an update still installs from GitHub (the four GitHub hosts now trust only the system certificate store).
- [ ] A release whose named APK (`LogB-<version>.apk`) is missing shows "no APK" on the Updates row, with the release page link still offered.
- [ ] Leave the app during an install and return to it: the Updates row offers *Open the install confirmation*.
- [ ] Settings › About: the links line up with the section text, and the build date is hidden when unknown.
- [ ] Tag filters still work from both the Objects list and a timeline.
- [ ] Editing a type that was deleted elsewhere says it no longer exists.

## Release 0.14.0 (QR sign-in)

- [ ] Scanning a QR code from the web's Account page signs the phone in, with no address or password typed.
- [ ] Opening a `logb://pair` link from outside the app (a share, a chat message) always asks first, naming the host, whether signed in or out.
- [ ] Reopening a pairing link from Recents (the app switcher) shows no dialog — it was already answered or dropped when it first arrived.
- [ ] Already signed in: a pairing link asks *Switch account?*, naming the current account and both servers; confirming signs out of the first and into the second.
- [ ] A used or expired code shows an error dialog with an *OK* button, not *Cancel*.
- [ ] With the app lock on: a pairing link arriving while locked shows no dialog over the lock screen; unlocking shows the confirmation exactly once.
- [ ] Against a server without the pairing feature: redeeming answers "This server does not support QR sign-in."
- [ ] Settings › About shows *QR sign-in: yes* against a server with the feature, *no* against one without it.
- [ ] Denying the camera permission while scanning shows a message instead of a blank screen.
- [ ] Signing out makes the phone's token disappear from the web's API access page (server with self-revoke).
