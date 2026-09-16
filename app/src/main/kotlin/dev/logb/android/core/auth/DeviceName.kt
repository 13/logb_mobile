package dev.logb.android.core.auth

import android.os.Build

/**
 * The name a redeemed pairing token is minted under, so the person can tell this phone apart from
 * others on the web's token list ([SessionRepository.signInWithPairing]'s doc explains why that
 * matters -- unlike a password sign-in, a failed pairing leaves the token live with nothing local
 * to show for it).
 */
object DeviceName {
    private const val MAX_LENGTH = 64
    private val WHITESPACE = Regex("\\s+")

    /** `"${Build.MANUFACTURER} ${Build.MODEL}"`, sanitised. */
    fun current(): String = sanitize("${Build.MANUFACTURER} ${Build.MODEL}")

    /**
     * Trimmed, every run of whitespace collapsed to one space, capped at [MAX_LENGTH] characters
     * (the server's token name has the same limit -- see the release plan's `create_token`/pairing
     * notes). Never blank: an empty or all-whitespace name -- `Build.MANUFACTURER`/`MODEL` reading
     * empty, or a device that reports nothing at all -- falls back to "Android" rather than mint a
     * nameless token.
     */
    fun sanitize(raw: String): String {
        val collapsed = raw.trim().replace(WHITESPACE, " ").take(MAX_LENGTH)
        return collapsed.ifBlank { "Android" }
    }
}
