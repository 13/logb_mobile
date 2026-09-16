package dev.logb.android.feature.update

import dev.logb.android.core.network.LogbJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Decodes a recorded answer from api.github.com with the app's own Json. */
class GitHubReleaseTest {
    private fun decode(json: String) = LogbJson.decodeFromString(GitHubRelease.serializer(), json)
    private val release = decode(checkNotNull(javaClass.getResource("/fixtures/github_release.json")).readText())

    @Test fun `reads the tag, the page and the asset named for the release`() {
        assertEquals("v0.6.0", release.tagName)
        assertEquals("https://github.com/13/logb_mobile/releases/tag/v0.6.0", release.htmlUrl)
        val apk = checkNotNull(release.apkFor(AppVersion(0, 6, 0)))
        assertEquals("LogB-0.6.0.apk", apk.name)
        assertEquals(9_509_668L, apk.size)
        assertEquals("https://github.com/13/logb_mobile/releases/download/v0.6.0/LogB-0.6.0.apk", apk.downloadUrl)
    }

    @Test fun `reads the sha-256 out of the digest field`() {
        assertEquals(
            "234fd871209e8a0fd7ef717477e2a49a3b7512f42a0d8e30d82cfed11aff2ac8",
            release.apkFor(AppVersion(0, 6, 0))!!.sha256,
        )
    }

    @Test fun `a missing or foreign digest yields no checksum`() {
        assertNull(GitHubAsset(name = "a.apk", digest = null).sha256)
        assertNull(GitHubAsset(name = "a.apk", digest = "md5:abc").sha256)
    }

    @Test fun `a release with no apk asset has none`() {
        assertNull(decode("""{"tag_name":"v9.9.9","html_url":"https://example.invalid","assets":[]}""").apkFor(AppVersion(9, 9, 9)))
        assertNull(decode("""{"tag_name":"v9.9.9","assets":[{"name":"notes.txt","size":1}]}""").apkFor(AppVersion(9, 9, 9)))
    }

    @Test fun `the asset named for the version is chosen over a debug apk`() {
        val release = GitHubRelease(
            tagName = "v0.8.0",
            assets = listOf(
                GitHubAsset(name = "debug.apk", downloadUrl = "https://example.invalid/debug.apk"),
                GitHubAsset(name = "LogB-0.8.0.apk", downloadUrl = "https://example.invalid/LogB-0.8.0.apk"),
            ),
        )
        val apk = checkNotNull(release.apkFor(AppVersion(0, 8, 0)))
        assertEquals("LogB-0.8.0.apk", apk.name)
    }

    @Test fun `an asset not named for the release is not chosen`() {
        val release = GitHubRelease(
            tagName = "v0.8.0",
            assets = listOf(GitHubAsset(name = "other.apk", downloadUrl = "https://example.invalid/other.apk")),
        )
        assertNull(release.apkFor(AppVersion(0, 8, 0)))
    }

    @Test fun `the asset name match ignores case`() {
        val release = GitHubRelease(
            tagName = "v0.8.0",
            assets = listOf(GitHubAsset(name = "LOGB-0.8.0.APK", downloadUrl = "https://example.invalid/a.apk")),
        )
        val apk = checkNotNull(release.apkFor(AppVersion(0, 8, 0)))
        assertEquals("LOGB-0.8.0.APK", apk.name)
    }
}
