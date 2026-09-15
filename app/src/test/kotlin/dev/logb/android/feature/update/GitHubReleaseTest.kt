package dev.logb.android.feature.update

import dev.logb.android.core.network.LogbJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Decodes a recorded answer from api.github.com with the app's own Json. */
class GitHubReleaseTest {
    private fun decode(json: String) = LogbJson.decodeFromString(GitHubRelease.serializer(), json)
    private val release = decode(checkNotNull(javaClass.getResource("/fixtures/github_release.json")).readText())

    @Test fun `reads the tag, the page and the one APK asset`() {
        assertEquals("v0.6.0", release.tagName)
        assertEquals("https://github.com/13/logb_mobile/releases/tag/v0.6.0", release.htmlUrl)
        val apk = checkNotNull(release.apk)
        assertEquals("LogB-0.6.0.apk", apk.name)
        assertEquals(9_509_668L, apk.size)
        assertEquals("https://github.com/13/logb_mobile/releases/download/v0.6.0/LogB-0.6.0.apk", apk.downloadUrl)
    }

    @Test fun `reads the sha-256 out of the digest field`() {
        assertEquals("234fd871209e8a0fd7ef717477e2a49a3b7512f42a0d8e30d82cfed11aff2ac8", release.apk!!.sha256)
    }

    @Test fun `a missing or foreign digest yields no checksum`() {
        assertNull(GitHubAsset(name = "a.apk", digest = null).sha256)
        assertNull(GitHubAsset(name = "a.apk", digest = "md5:abc").sha256)
    }

    @Test fun `a release with no apk asset has none`() {
        assertNull(decode("""{"tag_name":"v9.9.9","html_url":"https://example.invalid","assets":[]}""").apk)
        assertNull(decode("""{"tag_name":"v9.9.9","assets":[{"name":"notes.txt","size":1}]}""").apk)
    }
}
