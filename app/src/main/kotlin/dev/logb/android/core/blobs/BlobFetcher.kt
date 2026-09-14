package dev.logb.android.core.blobs

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import okio.FileSystem
import okio.Path.Companion.toOkioPath

/** What a screen asks Coil for: a file by its sha, as its thumbnail or its original. */
data class BlobImage(val sha: String, val thumb: Boolean = true)

/** Serves a `BlobImage` from the blob store, fetching it first when the phone does not have it yet. */
class BlobFetcher(private val data: BlobImage, private val store: BlobStore, private val downloader: () -> BlobDownloader?) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val local = if (data.thumb) store.thumb(data.sha) else store.original(data.sha)
        val file = if (local.isFile) local else downloader()?.let { if (data.thumb) it.ensureThumb(data.sha) else it.ensureOriginal(data.sha) } ?: return null
        return SourceFetchResult(ImageSource(file.toOkioPath(), FileSystem.SYSTEM), mimeType = null, dataSource = if (local.isFile) DataSource.DISK else DataSource.NETWORK)
    }

    class Factory(private val store: BlobStore, private val downloader: () -> BlobDownloader?) : Fetcher.Factory<BlobImage> {
        override fun create(data: BlobImage, options: Options, imageLoader: ImageLoader): Fetcher = BlobFetcher(data, store, downloader)
    }

    object Key : Keyer<BlobImage> {
        override fun key(data: BlobImage, options: Options): String = "blob:${data.sha}:${if (data.thumb) "t" else "o"}"
    }
}
