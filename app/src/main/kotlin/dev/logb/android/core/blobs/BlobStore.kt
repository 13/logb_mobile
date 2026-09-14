package dev.logb.android.core.blobs

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The bytes behind `files`, laid out as the server lays them out: originals under
 * `blobs/<sha[0..2]>/<sha>`, thumbnails under `thumbs/<sha>.jpg`. Content-addressed, so the
 * same photo attached twice is stored once, and the sha is computed from the bytes as they are
 * written -- never trusted from anywhere else.
 */
@Singleton
class BlobStore @Inject constructor(@ApplicationContext context: Context) {
    private val root: File = context.filesDir

    data class Written(val sha256: String, val size: Long)

    fun original(sha: String): File = File(File(File(root, "blobs"), sha.take(2)), sha)
    fun thumb(sha: String): File = File(File(root, "thumbs"), "$sha.jpg")
    fun hasOriginal(sha: String): Boolean = original(sha).isFile
    fun hasThumb(sha: String): Boolean = thumb(sha).isFile

    /** Copies `source` into a temp file while hashing, then moves it into place. Idempotent for identical bytes. */
    suspend fun writeOriginal(source: InputStream): Written = withContext(Dispatchers.IO) {
        val tmp = File.createTempFile("blob", ".part", root)
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        try {
            tmp.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                source.use { input ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        digest.update(buffer, 0, n)
                        out.write(buffer, 0, n)
                        size += n
                    }
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            val target = original(sha)
            target.parentFile?.mkdirs()
            if (target.isFile) tmp.delete() else check(tmp.renameTo(target)) { "could not move blob into place" }
            Written(sha, size)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /** Writes bytes fetched from the server as the original for `sha`; refuses bytes whose hash is not `sha`. */
    suspend fun writeOriginalFromServer(sha: String, source: InputStream): Boolean {
        val written = writeOriginal(source)
        if (written.sha256 != sha) { original(written.sha256).delete(); return false }
        return true
    }

    suspend fun writeThumb(sha: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val target = thumb(sha)
        target.parentFile?.mkdirs()
        val tmp = File(target.path + ".part")
        tmp.writeBytes(bytes)
        check(tmp.renameTo(target))
    }

    /** Renders the thumbnail from the stored original; false when the original is missing or not an image. */
    suspend fun makeThumb(sha: String): Boolean = withContext(Dispatchers.IO) {
        val src = original(sha)
        if (!src.isFile) return@withContext false
        val bytes = Thumbnails.render(src) ?: return@withContext false
        writeThumb(sha, bytes)
        true
    }

    /** Bytes held by originals. Thumbnails are small and never evicted, so they are not budgeted. */
    fun usageBytes(): Long = File(root, "blobs").walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /**
     * Removes originals, least recently used first, until usage is under `budget`. `keep`
     * names originals that must stay (an upload still pending). `lru` is every candidate sha,
     * least recently used first; an original not in it is left alone.
     */
    suspend fun evictOriginalsOver(budget: Long, keep: Set<String>, lru: List<String>): List<String> = withContext(Dispatchers.IO) {
        val evicted = mutableListOf<String>()
        var usage = usageBytes()
        for (sha in lru) {
            if (usage <= budget) break
            if (sha in keep) continue
            val f = original(sha)
            if (f.isFile) { usage -= f.length(); f.delete(); evicted += sha }
        }
        evicted
    }

    fun deleteAll(sha: String) {
        original(sha).delete()
        thumb(sha).delete()
    }
}
