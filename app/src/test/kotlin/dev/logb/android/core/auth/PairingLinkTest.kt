package dev.logb.android.core.auth

import org.junit.Test
import java.net.URLEncoder
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PairingLinkTest {
    /** `logb://pair?server=http://<host>&code=c`, with the server URL percent-encoded as a real QR payload would carry it. */
    private fun httpPairUri(host: String): String =
        "logb://pair?server=" + URLEncoder.encode("http://$host", "UTF-8") + "&code=c"

    /** `logb://pair?server=<serverUrl>&code=c`, with an arbitrary server URL percent-encoded as a real QR payload would carry it. */
    private fun pairUri(serverUrl: String): String =
        "logb://pair?server=" + URLEncoder.encode(serverUrl, "UTF-8") + "&code=c"

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

    @Test fun `non-canonical ipv4 octets are rejected, not leniently reparsed as octal or truncated`() {
        listOf("010.8.8.8", "0x0a.0.0.1", "10.1", "172.32.0.1", "192.169.0.1").forEach { host ->
            assertNull(PairingLinks.parse(httpPairUri(host)), host)
        }
    }

    @Test fun `canonical decimal private ipv4 octets are accepted`() {
        listOf("172.16.0.1", "172.31.255.255", "10.0.0.1", "192.168.0.1").forEach { host ->
            assertEquals("http://$host/", PairingLinks.parse(httpPairUri(host))!!.serverUrl, host)
        }
    }

    @Test fun `userinfo in front of a private host is refused, not silently dropped`() =
        assertNull(PairingLinks.parse(pairUri("http://evil.com@192.168.1.5")))

    @Test fun `userinfo in front of a public host is refused, not silently dropped`() =
        assertNull(PairingLinks.parse(pairUri("https://evil.com@logb.example/")))

    @Test fun `a query on the server url is refused`() =
        assertNull(PairingLinks.parse(pairUri("https://logb.example/?x=1")))

    @Test fun `a fragment on the server url is refused`() =
        assertNull(PairingLinks.parse(pairUri("https://logb.example/#frag")))

    @Test fun `port 0 is refused`() =
        assertNull(PairingLinks.parse(pairUri("http://192.168.1.5:0")))

    @Test fun `a port above 65535 is refused`() =
        assertNull(PairingLinks.parse(pairUri("http://192.168.1.5:70000")))

    @Test fun `a mixed-case scheme does not let userinfo slip past the check`() =
        assertNull(PairingLinks.parse(pairUri("HTTPS://evil.com@logb.example/")))
}
