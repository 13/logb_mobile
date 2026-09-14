package dev.logb.android.core.server

/** A server version as numbers, compared part by part; "0.8" equals "0.8.0". */
class ServerVersion private constructor(private val parts: List<Int>) : Comparable<ServerVersion> {
    override fun compareTo(other: ServerVersion): Int {
        for (i in 0 until maxOf(parts.size, other.parts.size)) {
            val c = parts.getOrElse(i) { 0 }.compareTo(other.parts.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    override fun equals(other: Any?): Boolean = other is ServerVersion && compareTo(other) == 0
    override fun hashCode(): Int = parts.dropLastWhile { it == 0 }.hashCode()
    override fun toString(): String = parts.joinToString(".")

    companion object {
        val ZERO = ServerVersion(listOf(0))

        /** "0.8.0-rc1" reads as 0.8.0; anything without a leading number is [ZERO]. */
        fun parse(value: String?): ServerVersion {
            val core = value?.trim()?.substringBefore('-')?.substringBefore('+').orEmpty()
            val parts = core.split('.').map { it.toIntOrNull() ?: return ZERO }
            return if (parts.isEmpty()) ZERO else ServerVersion(parts)
        }
    }
}

/** What the signed-in server supports, from its version. */
data class Capabilities(val tags: Boolean, val ownTypes: Boolean, val pairing: Boolean) {
    companion object {
        val NONE = Capabilities(tags = false, ownTypes = false, pairing = false)
        private val TAGS = ServerVersion.parse("0.8.0")
        private val PAIRING = ServerVersion.parse("0.9.0")

        fun of(version: String?): Capabilities {
            val v = ServerVersion.parse(version)
            return Capabilities(tags = v >= TAGS, ownTypes = v >= TAGS, pairing = v >= PAIRING)
        }
    }
}
