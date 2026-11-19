package com.jarvis.assistant.udn

import java.security.MessageDigest

/** Request-signing format shared with supabase/functions/udn-api/sigcrypto.ts. Pure Kotlin so it can be unit-tested. */
object UdnSigning {
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun canonical(action: String, deviceId: String, ts: String, nonce: String, bodySha256: String): String =
        listOf("UDN1", "POST", action, deviceId, ts, nonce, bodySha256).joinToString("\n")

    private const val NONCE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-"

    /** 24-character URL-safe random nonce (server accepts 16-64). */
    fun newNonce(random: java.security.SecureRandom = java.security.SecureRandom()): String =
        buildString { repeat(24) { append(NONCE_ALPHABET[random.nextInt(NONCE_ALPHABET.length)]) } }
}
