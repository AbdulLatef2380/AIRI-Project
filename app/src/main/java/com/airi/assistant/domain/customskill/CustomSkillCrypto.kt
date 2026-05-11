package com.airi.assistant.domain.customskill

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.airi.assistant.domain.logging.LoggingService
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.security.SecureRandom

object CustomSkillCrypto {

    private const val TAG = "CustomSkillCrypto"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "airi_skill_headers_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return (keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        }
        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        keyGen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return keyGen.generateKey()
    }

    fun encrypt(plaintext: String): String = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = ByteArray(GCM_IV_LENGTH)
        SecureRandom().nextBytes(iv)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(GCM_TAG_LENGTH, iv)
        )
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = iv + encrypted
        "enc:" + Base64.encodeToString(combined, Base64.NO_WRAP)
    }.getOrElse {
        LoggingService.warn(TAG, "Encryption failed, storing as-is")
        plaintext
    }

    fun decrypt(value: String): String {
        if (!value.startsWith("enc:")) return value
        return runCatching {
            val combined = Base64.decode(value.removePrefix("enc:"), Base64.NO_WRAP)
            val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
            val encrypted = combined.copyOfRange(GCM_IV_LENGTH, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        }.getOrElse {
            LoggingService.warn(TAG, "Decryption failed, returning raw value")
            value
        }
    }

    fun encryptSensitiveHeaders(headers: Map<String, String>): Map<String, String> =
        headers.mapValues { (key, value) ->
            if (CustomSkillSecurity.isSensitiveName(key)) encrypt(value) else value
        }

    fun decryptSensitiveHeaders(headers: Map<String, String>): Map<String, String> =
        headers.mapValues { (key, value) ->
            if (CustomSkillSecurity.isSensitiveName(key)) decrypt(value) else value
        }
}
