package dev.logb.android.core.design

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppSignatureTest {
    @Test fun `hex digest is lower case sha-256`() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", AppSignature.sha256Hex("abc".toByteArray()))
    }

    @Test fun `release when any signer is the release key`() {
        assertTrue(AppSignature.isRelease(listOf(AppSignature.RELEASE_SHA256)))
        assertTrue(AppSignature.isRelease(listOf("00", AppSignature.RELEASE_SHA256.uppercase())))
        assertFalse(AppSignature.isRelease(listOf("00")))
        assertFalse(AppSignature.isRelease(emptyList()))
    }
}
