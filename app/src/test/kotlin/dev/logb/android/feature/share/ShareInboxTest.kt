package dev.logb.android.feature.share

import android.content.Intent
import android.net.Uri
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `logb://pair` deep links: recognised, parsed and held for [dev.logb.android.feature.pairing.PairingConfirmViewModel] -- never acted on here. */
@RunWith(RobolectricTestRunner::class)
class ShareInboxTest {
    private fun viewIntent(uri: String) = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addCategory(Intent.CATEGORY_BROWSABLE).addCategory(Intent.CATEGORY_DEFAULT)

    private fun pairUri(server: String = "https://logb.example.org/", code: String = "abc123") =
        "logb://pair?server=${java.net.URLEncoder.encode(server, "UTF-8")}&code=$code"

    @Test fun `a valid pairing deep link is held as pendingPairing`() {
        val inbox = ShareInbox()
        val offered = inbox.offer(viewIntent(pairUri()))

        assertTrue(offered)
        val pending = inbox.pendingPairing.value
        assertEquals("https://logb.example.org/", pending?.link?.serverUrl)
        assertEquals("abc123", pending?.link?.code)
    }

    @Test fun `the arrival time is recorded when the link is offered, not read later`() {
        val inbox = ShareInbox()
        val arrivedAt = 1_000_000L

        inbox.offer(viewIntent(pairUri()), nowMs = arrivedAt)

        assertEquals(arrivedAt, inbox.pendingPairing.value?.arrivedAtMs)
    }

    @Test fun `takePendingPairing hands it over once`() {
        val inbox = ShareInbox()
        inbox.offer(viewIntent(pairUri()))

        val first = inbox.takePendingPairing()
        val second = inbox.takePendingPairing()

        assertEquals("abc123", first?.code)
        assertNull(second)
        assertNull(inbox.pendingPairing.value)
    }

    @Test fun `a VIEW intent that is not a pairing link is not offered`() {
        val inbox = ShareInbox()
        val offered = inbox.offer(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.org/")))

        assertEquals(false, offered)
        assertNull(inbox.pendingPairing.value)
    }

    @Test fun `a malformed pairing link (http to a public host) is dropped, not held`() {
        val inbox = ShareInbox()
        val offered = inbox.offer(viewIntent("logb://pair?server=${java.net.URLEncoder.encode("http://example.org/", "UTF-8")}&code=abc123"))

        assertEquals(false, offered)
        assertNull(inbox.pendingPairing.value)
    }

    @Test fun `a launch target intent still wins over pairing recognition`() {
        val inbox = ShareInbox()
        val intent = Intent(LaunchTarget.ACTION).putExtra(LaunchTarget.EXTRA, LaunchTarget.Search.name)

        assertTrue(inbox.offer(intent))
        assertEquals(LaunchTarget.Search, inbox.target.value?.target)
        assertNull(inbox.pendingPairing.value)
    }
}
