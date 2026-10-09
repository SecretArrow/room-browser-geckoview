package com.roombrowser.domain.oct

/**
 * Builds the sealed envelopes a reader has to open.
 *
 * The app has no writer, so without this every test of the sealed path would have to
 * re-implement the salt, the `u32be` framing and the iteration count itself — and a
 * re-implementation that drifts is a test that keeps passing against a format the app
 * no longer speaks. It lives in test fixtures rather than beside the protocol object so
 * that [OctSealed.seal] stays out of the module's own surface.
 */
object OctCircleFixtures {

    /** One sealed asset, as the node would hand it over. */
    data class Sealed(val envelope: ByteArray, val plaintextHash: String)

    fun seal(
        payload: ByteArray,
        circleId: String,
        keyId: String,
        passphrase: CharArray
    ): Sealed = Sealed(
        OctSealed.seal(payload, circleId, keyId, passphrase),
        OctSealed.plaintextHash(payload)
    )

    fun seal(
        payload: String,
        circleId: String,
        keyId: String,
        passphrase: CharArray
    ): Sealed = seal(payload.toByteArray(Charsets.UTF_8), circleId, keyId, passphrase)
}
