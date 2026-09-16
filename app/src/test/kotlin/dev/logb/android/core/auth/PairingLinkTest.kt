package dev.logb.android.core.auth

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PairingLinkTest {
    @Test fun `parses a pairing uri`() =
        assertEquals(
            PairingLink("https://logb.example/", "abc_-9"),
            PairingLinks.parse("logb://pair?server=https%3A%2F%2Flogb.example%2F&code=abc_-9"),
        )

    @Test fun `normalises the server to one trailing slash`() =
        assertEquals(
            "https://logb.example/x/",
            PairingLinks.parse("logb://pair?server=https%3A%2F%2Flogb.example%2Fx&code=c")!!.serverUrl,
        )

    @Test fun `http only for localhost and private lan addresses`() {
        assertEquals(
            "http://192.168.1.5:8080/",
            PairingLinks.parse("logb://pair?server=http%3A%2F%2F192.168.1.5%3A8080&code=c")!!.serverUrl,
        )
        assertEquals(
            "http://localhost:8090/",
            PairingLinks.parse("logb://pair?server=http%3A%2F%2Flocalhost%3A8090&code=c")!!.serverUrl,
        )
        assertNull(PairingLinks.parse("logb://pair?server=http%3A%2F%2Fexample.org&code=c"))
    }

    @Test fun `anything else is not a pairing link`() {
        listOf(
            "https://logb.example",
            "logb://other?server=https%3A%2F%2Fa&code=c",
            "logb://pair?code=c",
            "logb://pair?server=https%3A%2F%2Fa",
            "logb://pair?server=ftp%3A%2F%2Fa&code=c",
            "",
        ).forEach { assertNull(PairingLinks.parse(it), it) }
    }

    @Test fun `an ipv6 literal is accepted as local`() =
        assertEquals(
            "http://[::1]:8090/",
            PairingLinks.parse("logb://pair?server=http%3A%2F%2F%5B%3A%3A1%5D%3A8090&code=c")!!.serverUrl,
        )

    @Test fun `an uppercase scheme is normalised`() =
        assertEquals(
            "https://logb.example/",
            PairingLinks.parse("logb://pair?server=HTTPS%3A%2F%2Flogb.example%2F&code=c")!!.serverUrl,
        )

    @Test fun `a decoy host that merely contains a private address is refused`() =
        assertNull(PairingLinks.parse("logb://pair?server=http%3A%2F%2F192.168.1.5.evil.com&code=c"))
}
