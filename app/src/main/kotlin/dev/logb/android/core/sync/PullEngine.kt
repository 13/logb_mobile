package dev.logb.android.core.sync

import dev.logb.android.core.db.LogbDatabase
import dev.logb.android.core.network.GoneException
import dev.logb.android.core.network.LogbApi

/**
 * Brings the mirror up to the server's change feed: bootstrap when there is nothing to resume
 * from (or the server says the cursor is stale), then page through `pull` until `complete`.
 */
class PullEngine(
    private val db: LogbDatabase,
    private val api: LogbApi,
    private val deviceId: String,
    private val pageSize: Int = 1000,
) {
    private val bootstrap = Bootstrap(db)
    private val applier = ChangeApplier(db)

    suspend fun run() {
        val state = db.syncStateDao().get()
        if (state == null || state.bootstrapNeeded) bootstrap.apply(api.bootstrap(), deviceId)
        var attempts = 0
        while (true) {
            val s = db.syncStateDao().get() ?: return
            val page = try {
                api.pull(s.cursorSeq, s.epoch, pageSize)
            } catch (e: GoneException) {
                // The cursor predates what the server kept, or the database changed underneath
                // it: a fresh snapshot is the only honest catch-up. Once; a second 410 is a bug.
                if (attempts++ > 0) throw e
                bootstrap.apply(api.bootstrap(), deviceId)
                continue
            }
            applier.apply(page.changes)
            // Re-read, not `s`: the applier may have asked for a bootstrap while this page was applied
            // (a REST create it can only placeholder), and writing the stale copy back would forget that.
            val applied = db.syncStateDao().get() ?: return
            db.syncStateDao().upsert(
                applied.copy(
                    cursorSeq = page.nextSeq,
                    epoch = page.epoch,
                    clockOffsetMs = Clock.offsetMs(page.serverTime),
                    lastSyncedAt = Clock.nowIso(),
                ),
            )
            if (page.complete) break
        }
        // Heal placeholders now rather than on the next run: a row made in the browser should show
        // its values on the pull that announced it, not fifteen minutes later.
        if (db.syncStateDao().get()?.bootstrapNeeded == true) bootstrap.apply(api.bootstrap(), deviceId)
    }
}
