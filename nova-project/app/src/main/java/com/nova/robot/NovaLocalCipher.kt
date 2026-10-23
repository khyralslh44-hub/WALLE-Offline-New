package com.nova.robot

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Authenticated encryption for sensitive Nova data stored in app-private preferences.
 * The AES key is generated and retained by Android Keystore and is never written to preferences.
 */
internal object NovaLocalCipher {
    private const val KEY_ALIAS = "nova_people_memory_aes_v1"
    private const val PREFIX = "enc:v1:"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    @Synchronized
    fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
        val payload = cipher.iv + encrypted
        return PREFIX + Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    /**
     * Returns plaintext for a legacy unencrypted value so callers can migrate it on the next write.
     * A value with our prefix that fails authentication returns null; it is never treated as plaintext.
     */
    @Synchronized
    fun decryptOrLegacy(value: String): String? {
        if (!value.startsWith(PREFIX)) return value
        return runCatching {
            val payload = Base64.decode(value.removePrefix(PREFIX), Base64.NO_WRAP)
            require(payload.size > IV_BYTES + 16) { "Encrypted memory payload is truncated." }
            val iv = payload.copyOfRange(0, IV_BYTES)
            val ciphertext = payload.copyOfRange(IV_BYTES, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
        }.getOrNull()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
