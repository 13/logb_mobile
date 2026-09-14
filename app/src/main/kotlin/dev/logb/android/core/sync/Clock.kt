package dev.logb.android.core.sync

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Time as the sync protocol speaks it: RFC 3339 in UTC with milliseconds. */
object Clock {
    private val formatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    fun nowIso(): String = formatter.format(Instant.now())

    /** Local time corrected by the last known server offset, for stamping `edited_at`. */
    fun correctedNowIso(offsetMs: Long): String = formatter.format(Instant.now().plusMillis(offsetMs))

    /** `server_time - now`, in milliseconds; zero if the server's time does not parse. */
    fun offsetMs(serverTime: String): Long = parse(serverTime)?.let { it.toEpochMilli() - System.currentTimeMillis() } ?: 0

    fun parse(rfc3339: String): Instant? =
        runCatching { OffsetDateTime.parse(rfc3339).toInstant() }.getOrNull() ?: runCatching { Instant.parse(rfc3339) }.getOrNull()

    /** The server's canonical form: UTC, millisecond precision. Null when it does not parse. */
    fun canonical(rfc3339: String): String? = parse(rfc3339)?.let { formatter.format(it) }
}
