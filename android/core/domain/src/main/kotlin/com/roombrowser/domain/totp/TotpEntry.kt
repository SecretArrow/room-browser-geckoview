package com.roombrowser.domain.totp

/**
 * One authenticator account of one profile.
 *
 * [secret] is the Base32 seed, PLAINTEXT IN MEMORY ONLY — like a saved
 * credential's password, its persisted form is ciphertext (Room stores
 * `totp_entries.secret_enc`, sealed with the profile's 2FA key). It must never
 * be logged, put in a URL, or handed to the in-app agent; only generated codes
 * are. Issuer and account stay plaintext so the list can be searched without
 * decrypting, the same trade the password vault already makes.
 *
 * [toString] is overridden on purpose: a generated `data class` prints every
 * property, and this object travels through the UI and the agent — one log line
 * or one screenshot away from leaking the seed.
 */
data class TotpEntry(
    /** Stable row id (UUID). Regenerated on import so PKs never collide. */
    val id: String,
    /** Owning profile (ProfileId.value). Entries never cross profiles. */
    val profileId: String,
    val issuer: String,
    val account: String,
    val secret: String,
    val algorithm: TotpAlgorithm = TotpAlgorithm.SHA1,
    val digits: Int = 6,
    val period: Int = 30,
    /** Creation time, epoch ms. */
    val createdAt: Long,
    /** Last time a code was copied or filled, epoch ms; null = never used. */
    val lastUsedAt: Long? = null
) {
    override fun toString(): String =
        "TotpEntry(id=$id, profileId=$profileId, issuer=$issuer, account=$account, " +
            "secret=REDACTED, algorithm=$algorithm, digits=$digits, period=$period, " +
            "createdAt=$createdAt, lastUsedAt=$lastUsedAt)"
}
