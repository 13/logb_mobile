package dev.logb.android

import android.content.Intent
import android.net.Uri
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [MainActivity.shouldOffer] -- extracted so the review finding ("Recents relaunch re-offers a
 * used link") is a plain, unit-testable predicate rather than logic buried in onCreate()/
 * onNewIntent(). Only a pairing link carrying [Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY] is
 * refused; everything else -- a share, a launch target, or a pairing link with no such flag -- is
 * unaffected.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityShouldOfferTest {
    private fun pairIntent(fromHistory: Boolean = false): Intent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("logb://pair?server=${java.net.URLEncoder.encode("https://logb.example.org/", "UTF-8")}&code=abc123"))
        if (fromHistory) intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        return intent
    }

    @Test fun `a pairing link reopened from Recents is refused`() {
        assertFalse(MainActivity.shouldOffer(pairIntent(fromHistory = true)))
    }

    @Test fun `a pairing link with no from-history flag is offered`() {
        assertTrue(MainActivity.shouldOffer(pairIntent(fromHistory = false)))
    }

    @Test fun `a non-pairing intent from Recents is still offered`() {
        val send = Intent(Intent.ACTION_SEND).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        assertTrue(MainActivity.shouldOffer(send))
    }

    @Test fun `a null intent is offered (nothing to refuse)`() {
        assertTrue(MainActivity.shouldOffer(null))
    }

    @Test fun `a malformed pairing-scheme link from Recents is still offered -- offer() itself will drop it`() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("logb://pair?server=${java.net.URLEncoder.encode("http://example.org/", "UTF-8")}&code=abc123"))
        intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        assertTrue(MainActivity.shouldOffer(intent))
    }
}
