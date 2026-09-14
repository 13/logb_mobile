package dev.logb.android.core.sync

/** What the sync line under the Objects title says. One place, one sentence. */
sealed interface SyncStatus {
    /** Never synced yet, or signed out. */
    data object None : SyncStatus
    /** Synced; `pending` is what could not be sent yet (a reference not on the server) and waits for the next run. */
    data class Idle(val lastSyncedAt: String, val pending: Int = 0) : SyncStatus
    data object Syncing : SyncStatus
    /** No connection; `pending` writes wait (always 0 until phase 2). */
    data class Offline(val pending: Int) : SyncStatus
    /** The server could not be reached or answered with an error other than 401. */
    data class Failed(val message: String) : SyncStatus
    data object SignedOut : SyncStatus
}

enum class SyncReason { Foreground, Manual, AfterWrite, Periodic, Connectivity }
