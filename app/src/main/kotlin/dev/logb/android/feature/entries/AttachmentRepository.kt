package dev.logb.android.feature.entries

import dev.logb.android.core.blobs.BlobStore
import dev.logb.android.core.blobs.Thumbnails
import dev.logb.android.core.db.LogbDatabase
import dev.logb.android.core.db.entity.AttachmentEntity
import dev.logb.android.core.db.entity.BlobEntity
import dev.logb.android.core.db.entity.FileEntity
import dev.logb.android.core.sync.Clock
import dev.logb.android.core.sync.LocalWriter
import java.io.InputStream
import java.util.UUID

/**
 * Photos and documents into the mirror: the bytes go to the blob store, the metadata to
 * `files` and `attachments`, and one `attachment` create op into the queue -- the upload
 * happens when the server can be reached. A file is `photo` when its MIME is an image, as the
 * server decides it.
 */
class AttachmentRepository(private val db: LogbDatabase, private val store: BlobStore, private val writer: LocalWriter, private val onWrite: () -> Unit = {}) {
    suspend fun import(source: InputStream, name: String, mime: String, objectUuid: String, activityUuid: String?, caption: String = ""): String {
        val written = store.writeOriginal(source)
        val isImage = mime.startsWith("image/")
        if (isImage && !store.hasThumb(written.sha256)) store.makeThumb(written.sha256)
        val meta = if (isImage) Thumbnails.meta(store.original(written.sha256)) else null
        val now = Clock.nowIso()
        val attachmentUuid = UUID.randomUUID().toString()
        writer.create("attachment", attachmentUuid) {
            // Identical bytes attached before reuse the file row, exactly as the server dedups.
            val file = db.fileDao().liveBySha(written.sha256) ?: FileEntity(
                uuid = UUID.randomUUID().toString(), serverId = null, sha256 = written.sha256, originalName = name, mime = mime, size = written.size,
                width = meta?.width?.toLong(), height = meta?.height?.toLong(), takenAt = meta?.takenAt, createdAt = now, deletedAt = null,
            ).also { db.fileDao().upsert(it) }
            db.blobDao().upsert(BlobEntity(written.sha256, written.size, mime, originalPresent = true, thumbPresent = store.hasThumb(written.sha256), lastAccessAt = now))
            db.attachmentDao().upsert(AttachmentEntity(attachmentUuid, null, objectUuid, activityUuid, file.uuid, if (isImage) "photo" else "document", caption.trim(), now, null))
        }
        onWrite()
        return attachmentUuid
    }

    /** The EXIF capture date of a stored photo, as a `YYYY-MM-DD` the entry form can offer. */
    suspend fun takenDate(attachmentUuid: String): String? {
        val a = db.attachmentDao().get(attachmentUuid) ?: return null
        return db.fileDao().get(a.fileUuid)?.takenAt?.take(10)
    }

    suspend fun delete(attachmentUuid: String) {
        writer.delete("attachment", attachmentUuid)
        onWrite()
    }

    suspend fun setCaption(attachmentUuid: String, caption: String) {
        val a = db.attachmentDao().get(attachmentUuid) ?: return
        if (a.caption == caption.trim()) return
        writer.set("attachment", attachmentUuid, mapOf("caption" to caption.trim())) { db.attachmentDao().upsert(a.copy(caption = caption.trim())) }
        onWrite()
    }

    suspend fun setCover(objectUuid: String, attachmentUuid: String?) {
        val o = db.objectDao().get(objectUuid) ?: return
        if (o.coverAttachmentUuid == attachmentUuid) return
        writer.set("object", objectUuid, mapOf("cover_attachment_id" to attachmentUuid)) { db.objectDao().upsert(o.copy(coverAttachmentUuid = attachmentUuid, updatedAt = Clock.nowIso())) }
        onWrite()
    }
}
