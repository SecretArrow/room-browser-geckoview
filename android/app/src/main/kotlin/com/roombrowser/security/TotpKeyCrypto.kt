package com.roombrowser.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.roombrowser.domain.model.ProfileId
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Per-profile 2FA key crypto: one AndroidKeyStore AES-256-GCM key per profile,
 * alias "roomtotp-<profile.safeSuffix>".
 *
 * A THIRD key, deliberately not shared with [VaultCrypto] ("roomvault-") or
 * [WalletKeyCrypto] ("roomwallet-"): each subsystem seals its own secrets, so a
 * defect in credential code cannot read OTP seeds. Cost is one more
 * [deleteKey] in the profile cascade, which is trivial next to that.
 *
 * Fails loudly like the other two — a dropped seed is worse than a visible
 * error — and never logs. Only [deleteKey] swallows failures: it runs inside
 * profile deletion, which must not abort, and a key with no rows behind it is
 * inert (its ciphertext stays permanently undecryptable, the safe direction).
 *
 * Format: base64(iv[12] || ciphertext+tag). Room stores only that string, in
 * `totp_entries.secret_enc`.
 */
object TotpKeyCrypto : VaultCryptor {

    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS_PREFIX = "roomtotp-"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    /** Full keystore alias of one profile's 2FA key. */
    fun aliasFor(profileId: ProfileId): String = ALIAS_PREFIX + profileId.safeSuffix

    override fun encrypt(profileKey: String, plaintext: String): String {
        val key = keyFor(ALIAS_PREFIX + profileKey)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
        } catch (e: Exception) {
            throw VaultCryptoException("2FA encryption failed", e)
        }
    }

    override fun decrypt(profileKey: String, encoded: String): String {
        val key = keyFor(ALIAS_PREFIX + profileKey)
        return try {
            val all = Base64.decode(encoded, Base64.NO_WRAP)
            check(all.size > IV_LEN) { "2FA blob too short" }
            val iv = all.copyOfRange(0, IV_LEN)
            val ciphertext = all.copyOfRange(IV_LEN, all.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            throw VaultCryptoException("2FA blob unreadable", e)
        }
    }

    /** ProfileId-keyed convenience over the [VaultCryptor] methods. */
    fun encrypt(profileId: ProfileId, plaintext: String): String =
        encrypt(profileId.safeSuffix, plaintext)

    /** ProfileId-keyed convenience over the [VaultCryptor] methods. */
    fun decrypt(profileId: ProfileId, encoded: String): String =
        decrypt(profileId.safeSuffix, encoded)

    /**
     * Drops the profile's 2FA key (profile deletion cascade). Never throws and
     * is a no-op when the key is already gone.
     */
    fun deleteKey(profileId: ProfileId) {
        try {
            val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
            ks.deleteEntry(aliasFor(profileId))
        } catch (_: Throwable) {
            // Key cleanup must never abort a profile deletion.
        }
    }

    /** Loads — or lazily generates on first use — one profile's 2FA key. */
    @Synchronized
    private fun keyFor(alias: String): SecretKey {
        try {
            val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
            (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
            generator.init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    // StrongBox deliberately NOT required, as in the other two:
                    // it would make 2FA unavailable on the many devices without
                    // the hardware, and the TEE-backed Keystore is the guarantee
                    // the threat model needs.
                    .build()
            )
            return generator.generateKey()
        } catch (e: Throwable) {
            throw VaultCryptoException("2FA key unavailable", e)
        }
    }
}
