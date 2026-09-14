package dev.logb.android.feature.entries

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dev.logb.android.core.blobs.Thumbnails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** A file the person picked, copied out of its content URI at once so the form owns it until it is saved or dropped. */
data class PickedFile(val file: File, val name: String, val mime: String, val takenAt: String?) {
    val isImage: Boolean get() = mime.startsWith("image/")
    fun discard() { file.delete() }

    companion object {
        /** Copies the URI's bytes into the app's cache; null when the URI cannot be read. */
        suspend fun from(context: Context, uri: Uri): PickedFile? = withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val mime = resolver.getType(uri) ?: "application/octet-stream"
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: uri.lastPathSegment ?: "file"
            val dir = File(context.cacheDir, "picked").apply { mkdirs() }
            val target = File(dir, UUID.randomUUID().toString())
            runCatching { resolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } } ?: return@withContext null }
                .getOrElse { return@withContext null }
            val taken = if (mime.startsWith("image/")) Thumbnails.meta(target).takenAt else null
            PickedFile(target, name, mime, taken)
        }
    }
}
