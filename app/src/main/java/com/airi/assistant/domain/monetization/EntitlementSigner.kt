package com.airi.assistant.domain.monetization

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * B1: authenticates the locally cached entitlement so that editing SharedPreferences
 * (rooted device, debug tooling) cannot grant Premium.
 *
 * The signing key never leaves the Android Keystore, so a copied/edited preferences file
 * carries no valid signature. This raises the bar from "edit one string" to "attack the
 * Keystore"; real enforcement still lives on the server (see PlayPurchaseVerifier).
 */
interface EntitlementSigner {
    fun sign(payload: String): String
    fun verify(payload: String, signature: String): Boolean
}

class KeystoreEntitlementSigner(
    private val alias: String = "airi_entitlement_hmac_v1"
) : EntitlementSigner {

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN).build())
        return gen.generateKey()
    }

    private fun mac(payload: String): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(key())
            doFinal(payload.toByteArray(Charsets.UTF_8))
        }

    override fun sign(payload: String): String =
        Base64.encodeToString(mac(payload), Base64.NO_WRAP)

    override fun verify(payload: String, signature: String): Boolean = try {
        val expected = mac(payload)
        val given = Base64.decode(signature, Base64.NO_WRAP)
        java.security.MessageDigest.isEqual(expected, given)   // constant-time compare
    } catch (_: Exception) {
        false   // any Keystore/decoding failure ⇒ not entitled (fail closed)
    }
}
