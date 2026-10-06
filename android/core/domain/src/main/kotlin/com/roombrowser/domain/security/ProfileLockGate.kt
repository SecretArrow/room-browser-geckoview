package com.roombrowser.domain.security

/**
 * Which credential a profile-locked screen presents, in the approved order:
 * the device credential (fingerprint / face / system screen lock) first, then
 * the profile PIN when the device has none, and only the unprotected-banner
 * case when there is neither.
 */
enum class ProfileLockGate {
    DEVICE_CREDENTIAL,
    PIN,

    /** No device credential and no PIN: the screen opens behind a warning. */
    UNPROTECTED;

    companion object {
        /**
         * @param pinConfigured true / false from the lock store, or null when
         *   the decision could not be made. Null is NOT "no PIN": it fails
         *   closed onto [PIN], and only a positive false may reach [UNPROTECTED].
         */
        fun choose(biometricsAvailable: Boolean, pinConfigured: Boolean?): ProfileLockGate = when {
            biometricsAvailable -> DEVICE_CREDENTIAL
            pinConfigured == false -> UNPROTECTED
            else -> PIN
        }
    }
}
