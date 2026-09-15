package dev.logb.android.core.auth

import androidx.biometric.BiometricManager

/** When the lock engages, and whether the device can unlock it. Pure, so the timing is tested without a device. */
object LockPolicy {
    /**
     * How long the app may sit in the background before it locks again. A camera or file picker
     * round trip sends the app to the background for a few seconds; locking the person out on
     * the way back from taking a photo would be absurd.
     */
    const val GRACE_MS = 60_000L

    /** `backgroundedAt` is null on a cold start, which always locks when the lock is on. */
    fun shouldLock(enabled: Boolean, backgroundedAt: Long?, now: Long): Boolean =
        enabled && (backgroundedAt == null || now - backgroundedAt > GRACE_MS)

    /**
     * A notification tap or launcher shortcut just handed the app a target to open, while this
     * resume's own re-check of the lock (`RootViewModel.onForeground`) has not landed yet
     * (`backgroundedAt` is still set -- it is only cleared once that check completes). Trusting
     * the *previous* session's "unlocked" answer for one more frame would let `AppNavHost` mount,
     * consume the target, and then get torn down the moment the real (possibly "locked") answer
     * lands a moment later -- losing the target with it, since consuming it is a one-shot read.
     * Returning true says: drop back to "unknown" and wait, rather than trust a stale belief.
     * Already `true` (still locked, e.g. a fresh `onForeground` retriggered by a stray lifecycle
     * blip) or already `null` (still unknown) is left alone, so this can never itself unlock.
     */
    fun shouldRecheckForTarget(backgroundedAt: Long?, currentlyLocked: Boolean?): Boolean =
        backgroundedAt != null && currentlyLocked == false

    enum class Availability { Available, NoneEnrolled, NoHardware, Unavailable }

    /** The `BiometricManager.canAuthenticate` code for `BIOMETRIC_WEAK or DEVICE_CREDENTIAL`, read for the settings switch. */
    fun availability(canAuthenticate: Int): Availability = when (canAuthenticate) {
        BiometricManager.BIOMETRIC_SUCCESS -> Availability.Available
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> Availability.NoneEnrolled
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE, BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> Availability.NoHardware
        else -> Availability.Unavailable
    }

    const val AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
}
