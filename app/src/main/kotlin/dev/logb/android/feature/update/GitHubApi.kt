package dev.logb.android.feature.update

import dev.logb.android.BuildConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Path

/**
 * api.github.com: the releases of this app's own repository. Public, no token; "latest" already
 * excludes drafts and pre-releases. 60 unauthenticated requests an hour per address is far above
 * one automatic check a day plus a button.
 */
interface GitHubApi {
    @GET("repos/{repo}/releases/latest")
    suspend fun latestRelease(@Path("repo", encoded = true) repo: String = BuildConfig.UPDATE_REPO): GitHubRelease

    companion object { const val BASE_URL = "https://api.github.com/" }
}

@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
    val name: String? = null,
    val body: String? = null,
    val assets: List<GitHubAsset> = emptyList(),
) {
    /** The APK to install; null for a release without one, which the caller reports. */
    val apk: GitHubAsset? get() = assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
}

@Serializable
data class GitHubAsset(
    val name: String = "",
    val size: Long = 0,
    @SerialName("browser_download_url") val downloadUrl: String = "",
    /** "sha256:<hex>" where GitHub published it. */
    val digest: String? = null,
) {
    val sha256: String? get() = digest?.removePrefix("sha256:")?.takeIf { it.length == 64 && it != digest }
}
