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
