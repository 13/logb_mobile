package dev.logb.android.feature.update

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
class UpdateRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val payload = ByteArray(300_000) { (it % 251).toByte() }
    private val payloadSha = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
    private val releaseKey = setOf("ef46d303232d7394d83b42f117e2c81f1ca5fe7399a22d0ac0d7dda19a60b8f3")

    private fun httpServing(body: ByteArray? = payload): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            if (body == null) throw IOException("no route to host")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("ok")
                .body(body.toResponseBody("application/vnd.android.package-archive".toMediaType())).build()
        }.build()

    private class FakeGitHub(private val answer: () -> GitHubRelease) : GitHubApi {
        override suspend fun latestRelease(repo: String): GitHubRelease = answer()
    }

    private class FakeSignatures(private val installed: Set<String>, private val archive: Set<String>?) : ApkSignatures {
        override fun installed(): Set<String> = installed
        override fun ofArchive(file: File): Set<String>? = archive
    }

    private fun release(tag: String, digest: String? = "sha256:$payloadSha", withApk: Boolean = true, size: Long = payload.size.toLong()) =
        GitHubRelease(
            tagName = tag,
            htmlUrl = "https://github.com/13/logb_mobile/releases/tag/$tag",
            assets = if (withApk) listOf(GitHubAsset("LogB-${tag.removePrefix("v")}.apk", size, "https://example.invalid/a.apk", digest)) else emptyList(),
        )

    private fun repository(api: GitHubApi, http: OkHttpClient = httpServing(), archiveSigners: Set<String>? = releaseKey) =
        UpdateRepository(api, http, context, FakeSignatures(releaseKey, archiveSigners))

    private fun cacheFiles(): List<File> = File(context.cacheDir, "updates").listFiles()?.toList().orEmpty()

    @Test fun `a newer tag is offered with its asset and its page`() = runTest {
        val available = repository(FakeGitHub { release("v0.8.0") }).check("0.7.1") as UpdateCheck.Available
        assertEquals(AppVersion(0, 8, 0), available.version)
        assertEquals("https://github.com/13/logb_mobile/releases/tag/v0.8.0", available.releaseUrl)
    }

    @Test fun `the same or an older tag is up to date`() = runTest {
        assertEquals(UpdateCheck.UpToDate, repository(FakeGitHub { release("v0.7.1") }).check("0.7.1"))
        assertEquals(UpdateCheck.UpToDate, repository(FakeGitHub { release("v0.7.0") }).check("0.7.1"))
    }

    @Test fun `an unreadable tag is a failure, not an all clear`() = runTest {
        val failed = repository(FakeGitHub { release("nightly") }).check("0.7.1") as UpdateCheck.Failed
        assertEquals(UpdateFailure.UNREADABLE_VERSION, failed.failure)
        assertEquals("https://github.com/13/logb_mobile/releases/tag/nightly", failed.releaseUrl)
    }

    @Test fun `a release with no apk fails but still offers its page`() = runTest {
        val failed = repository(FakeGitHub { release("v0.8.0", withApk = false) }).check("0.7.1") as UpdateCheck.Failed
        assertEquals(UpdateFailure.NO_APK, failed.failure)
        assertEquals("https://github.com/13/logb_mobile/releases/tag/v0.8.0", failed.releaseUrl)
    }

    @Test fun `an unreachable GitHub is a network failure`() = runTest {
        assertEquals(UpdateFailure.NETWORK, (repository(FakeGitHub { throw IOException("offline") }).check("0.7.1") as UpdateCheck.Failed).failure)
    }

    @Test fun `a download matching checksum and key is kept and marked verified`() = runTest {
        val repo = repository(FakeGitHub { release("v0.8.0") })
        val progress = repo.download(repo.check("0.7.1") as UpdateCheck.Available).toList()
        val done = progress.last() as DownloadProgress.Done
        assertTrue(done.digestVerified)
        assertArrayEquals(payload, done.file.readBytes())
        assertTrue(progress.count { it is DownloadProgress.Running } > 1)
    }

    @Test fun `a download that does not match the checksum fails and is deleted`() = runTest {
        val repo = repository(FakeGitHub { release("v0.8.0", digest = "sha256:" + "0".repeat(64)) })
        assertEquals(DownloadProgress.Failed(UpdateFailure.DIGEST_MISMATCH), repo.download(repo.check("0.7.1") as UpdateCheck.Available).toList().last())
        assertTrue(cacheFiles().isEmpty())
    }

    /** The spec's certificate check: an APK signed with another key never reaches the installer. */
    @Test fun `a download signed with another key fails and is deleted`() = runTest {
        val repo = repository(FakeGitHub { release("v0.8.0") }, archiveSigners = setOf("0".repeat(64)))
        assertEquals(DownloadProgress.Failed(UpdateFailure.SIGNATURE_MISMATCH), repo.download(repo.check("0.7.1") as UpdateCheck.Available).toList().last())
        assertTrue(cacheFiles().isEmpty())
    }

    @Test fun `a download whose signers cannot be read fails as a signature mismatch`() = runTest {
        val repo = repository(FakeGitHub { release("v0.8.0") }, archiveSigners = null)
        assertEquals(DownloadProgress.Failed(UpdateFailure.SIGNATURE_MISMATCH), repo.download(repo.check("0.7.1") as UpdateCheck.Available).toList().last())
    }

    @Test fun `a release without a checksum downloads unverified`() = runTest {
        val repo = repository(FakeGitHub { release("v0.8.0", digest = null) })
        assertFalse((repo.download(repo.check("0.7.1") as UpdateCheck.Available).toList().last() as DownloadProgress.Done).digestVerified)
    }

    @Test fun `a download shorter than declared fails and is deleted`() = runTest {
        val repo = repository(FakeGitHub { release("v0.8.0", digest = null, size = payload.size + 4096L) })
        assertEquals(DownloadProgress.Failed(UpdateFailure.NETWORK), repo.download(repo.check("0.7.1") as UpdateCheck.Available).toList().last())
        assertTrue(cacheFiles().isEmpty())
    }

    @Test fun `an asset with no declared size is not treated as truncated`() = runTest {
        val repo = repository(FakeGitHub { release("v0.8.0", digest = null, size = 0) })
        assertArrayEquals(payload, (repo.download(repo.check("0.7.1") as UpdateCheck.Available).toList().last() as DownloadProgress.Done).file.readBytes())
    }

    @Test fun `a release asset name cannot steer the download outside the cache directory`() = runTest {
        val escapee = GitHubRelease(
            tagName = "v0.8.0",
            htmlUrl = "https://github.com/13/logb_mobile/releases/tag/v0.8.0",
            assets = listOf(GitHubAsset("../escape.apk", payload.size.toLong(), "https://example.invalid/a.apk", "sha256:$payloadSha")),
        )
        val repo = repository(FakeGitHub { escapee })
        val done = repo.download(repo.check("0.7.1") as UpdateCheck.Available).toList().last() as DownloadProgress.Done
        val target = File(context.cacheDir, "updates/update.apk")
        assertEquals(target.canonicalPath, done.file.canonicalPath)
        assertFalse(File(context.cacheDir, "escape.apk").exists())
    }

    @Test fun `a download that cannot reach the server fails`() = runTest {
        val repo = repository(FakeGitHub { release("v0.8.0") }, httpServing(body = null))
        assertEquals(DownloadProgress.Failed(UpdateFailure.NETWORK), repo.download(repo.check("0.7.1") as UpdateCheck.Available).toList().last())
    }

    @Test fun `checking clears whatever the last attempt left behind`() = runTest {
        File(context.cacheDir, "updates").apply { mkdirs() }.resolve("stale.apk").writeBytes(byteArrayOf(1, 2, 3))
        repository(FakeGitHub { release("v0.7.1") }).check("0.7.1")
        assertTrue(cacheFiles().isEmpty())
    }
}
