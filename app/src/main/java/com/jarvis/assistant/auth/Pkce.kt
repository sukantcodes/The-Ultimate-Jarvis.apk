package com.jarvis.assistant.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * PKCE (RFC 7636) helper. The code verifier never leaves the device until the code exchange, so an app
 * that intercepts the redirect cannot redeem the authorization code.
 */
object Pkce {
    private val random = SecureRandom()

    /** 48 random bytes -> a 64-character base64url verifier (RFC allows 43-128). */
    fun newVerifier(): String {
        val bytes = ByteArray(48)
        random.nextBytes(bytes)
        return base64Url(bytes)
    }

    /** S256 challenge: base64url(SHA-256(verifier)) without padding. */
    fun challengeFor(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return base64Url(digest)
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
