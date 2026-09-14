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
