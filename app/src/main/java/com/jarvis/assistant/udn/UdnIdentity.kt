package com.jarvis.assistant.udn

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import android.util.Log
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * Per-device cryptographic identity: an ECDSA P-256 key pair generated inside the Android Keystore
 * (StrongBox when the hardware has it). The private key is non-exportable; this device proves itself by signing
 * every request. The public key (X.509 SPKI) is what the server stores and verifies against.
 */
object UdnIdentity {
    private const val TAG = "UdnIdentity"
    private const val ALIAS = "jarvis_udn_device_key_v1"

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun hasKey(): Boolean = runCatching { keyStore().containsAlias(ALIAS) }.getOrDefault(false)

    @Synchronized
    fun ensureKey() {
        if (hasKey()) return
        try {
            generate(strongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        } catch (e: StrongBoxUnavailableException) {
            generate(strongBox = false)
        } catch (e: Exception) {
            // Some OEM keystores reject the StrongBox request with a generic ProviderException.
            Log.w(TAG, "StrongBox key generation failed, using the TEE-backed keystore")
            generate(strongBox = false)
        }
    }

    private fun generate(strongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .apply { if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true) }
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            initialize(spec)
            generateKeyPair()
        }
    }

    fun publicKeyB64(): String {
        ensureKey()
        val cert = keyStore().getCertificate(ALIAS) ?: error("Missing device certificate")
        return Base64.encodeToString(cert.publicKey.encoded, Base64.NO_WRAP)
    }

    /** Returns a base64 ASN.1 DER ECDSA signature; the server converts it for WebCrypto. */
    fun sign(message: String): String {
        ensureKey()
        val key = keyStore().getKey(ALIAS, null) as? PrivateKey ?: error("Missing device key")
        val sig = Signature.getInstance("SHA256withECDSA").apply {
            initSign(key)
            update(message.toByteArray(Charsets.UTF_8))
        }
        return Base64.encodeToString(sig.sign(), Base64.NO_WRAP)
    }

    /** Discards the identity. Used when the server says the device was revoked, so it must re-enroll with a fresh key. */
    @Synchronized
    fun reset() {
        runCatching { keyStore().deleteEntry(ALIAS) }
    }
}
