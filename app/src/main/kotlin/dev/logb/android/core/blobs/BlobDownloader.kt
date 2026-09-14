package dev.logb.android.core.blobs

import dev.logb.android.core.db.LogbDatabase
import dev.logb.android.core.db.entity.BlobEntity
import dev.logb.android.core.db.entity.FileEntity
import dev.logb.android.core.network.LogbApi
import dev.logb.android.core.sync.Clock
import dev.logb.android.core.sync.ConnectivityMonitor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException

/**
 * Keeps the blob store in step with the mirror after a pull: every referenced file gets its
 * thumbnail on any connection; originals follow on unmetered connections (or any, when the
 * person turned that rule off) until the budget binds, newest first; then the least recently
 * used originals are evicted until under budget. A tap on a photo asks for its original now.
 */
class BlobDownloader(
    private val db: LogbDatabase,
    private val api: LogbApi,
    private val store: BlobStore,
    private val connectivity: ConnectivityMonitor,
    private val settings: suspend () -> BlobSettings,
) {
    private val onDemand = Mutex()

    suspend fun runAfterPull() {
        val files = db.fileDao().referencedFromServer()
        val prefs = settings()
        var usage = store.usageBytes()
        val originalsAllowed = !prefs.originalsUnmeteredOnly || connectivity.isUnmetered
        for (f in files) {
            if (f.sha256.isBlank() || f.serverId == null) continue
            if (f.mime.startsWith("image/") && !store.hasThumb(f.sha256)) runCatching { fetchThumb(f) }
            if (originalsAllowed && !store.hasOriginal(f.sha256) && usage + f.size <= prefs.budgetBytes) {
                if (runCatching { fetchOriginal(f) }.getOrDefault(false)) usage += f.size
            }
        }
        val pendingUploads = db.opDao().pending().filter { it.kind == "create" && it.entity == "attachment" }
            .mapNotNull { op -> db.attachmentDao().get(op.entityUuid)?.let { db.fileDao().get(it.fileUuid)?.sha256 } }.toSet()
        val evicted = store.evictOriginalsOver(prefs.budgetBytes, keep = pendingUploads, lru = db.blobDao().originalsLeastRecentlyUsed())
        evicted.forEach { db.blobDao().setOriginalPresent(it, false) }
    }

    /** The original for a photo the person opened: fetched on any connection, once, and remembered as used. */
    suspend fun ensureOriginal(sha: String): File? = onDemand.withLock {
        val file = store.original(sha)
        if (file.isFile) { db.blobDao().touch(sha, Clock.nowIso()); return file }
        val row = db.fileDao().bySha(sha) ?: return null
        if (row.serverId == null) return null
        return if (runCatching { fetchOriginal(row) }.getOrDefault(false)) store.original(sha) else null
    }

    suspend fun ensureThumb(sha: String): File? {
        val file = store.thumb(sha)
        if (file.isFile) return file
        val row = db.fileDao().bySha(sha) ?: return null
        if (row.serverId == null) return null
        return if (runCatching { fetchThumb(row) }.isSuccess) store.thumb(sha) else null
    }

    private suspend fun fetchThumb(f: FileEntity) {
        val bytes = api.downloadThumb(f.serverId!!).use { it.bytes() }
        store.writeThumb(f.sha256, bytes)
        upsertBlob(f, thumb = true)
    }

    private suspend fun fetchOriginal(f: FileEntity): Boolean {
        val ok = api.downloadOriginal(f.serverId!!).use { store.writeOriginalFromServer(f.sha256, it.byteStream()) }
        if (!ok) throw IOException("bytes for ${f.sha256} did not match")
        upsertBlob(f, original = true)
        return true
    }

    private suspend fun upsertBlob(f: FileEntity, thumb: Boolean? = null, original: Boolean? = null) {
        val existing = db.blobDao().get(f.sha256)
        db.blobDao().upsert(
            BlobEntity(
                f.sha256, f.size, f.mime,
                originalPresent = original ?: existing?.originalPresent ?: store.hasOriginal(f.sha256),
                thumbPresent = thumb ?: existing?.thumbPresent ?: store.hasThumb(f.sha256),
                lastAccessAt = Clock.nowIso(),
            ),
        )
    }
}
