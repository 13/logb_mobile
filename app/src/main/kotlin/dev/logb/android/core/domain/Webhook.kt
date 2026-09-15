package dev.logb.android.core.domain

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

sealed interface WebhookCheck {
    data object None : WebhookCheck
    data class Valid(val url: String) : WebhookCheck
    data object Invalid : WebhookCheck
}

/**
 * Mirrors `api::notifications::validate_webhook` in the server (`src/api/notifications.rs`):
 * trim first, blank means none, at most 2000 characters, and it must parse as an http(s) URL
 * with a host. The server parses with `reqwest::Url` (WHATWG); `java.net.URI` is stricter about
 * what it accepts (spaces, unencoded characters), so this uses OkHttp's WHATWG-based
 * [toHttpUrlOrNull] instead, which -- like the server -- already requires http/https and a host.
 * Private addresses are allowed on purpose: a self-hosted ntfy on the home network is the
 * likeliest target of all.
 */
object Webhook {
    private const val MAX_LENGTH = 2000

    fun normalize(raw: String): WebhookCheck {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return WebhookCheck.None
        if (trimmed.length > MAX_LENGTH) return WebhookCheck.Invalid
        trimmed.toHttpUrlOrNull() ?: return WebhookCheck.Invalid
        return WebhookCheck.Valid(trimmed)
    }
}
