package dev.logb.android.feature.settings

import dev.logb.android.core.server.Capabilities

/** Everything the About page shows, and the plain text "Copy details" puts on the clipboard. */
data class AboutInfo(
    val versionName: String,
    val versionCode: Int,
    val buildDate: String,
    val commit: String,
    val debug: Boolean,
    val releaseKey: Boolean,
    val serverUrl: String?,
    val serverVersion: String?,
    val capabilities: Capabilities,
) {
    val commitUrl: String? get() = commit.takeIf { it != "unknown" }?.let { "$REPO_URL/commit/$it" }

    /** English on purpose: this text goes into bug reports. */
    fun copyText(): String = buildList {
        add("LogB $versionName ($versionCode)")
        add("Built $buildDate from $commit, ${if (debug) "debug" else "release"}, ${if (releaseKey) "release key" else "debug key"}")
        if (serverUrl == null) {
            add("No server")
        } else {
            add("Server ${serverUrl.removeSuffix("/")} ${serverVersion ?: "version unknown"}")
            add("Tags: ${yesNo(capabilities.tags)}, own types: ${yesNo(capabilities.ownTypes)}, QR sign-in: ${yesNo(capabilities.pairing)}")
        }
    }.joinToString("\n")

    private fun yesNo(b: Boolean) = if (b) "yes" else "no"

    companion object {
        const val REPO_URL = "https://github.com/13/logb_mobile"
        const val ISSUES_URL = "$REPO_URL/issues"
    }
}
