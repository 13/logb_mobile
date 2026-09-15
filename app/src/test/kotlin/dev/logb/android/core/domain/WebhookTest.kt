package dev.logb.android.core.domain

import org.junit.Test
import kotlin.test.assertEquals

class WebhookTest {
    @Test fun `blank means none`() = assertEquals(WebhookCheck.None, Webhook.normalize("   "))

    @Test fun `http and https with a host are accepted, trimmed`() {
        assertEquals(WebhookCheck.Valid("https://ntfy.sh/logb"), Webhook.normalize(" https://ntfy.sh/logb "))
        assertEquals(WebhookCheck.Valid("http://192.168.1.5:8080/x"), Webhook.normalize("http://192.168.1.5:8080/x"))
    }

    @Test fun `other schemes and host-less addresses are refused`() {
        assertEquals(WebhookCheck.Invalid, Webhook.normalize("ftp://example.org"))
        assertEquals(WebhookCheck.Invalid, Webhook.normalize("https://"))
        assertEquals(WebhookCheck.Invalid, Webhook.normalize("ntfy.sh/logb"))
    }

    @Test fun `mailto is refused`() = assertEquals(WebhookCheck.Invalid, Webhook.normalize("mailto:x@y"))

    @Test fun `a path with a space is valid, WHATWG-style`() =
        assertEquals(WebhookCheck.Valid("https://ntfy.sh/my topic"), Webhook.normalize("https://ntfy.sh/my topic"))

    @Test fun `exactly 2000 characters is valid`() {
        val url = "https://ntfy.sh/" + "a".repeat(2000 - "https://ntfy.sh/".length)
        assertEquals(2000, url.length)
        assertEquals(WebhookCheck.Valid(url), Webhook.normalize(url))
    }

    @Test fun `over 2000 characters is invalid`() {
        val url = "https://ntfy.sh/" + "a".repeat(2001 - "https://ntfy.sh/".length)
        assertEquals(2001, url.length)
        assertEquals(WebhookCheck.Invalid, Webhook.normalize(url))
    }
}
