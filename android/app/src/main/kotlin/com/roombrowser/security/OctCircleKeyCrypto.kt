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
 * Per-profile `oct://` passphrase crypto: one AndroidKeyStore AES-256-GCM key
 * per profile, alias "roomoctra-<profile.safeSuffix>".
 *
 * A FOURTH key, deliberately not shared with [VaultCrypto] ("roomvault-"),
 * [WalletKeyCrypto] ("roomwallet-") or [TotpKeyCrypto] ("roomtotp-"): a reader
 * who has opened one sealed circle must not thereby hold a key to the
 * passwords or the wallet. Cost is one more [deleteKey] in the profile
 * cascade.
 *
 * This seals the PASSPHRASE, not circle content — the app never caches a
 * circle, only the one secret a reader would otherwise retype for every
 * sealed circle. Fails loudly: requiring the passphrase again is a nuisance,
 * silently losing it is a lockout. Never logs.
 */
object OctCircleKeyCrypto : VaultCryptor {

    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS_PREFIX = "roomoctra-"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    /** Full keystore alias of one profile's circle key. */
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
            throw VaultCryptoException("Circle passphrase encryption failed", e)
        }
    }

    override fun decrypt(profileKey: String, encoded: String): String {
        val key = keyFor(ALIAS_PREFIX + profileKey)
        return try {
            val all = Base64.decode(encoded, Base64.NO_WRAP)
            check(all.size > IV_LEN) { "Circle blob too short" }
            val iv = all.copyOfRange(0, IV_LEN)
            val ciphertext = all.copyOfRange(IV_LEN, all.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            throw VaultCryptoException("Circle blob unreadable", e)
        }
    }

    /** ProfileId-keyed convenience over the [VaultCryptor] methods. */
    fun encrypt(profileId: ProfileId, plaintext: String): String =
        encrypt(profileId.safeSuffix, plaintext)

    /** ProfileId-keyed convenience over the [VaultCryptor] methods. */
    fun decrypt(profileId: ProfileId, encoded: String): String =
        decrypt(profileId.safeSuffix, encoded)

    /**
     * Drops the profile's circle key (profile deletion cascade). Never throws
     * and is a no-op when the key is already gone.
     */
    fun deleteKey(profileId: ProfileId) {
        try {
            val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
            ks.deleteEntry(aliasFor(profileId))
        } catch (_: Throwable) {
            // Key cleanup must never abort a profile deletion.
        }
    }

    /** Loads — or lazily generates on first use — one profile's circle key. */
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
                    .build()
            )
            return generator.generateKey()
        } catch (e: Throwable) {
            throw VaultCryptoException("Circle key unavailable", e)
        }
    }
}
