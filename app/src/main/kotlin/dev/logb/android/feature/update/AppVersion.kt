package dev.logb.android.feature.update

/**
 * A release version as three numbers, so "0.10.0" sorts above "0.9.0".
 *
 * Version *names* are compared, never version codes: `release.yml` derives the code from the name,
 * and repeating that arithmetic here would let the two drift apart. Stricter than
 * `core.server.ServerVersion` on purpose: a suffix there is a server build detail, here it would
 * be a release nobody should be offered.
 */
data class AppVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        /** "0.7.1" and "v0.7.1"; "1.2" is 1.2.0. Anything else, including "1.0.0-rc1", is null. */
        fun parse(raw: String?): AppVersion? {
            val text = raw?.trim()?.removePrefix("v").orEmpty()
            if (text.isEmpty()) return null
            val parts = text.split('.')
            if (parts.size > 3) return null
            val numbers = parts.map { part -> part.toIntOrNull()?.takeIf { it >= 0 } ?: return null }
            return AppVersion(numbers[0], numbers.getOrElse(1) { 0 }, numbers.getOrElse(2) { 0 })
        }
    }
}
