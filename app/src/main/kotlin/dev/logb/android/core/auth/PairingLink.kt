package dev.logb.android.core.auth

import dev.logb.android.core.network.ApiClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder

/** A `logb://pair` link scanned from a QR code or opened as a deep link. */
data class PairingLink(val serverUrl: String, val code: String) {
    // The code is the proof that redeems for a live token; it must never end up in a log line
    // via an incidental toString() -- e.g. a caller logging "got $link" -- the way the generated
    // data class one would.
    override fun toString(): String = "PairingLink(serverUrl=$serverUrl, code=<redacted>)"
}

/**
 * Parses `logb://pair?server=<percent-encoded base URL>&code=<code>` links.
 *
 * A QR code must never silently point the phone at an arbitrary cleartext host: `https://` is
 * accepted for any server, but `http://` only for `localhost`, the private LAN ranges (`10/8`,
 * `172.16/12`, `192.168/16`, `127/8`), the Tailscale/CGNAT range `100.64.0.0/10`, IPv6 loopback
 * (`::1`) and unique-local addresses (`fc00::/7`), and `.local`/`.lan`/`.home.arpa` names.
 * Anything else -- and anything that isn't a well-formed pairing link at all -- parses as `null`;
 * [classify] tells the two apart, since they call for different messages ([Outcome.NotACode] vs
 * [Outcome.UnsafeAddress]). Kept to `java.net.URI`/`URLDecoder` rather than `android.net.Uri` so
 * it runs as a plain JVM test.
 */
object PairingLinks {
    private val LOCAL_SUFFIXES = listOf(".local", ".lan", ".home.arpa")

    /** What a scanned or opened text amounted to. */
    sealed interface Outcome {
        data class Parsed(val link: PairingLink) : Outcome

        /** Not a `logb://pair` link at all -- malformed, a different scheme, missing `server`/`code`, or a server URL that fails a structural check ([classifyServerUrl]'s [ServerUrlResult.Malformed]). */
        data object NotACode : Outcome

        /** A well-formed `logb://pair` link, but its server address is `http` and outside every range the safety rule allows for cleartext -- deliberately reported apart from [NotACode], since the code itself is fine; it names an address this phone will not trust unencrypted. */
        data object UnsafeAddress : Outcome
    }

    /** [Outcome.Parsed]'s link, or `null` for anything else -- callers that only need the yes/no answer (deep-link routing, say) rather than [Outcome]'s own detail. */
    fun parse(text: String): PairingLink? = (classify(text) as? Outcome.Parsed)?.link

    fun classify(text: String): Outcome {
        if (text.isBlank()) return Outcome.NotACode
        val uri = parseUri(text) ?: return Outcome.NotACode
        if (!uri.scheme.equals("logb", ignoreCase = true)) return Outcome.NotACode
        if (!uri.host.equals("pair", ignoreCase = true)) return Outcome.NotACode
        val params = parseQuery(uri.rawQuery ?: return Outcome.NotACode)
        val rawServer = params["server"]?.takeIf { it.isNotBlank() } ?: return Outcome.NotACode
        val code = params["code"]?.takeIf { it.isNotBlank() } ?: return Outcome.NotACode
        return when (val result = classifyServerUrl(rawServer)) {
            is ServerUrlResult.Ok -> Outcome.Parsed(PairingLink(result.url, code))
            ServerUrlResult.Malformed -> Outcome.NotACode
            ServerUrlResult.UnsafeHttp -> Outcome.UnsafeAddress
        }
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

    private sealed interface ServerUrlResult {
        data class Ok(val url: String) : ServerUrlResult
        data object Malformed : ServerUrlResult

        /** A structurally fine `http://` server URL whose host is outside every allowed range. */
        data object UnsafeHttp : ServerUrlResult
    }

    /** Validates the decoded server URL against the scheme rule and normalises its trailing slash. */
    private fun classifyServerUrl(rawServer: String): ServerUrlResult {
        val uri = parseUri(rawServer) ?: return ServerUrlResult.Malformed
        val scheme = uri.scheme ?: return ServerUrlResult.Malformed
        val host = uri.host ?: return ServerUrlResult.Malformed
        // Userinfo, a query or a fragment on the server URL itself has no legitimate use here and
        // is exactly how a decoy host gets smuggled in front of the real one (e.g.
        // "http://evil.com@192.168.1.5" parses with host "192.168.1.5" but a browser -- and a
        // careless label in this app -- would show "evil.com"). A malformed port (0, or above
        // 65535) is rejected too: java.net.URI accepts digits without range-checking them.
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return ServerUrlResult.Malformed
        if (uri.port != -1 && uri.port !in 1..65535) return ServerUrlResult.Malformed
        when {
            scheme.equals("https", ignoreCase = true) -> Unit
            scheme.equals("http", ignoreCase = true) -> if (!isLocalOrPrivate(host)) return ServerUrlResult.UnsafeHttp
            else -> return ServerUrlResult.Malformed
        }
        // Only the scheme's case is normalised here; ApiClient.normalizeBaseUrl only recognises
        // a lowercase "http://"/"https://" prefix and would otherwise treat e.g. "HTTPS://" as
        // schemeless and prepend another "https://" in front of it.
        val lowerSchemeUrl = scheme.lowercase() + rawServer.substring(scheme.length)
        val normalized = ApiClient.normalizeBaseUrl(lowerSchemeUrl)
        // Belt and braces: java.net.URI and OkHttp's own parser must agree on the host, or the
        // string that reaches Retrofit later could resolve somewhere this check never looked at.
        val reparsed = normalized.toHttpUrlOrNull() ?: return ServerUrlResult.Malformed
        val checkedHost = host.removePrefix("[").removeSuffix("]").lowercase()
        if (reparsed.host != checkedHost) return ServerUrlResult.Malformed
        return ServerUrlResult.Ok(normalized)
    }

    private fun isLocalOrPrivate(host: String): Boolean {
        val h = host.removePrefix("[").removeSuffix("]").lowercase()
        if (h == "localhost" || h == "::1") return true
        if (LOCAL_SUFFIXES.any { h.endsWith(it) }) return true
        if (isPrivateIpv4(h)) return true
        return isIpv6UniqueLocal(h)
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
        return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
            // 100.64.0.0/10 -- Tailscale's (and CGNAT's) own range: the second octet's top two
            // bits must be 01, i.e. 64..127; 100.63.x and 100.128.x are outside it.
            (a == 100 && b in 64..127)
    }

    /** `fc00::/7`: the address's top 7 bits are `1111110`, i.e. its first 16-bit group's high byte is `0xFC` or `0xFD`. [host] is already bracket-stripped and lower-cased by [isLocalOrPrivate]. */
    private fun isIpv6UniqueLocal(host: String): Boolean {
        if (!host.contains(':')) return false
        val firstGroup = host.substringBefore(':').ifEmpty { "0" }
        if (firstGroup.length > 4 || firstGroup.any { it !in '0'..'9' && it !in 'a'..'f' }) return false
        val highByte = firstGroup.padStart(4, '0').substring(0, 2).toInt(16)
        return (highByte and 0xFE) == 0xFC
    }
}
