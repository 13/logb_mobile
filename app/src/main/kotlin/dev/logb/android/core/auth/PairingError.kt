package dev.logb.android.core.auth

/**
 * What went wrong pairing, for display. [NotACode] is decided before [SessionRepository.signInWithPairing]
 * is even called -- a scanned text that [PairingLinks.parse] refused; [CameraPermissionDenied] is
 * decided even earlier, before a scan ever reaches [PairingLinks.parse] -- everything else
 * classifies that call's outcome. A sealed interface rather than a plain enum only because
 * [Rejected] carries the server's own words when it has any; kept free of Android/Compose so it
 * is plain to unit test, and the screen that shows it owns the string resource for each case.
 */
sealed interface PairError {
    data object NotACode : PairError
    data object Unsupported : PairError
    data object Invalid : PairError

    /** The redeem call answered 429: this IP has tried too many codes too quickly. */
    data object RateLimited : PairError

    /** The redeem call answered 400. [message] is the server's own words when it sent any (never blank); null falls back to a generic "refused" message. */
    data class Rejected(val message: String?) : PairError
    data object Unreachable : PairError
    data object CameraPermissionDenied : PairError
}

/** Classifies a [SessionRepository.signInWithPairing] failure; never called for a [PairError.NotACode], which never reaches the repository. */
fun classifyPairingError(e: Throwable?): PairError = when (e) {
    is PairingUnsupported -> PairError.Unsupported
    is PairingInvalid -> PairError.Invalid
    is PairingRateLimited -> PairError.RateLimited
    is PairingRejected -> PairError.Rejected(e.message?.takeIf { it.isNotBlank() })
    else -> PairError.Unreachable
}
