package dev.logb.android.core.network.dto

import dev.logb.android.core.network.LogbJson
import org.junit.Test
import kotlin.test.assertEquals

/** `HealthInfo.features` is how the server announces feature-gated support like QR sign-in. */
class AuthTest {
    @Test
    fun `HealthInfo features default to empty when the server omits the field`() {
        val info = LogbJson.decodeFromString(HealthInfo.serializer(), """{"status":"ok","version":"0.11.0"}""")
        assertEquals(emptyList(), info.features)
    }

    @Test
    fun `HealthInfo reads the features list when the server sends it`() {
        val info = LogbJson.decodeFromString(HealthInfo.serializer(), """{"status":"ok","version":"0.11.0","features":["pairing"]}""")
        assertEquals(listOf("pairing"), info.features)
    }
}
