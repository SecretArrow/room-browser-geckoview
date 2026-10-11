package com.roombrowser.agent

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AndroidKeyStore AES-256-GCM encryption for provider API keys.
 *
 * The key never leaves secure hardware-backed storage and is generated on
 * first use. Ciphertext format: base64(iv[12] || ciphertext+tag).
 * All operations are wrapped defensively — a KeyStore failure degrades to
 * "key unavailable" (null) instead of crashing the browser.
 */
object KeyStoreCrypto {

    private const val PROVIDER = "AndroidKeyStore"
    private const val MASTER_ALIAS = "room_agent_master_key"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    @Synchronized
    private fun masterKey(): SecretKey? = try {
            val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
            val existing = (ks.getEntry(MASTER_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
            if (existing != null) {
                existing
            } else {
                val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
                generator.init(
                    KeyGenParameterSpec.Builder(
                        MASTER_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
                generator.generateKey()
            }
        } catch (_: Throwable) {
            null
        }

    /** @return base64(iv||ciphertext) or null when encryption is unavailable. */
    fun encrypt(plain: String): String? {
        return try {
            val key = masterKey() ?: return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
        } catch (_: Throwable) {
            null
        }
    }

    /** @return the plaintext, or null when the blob is unreadable. */
    fun decrypt(encoded: String): String? {
        return try {
            val key = masterKey() ?: return null
            val all = Base64.decode(encoded, Base64.NO_WRAP)
            if (all.size <= IV_LEN) return null
            val iv = all.copyOfRange(0, IV_LEN)
            val cipherText = all.copyOfRange(IV_LEN, all.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (_: Throwable) {
            null
        }
    }
}
