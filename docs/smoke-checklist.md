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
