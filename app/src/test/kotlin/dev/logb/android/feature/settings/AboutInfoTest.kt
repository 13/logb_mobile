package dev.logb.android.feature.settings

import dev.logb.android.core.server.Capabilities
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AboutInfoTest {
    private val info = AboutInfo(
        versionName = "0.7.0", versionCode = 700, buildDate = "2026-09-14", commit = "1dc9ad7", debug = false, releaseKey = true,
        serverUrl = "https://logb.muh/", serverVersion = "0.7.1", capabilities = Capabilities.of("0.7.1"),
    )

    @Test fun `copy text lists every detail on its own line`() {
        assertEquals(
            """
            LogB 0.7.0 (700)
            Built 2026-09-14 from 1dc9ad7, release, release key
            Server https://logb.muh 0.7.1
            Tags: no, own types: no, QR sign-in: no
            """.trimIndent(),
            info.copyText(),
        )
    }

    @Test fun `copy text without a server says so`() {
        val text = info.copy(serverUrl = null, serverVersion = null, capabilities = Capabilities.NONE, debug = true, releaseKey = false).copyText()
        assertEquals("LogB 0.7.0 (700)\nBuilt 2026-09-14 from 1dc9ad7, debug, debug key\nNo server", text)
    }

    /** [AboutInfo.releaseKey] is null only until the signing check answers; copy text must never
     * guess "debug key" for that window, which would be a wrong claim about a release build. */
    @Test fun `copy text says the signing key is unknown rather than guessing`() {
        val text = info.copy(releaseKey = null).copyText()
        assertEquals("LogB 0.7.0 (700)\nBuilt 2026-09-14 from 1dc9ad7, release, signing key unknown\nServer https://logb.muh 0.7.1\nTags: no, own types: no, QR sign-in: no", text)
    }

    @Test fun `commit link only for a real hash`() {
        assertEquals("https://github.com/13/logb_mobile/commit/1dc9ad7", info.commitUrl)
        assertNull(info.copy(commit = "unknown").commitUrl)
    }
}
