package dev.logb.android.core.design

import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Which key signed the installed app: the release key, or anything else (a debug build). */
object AppSignature {
    const val RELEASE_SHA256 = "ef46d303232d7394d83b42f117e2c81f1ca5fe7399a22d0ac0d7dda19a60b8f3"

    fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun isRelease(digests: List<String>): Boolean = digests.any { it.equals(RELEASE_SHA256, ignoreCase = true) }

    fun isReleaseSigned(context: Context): Boolean = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signers = info.signingInfo?.apkContentsSigners.orEmpty()
        isRelease(signers.map { sha256Hex(it.toByteArray()) })
    }.getOrDefault(false)
}

/**
 * Caches [AppSignature.isReleaseSigned] for the process. `PackageManager.getPackageInfo` is a
 * blocking IPC call, so this holds it behind a `by lazy` rather than a ViewModel constructor
 * computing it eagerly on whatever thread creates the ViewModel (usually the main thread).
 */
@Singleton
class ReleaseKey @Inject constructor(@ApplicationContext private val context: Context) {
    val isRelease: Boolean by lazy { AppSignature.isReleaseSigned(context) }
}
