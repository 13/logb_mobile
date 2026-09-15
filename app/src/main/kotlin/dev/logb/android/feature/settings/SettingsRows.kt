package dev.logb.android.feature.settings

import dev.logb.android.core.auth.Session
import dev.logb.android.core.prefs.Appearance
import dev.logb.android.core.prefs.ThemeMode
import dev.logb.android.core.sync.SyncStatus

enum class SettingsPage { Appearance, Account, Sync, Notifications, Types, Data, ApiAccess, About }

/** A hub row: where it goes and what it currently says, so the hub reads as a status summary. */
data class SettingsRow(val page: SettingsPage, val value: String?)

/** Pure, as in the web app's spec: given the state, the rows and their values. */
fun settingsRows(session: Session, appearance: Appearance, sync: SyncStatus, language: String, version: String, failed: Int = 0, notifications: String? = null, showTypes: Boolean = false): List<SettingsRow> {
    val theme = when (appearance.theme) { ThemeMode.System -> "system"; ThemeMode.Light -> "light"; ThemeMode.Dark -> "dark" }
    val syncValue = if (failed > 0) "failed:$failed" else when (sync) {
        SyncStatus.None -> null
        is SyncStatus.Idle -> "synced"
        SyncStatus.Syncing -> "syncing"
        is SyncStatus.Offline -> if (sync.pending > 0) "pending:${sync.pending}" else "offline"
        is SyncStatus.Failed -> "failed"
        SyncStatus.SignedOut -> null
    }
    return buildList {
        add(SettingsRow(SettingsPage.Appearance, "$theme · ${language.uppercase()}"))
        add(SettingsRow(SettingsPage.Account, (session as? Session.SignedIn)?.user?.username))
        add(SettingsRow(SettingsPage.Sync, syncValue))
        add(SettingsRow(SettingsPage.Notifications, notifications))
        if (showTypes) add(SettingsRow(SettingsPage.Types, null))
        add(SettingsRow(SettingsPage.Data, null))
        add(SettingsRow(SettingsPage.ApiAccess, null))
        add(SettingsRow(SettingsPage.About, version))
    }
}
