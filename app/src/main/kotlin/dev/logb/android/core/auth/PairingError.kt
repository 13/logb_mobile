package dev.logb.android.core.auth

/**
 * What went wrong pairing, for display. [NotACode] is decided before [SessionRepository.signInWithPairing]
 * is even called -- a scanned text that [PairingLinks.parse] refused; [CameraPermissionDenied] is
 * decided even earlier, before a scan ever reaches [PairingLinks.parse] -- everything else
 * classifies that call's outcome. Kept free of Android/Compose so it is plain to unit test; the
 * screen that shows it owns the string resource for each case.
 */
enum class PairError { NotACode, Unsupported, Invalid, Unreachable, CameraPermissionDenied }

/** Classifies a [SessionRepository.signInWithPairing] failure; never called for a [PairError.NotACode], which never reaches the repository. */
fun classifyPairingError(e: Throwable?): PairError = when (e) {
    is PairingUnsupported -> PairError.Unsupported
    is PairingInvalid -> PairError.Invalid
    else -> PairError.Unreachable
}
