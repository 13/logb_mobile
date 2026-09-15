package dev.logb.android.feature.update

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.logb.android.core.design.AppSignature
import java.io.File
import javax.inject.Inject

/** SHA-256 digests of signing certificates: this installed app's, and a downloaded APK's. */
interface ApkSignatures {
    fun installed(): Set<String>

    /** Null when the file cannot be read as an APK or names no signer. */
    fun ofArchive(file: File): Set<String>?
}

class PackageManagerApkSignatures @Inject constructor(@ApplicationContext private val context: Context) : ApkSignatures {
    override fun installed(): Set<String> =
        digests(context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners)

    override fun ofArchive(file: File): Set<String>? {
        val pm = context.packageManager
        // Before Android 13 an archive's signingInfo can come back null; the older field still carries the signers.
        val signers = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)?.signingInfo?.apkContentsSigners
            ?: legacySigners(pm, file)
        return digests(signers).takeIf { it.isNotEmpty() }
    }

    @Suppress("DEPRECATION")
    private fun legacySigners(pm: PackageManager, file: File): Array<Signature>? =
        pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNATURES)?.signatures

    private fun digests(signers: Array<Signature>?): Set<String> =
        signers.orEmpty().map { AppSignature.sha256Hex(it.toByteArray()) }.toSet()
}
