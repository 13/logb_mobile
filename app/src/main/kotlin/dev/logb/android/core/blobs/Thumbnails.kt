package dev.logb.android.core.blobs

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** What a photo says about itself, as the server records it in `files`. */
data class ImageMeta(val width: Int?, val height: Int?, val takenAt: String?)

/** Pure functions over an image file: the 400 px thumbnail the server also makes, and the EXIF the entry form offers. */
object Thumbnails {
    const val MAX_PX = 400
    private const val QUALITY = 80
    private val exifFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

    /** A JPEG of the image scaled to `maxPx` on its longest side and rotated upright, or null when the file is not an image. */
    fun render(src: File, maxPx: Int = MAX_PX): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sample = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxPx) }
        val decoded = BitmapFactory.decodeFile(src.path, sample) ?: return null
        val scale = maxPx.toFloat() / maxOf(decoded.width, decoded.height)
        val matrix = Matrix()
        if (scale < 1f) matrix.postScale(scale, scale)
        when (orientation(src)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
        }
        val out = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        val bytes = ByteArrayOutputStream().use { out.compress(Bitmap.CompressFormat.JPEG, QUALITY, it); it.toByteArray() }
        if (out !== decoded) out.recycle()
        decoded.recycle()
        return bytes
    }

    /** Width and height as displayed (after orientation) and the EXIF capture time as RFC 3339 UTC, when present. */
    fun meta(src: File): ImageMeta {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.path, bounds)
        if (bounds.outWidth <= 0) return ImageMeta(null, null, null)
        val rotated = orientation(src) in listOf(ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE)
        val (w, h) = if (rotated) bounds.outHeight to bounds.outWidth else bounds.outWidth to bounds.outHeight
        val taken = runCatching { ExifInterface(src).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) }.getOrNull()
            ?.let { runCatching { LocalDateTime.parse(it, exifFormat).atOffset(ZoneOffset.UTC).toInstant().toString() }.getOrNull() }
        return ImageMeta(w, h, taken)
    }

    private fun orientation(src: File): Int =
        runCatching { ExifInterface(src).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun sampleSize(w: Int, h: Int, maxPx: Int): Int {
        var sample = 1
        while (maxOf(w, h) / (sample * 2) >= maxPx) sample *= 2
        return sample
    }
}
