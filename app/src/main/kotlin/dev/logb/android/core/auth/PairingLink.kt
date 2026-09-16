package dev.logb.android.core.auth

import dev.logb.android.core.network.ApiClient
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder

/** A `logb://pair` link scanned from a QR code or opened as a deep link. */
data class PairingLink(val serverUrl: String, val code: String)

/**
 * Parses `logb://pair?server=<percent-encoded base URL>&code=<code>` links.
 *
 * A QR code must never silently point the phone at an arbitrary cleartext host: `https://` is
 * accepted for any server, but `http://` only for `localhost`, the private LAN ranges
 * (`10/8`, `172.16/12`, `192.168/16`, `127/8`, `::1`) and `.local`/`.lan`/`.home.arpa` names.
 * Anything else -- and anything that isn't a well-formed pairing link -- parses as `null`. Kept
 * to `java.net.URI`/`URLDecoder` rather than `android.net.Uri` so it runs as a plain JVM test.
 */
object PairingLinks {
    private val LOCAL_SUFFIXES = listOf(".local", ".lan", ".home.arpa")

    fun parse(text: String): PairingLink? {
        if (text.isBlank()) return null
        val uri = parseUri(text) ?: return null
        if (!uri.scheme.equals("logb", ignoreCase = true)) return null
        if (!uri.host.equals("pair", ignoreCase = true)) return null
        val params = parseQuery(uri.rawQuery ?: return null)
        val rawServer = params["server"]?.takeIf { it.isNotBlank() } ?: return null
        val code = params["code"]?.takeIf { it.isNotBlank() } ?: return null
        val serverUrl = normalizeServerUrl(rawServer) ?: return null
        return PairingLink(serverUrl, code)
    }

    private fun parseUri(text: String): URI? = try {
        URI(text)
    } catch (e: URISyntaxException) {
        null
    }

    private fun parseQuery(query: String): Map<String, String> =
        query.split('&').mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx < 0) return@mapNotNull null
            decode(pair.substring(0, idx)) to decode(pair.substring(idx + 1))
        }.toMap()

    private fun decode(value: String): String = try {
        URLDecoder.decode(value, "UTF-8")
    } catch (e: IllegalArgumentException) {
        value
    }

    /** Validates the decoded server URL against the scheme rule and normalises its trailing slash. */
    private fun normalizeServerUrl(rawServer: String): String? {
        val uri = parseUri(rawServer) ?: return null
        val scheme = uri.scheme ?: return null
        val host = uri.host ?: return null
        val allowed = when {
            scheme.equals("https", ignoreCase = true) -> true
            scheme.equals("http", ignoreCase = true) -> isLocalOrPrivate(host)
            else -> false
        }
        if (!allowed) return null
        // Only the scheme's case is normalised here; ApiClient.normalizeBaseUrl only recognises
        // a lowercase "http://"/"https://" prefix and would otherwise treat e.g. "HTTPS://" as
        // schemeless and prepend another "https://" in front of it.
        val lowerSchemeUrl = scheme.lowercase() + rawServer.substring(scheme.length)
        return ApiClient.normalizeBaseUrl(lowerSchemeUrl)
    }

    private fun isLocalOrPrivate(host: String): Boolean {
        val h = host.removePrefix("[").removeSuffix("]").lowercase()
        if (h == "localhost" || h == "::1") return true
        if (LOCAL_SUFFIXES.any { h.endsWith(it) }) return true
        return isPrivateIpv4(h)
    }

    // A canonical decimal octet: "0", or a nonzero digit followed by up to two more digits --
    // never a leading zero. `toIntOrNull("010")` reads as 10, but some resolvers treat a leading
    // zero as an octal prefix (`010` == 8), so a leniently-parsed octet could name a different,
    // possibly public, address than the one this check believes it approved.
    private val CANONICAL_OCTET = Regex("0|[1-9]\\d{0,2}")

    private fun isPrivateIpv4(host: String): Boolean {
        val octets = host.split('.')
        if (octets.size != 4) return false
        if (octets.any { !CANONICAL_OCTET.matches(it) }) return false
        val nums = octets.map { it.toInt() }
        if (nums.any { it > 255 }) return false
        val a = nums[0]
        val b = nums[1]
        return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
    }
}
