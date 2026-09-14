package dev.logb.android.core.sync

/**
 * Field-level last-write-wins, exactly as `sync::apply::wins` on the server: the greater
 * canonical `edited_at` wins, and on a tie the greater `device_id` string wins. An unstamped
 * field loses to anything, which is what lets a fresh row take its first values.
 */
object Lww {
    fun wins(incomingEditedAt: String, incomingDevice: String, currentEditedAt: String?, currentDevice: String?): Boolean {
        if (currentEditedAt == null) return true
        val incoming = Clock.canonical(incomingEditedAt) ?: return false
        val current = Clock.canonical(currentEditedAt) ?: return true
        return when {
            incoming > current -> true
            incoming < current -> false
            else -> incomingDevice > (currentDevice ?: "")
        }
    }
}
