package com.jarvis.assistant.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PkceTest {
    @Test
    fun `challenge matches the RFC 7636 appendix B test vector`() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challengeFor(verifier))
    }

    @Test
    fun `verifier is url-safe, within RFC length, and unique`() {
        val a = Pkce.newVerifier()
        val b = Pkce.newVerifier()
        assertTrue(a.length in 43..128)
        assertTrue(a.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertNotEquals(a, b)
    }
}

class CallbackParamsTest {
    private fun classify(q: String?, f: String? = null) = CallbackParams.classify(CallbackParams.parse(q, f))

    @Test
    fun `authorization code in the query is accepted`() {
        val kind = classify("code=abc123")
        assertEquals(CallbackParams.Kind.Code("abc123"), kind)
    }

    @Test
    fun `values are url-decoded and fragment is merged`() {
        val p = CallbackParams.parse("error_description=User+denied%20access", "error=access_denied")
        assertEquals("User denied access", p["error_description"])
        assertEquals("access_denied", p["error"])
    }

    @Test
    fun `access_denied is reported as cancelled`() {
        val kind = classify("error=access_denied&error_description=The+user+denied")
        assertTrue(kind is CallbackParams.Kind.Error)
        assertTrue((kind as CallbackParams.Kind.Error).cancelled)
    }

    @Test
    fun `other errors are not cancellations`() {
        val kind = classify(null, "error=server_error&error_description=boom") as CallbackParams.Kind.Error
        assertFalse(kind.cancelled)
        assertEquals("boom", kind.description)
    }

    @Test
    fun `tokens delivered in a link are never accepted as a session`() {
        assertEquals(CallbackParams.Kind.ImplicitTokens, classify(null, "access_token=x&refresh_token=y"))
    }

    @Test
    fun `an error wins over a code`() {
        assertTrue(classify("code=abc&error=access_denied") is CallbackParams.Kind.Error)
    }

    @Test
    fun `empty callback is classified as empty`() {
        assertEquals(CallbackParams.Kind.Empty, classify(null, null))
        assertEquals(CallbackParams.Kind.Empty, classify("", ""))
    }
}

class SupabaseResponseParserTest {
    private val now = 1_000L

    @Test
    fun `session response is parsed`() {
        val body = """{"access_token":"A","refresh_token":"R","expires_in":3600,"expires_at":5000,
            "user":{"id":"u1","email":"a@b.co"}}"""
        val s = SupabaseResponseParser.parseSession(body, now)!!
        assertEquals("A", s.accessToken)
        assertEquals("R", s.refreshToken)
        assertEquals(5000L, s.expiresAtEpochSec)
        assertEquals("u1", s.userId)
        assertEquals("a@b.co", s.email)
    }

    @Test
    fun `Google profile claims are read from provider identity data when user metadata is absent`() {
        val body = """{"access_token":"A","refresh_token":"R","expires_in":3600,
            "user":{"id":"google-user","email":"person@example.com",
            "identities":[{"provider":"google","identity_data":{
                "name":"Google Person","picture":"https://lh3.googleusercontent.com/avatar"
            }}]}}"""
        val session = SupabaseResponseParser.parseSession(body, now)!!
        assertEquals("Google Person", session.displayName)
        assertEquals("https://lh3.googleusercontent.com/avatar", session.avatarUrl)
    }

    @Test
    fun `authenticated profile endpoint parses metadata and provider identity fallbacks`() {
        val body = """{"id":"u-profile","user_metadata":{},
            "identities":[{"identity_data":{"full_name":"Profile Name",
            "avatar_url":"https://example.com/avatar.jpg"}}]}"""
        val profile = SupabaseResponseParser.parseProfile(body)!!
        assertEquals("u-profile", profile.userId)
        assertEquals("Profile Name", profile.displayName)
        assertEquals("https://example.com/avatar.jpg", profile.avatarUrl)
    }

    @Test
    fun `profile parser rejects non HTTPS avatar URLs`() {
        val body = """{"id":"u-profile","user_metadata":{
            "name":"Safe Name","avatar_url":"http://example.com/avatar.jpg"}}"""
        val profile = SupabaseResponseParser.parseProfile(body)!!
        assertEquals("Safe Name", profile.displayName)
        assertEquals("", profile.avatarUrl)
    }

    @Test
    fun `missing expires_at falls back to expires_in`() {
        val s = SupabaseResponseParser.parseSession("""{"access_token":"A","refresh_token":"R","expires_in":60}""", now)!!
        assertEquals(1060L, s.expiresAtEpochSec)
    }

    @Test
    fun `body without tokens is not a session`() {
        assertNull(SupabaseResponseParser.parseSession("""{"id":"u1","email":"a@b.co"}""", now))
        assertNull(SupabaseResponseParser.parseSession("not json", now))
    }

    @Test
    fun `sign-up with email confirmation off signs in`() {
        val body = """{"access_token":"A","refresh_token":"R","expires_in":3600,"user":{"id":"u","email":"a@b.co"}}"""
        assertTrue(SupabaseResponseParser.parseSignUp(body, now) is SignUpOutcome.SignedIn)
    }

    @Test
    fun `sign-up with email confirmation on requires confirmation`() {
        val body = """{"id":"u","email":"a@b.co","identities":[{"id":"i"}]}"""
        assertEquals(SignUpOutcome.ConfirmationRequired, SupabaseResponseParser.parseSignUp(body, now))
    }

    @Test
    fun `sign-up for an existing email is detected from empty identities`() {
        val body = """{"id":"u","email":"a@b.co","identities":[]}"""
        assertEquals(SignUpOutcome.AlreadyRegistered, SupabaseResponseParser.parseSignUp(body, now))
    }

    @Test
    fun `invalid credentials map to a friendly message`() {
        val f = SupabaseResponseParser.parseError(400, """{"error_code":"invalid_credentials","msg":"Invalid login credentials"}""")
        assertEquals(AuthError.INVALID_CREDENTIALS, f.error)
        assertEquals("Incorrect email or password.", f.message)
    }

    @Test
    fun `unconfirmed email is distinguished`() {
        val f = SupabaseResponseParser.parseError(400, """{"error_code":"email_not_confirmed","msg":"Email not confirmed"}""")
        assertEquals(AuthError.EMAIL_NOT_CONFIRMED, f.error)
    }

    @Test
    fun `existing user, weak password, signup disabled and rate limit are mapped`() {
        assertEquals(AuthError.ALREADY_REGISTERED,
            SupabaseResponseParser.parseError(422, """{"error_code":"user_already_exists","msg":"User already registered"}""").error)
        assertEquals(AuthError.WEAK_PASSWORD,
            SupabaseResponseParser.parseError(422, """{"error_code":"weak_password","msg":"Password should be at least 6 characters."}""").error)
        assertEquals(AuthError.SIGNUP_DISABLED,
            SupabaseResponseParser.parseError(422, """{"error_code":"signup_disabled","msg":"Signups not allowed"}""").error)
        assertEquals(AuthError.RATE_LIMITED,
            SupabaseResponseParser.parseError(429, """{"error_code":"over_email_send_rate_limit","msg":"rate limit"}""").error)
    }

    @Test
    fun `a rejected refresh token is a dead session`() {
        val f = SupabaseResponseParser.parseError(400, """{"error_code":"refresh_token_not_found","message":"Invalid Refresh Token"}""")
        assertEquals(AuthError.SESSION_INVALID, f.error)
    }

    @Test
    fun `unparseable error bodies still yield a usable message`() {
        val f = SupabaseResponseParser.parseError(502, "<html>bad gateway</html>")
        assertEquals(AuthError.UNKNOWN, f.error)
        assertTrue(f.message.isNotBlank())
        assertEquals(502, f.httpCode)
    }

    @Test
    fun `error messages never echo secrets from the response`() {
        val f = SupabaseResponseParser.parseError(400, """{"error_code":"invalid_credentials","msg":"x","access_token":"SECRET"}""")
        assertFalse(f.message.contains("SECRET"))
    }
}

class AuthValidatorsTest {
    @Test
    fun `email validation`() {
        assertTrue(AuthValidators.isValidEmail("user@example.com"))
        assertTrue(AuthValidators.isValidEmail("  first.last+tag@mail.example.co.in "))
        assertFalse(AuthValidators.isValidEmail("user@"))
        assertFalse(AuthValidators.isValidEmail("user@example"))
        assertFalse(AuthValidators.isValidEmail("@example.com"))
        assertFalse(AuthValidators.isValidEmail("us er@example.com"))
        assertFalse(AuthValidators.isValidEmail(""))
    }

    @Test
    fun `login requires fields but never length-checks the password`() {
        val ok = AuthValidators.validate("a@b.co", "x", "", register = false)
        assertTrue(ok.isValid)
        val empty = AuthValidators.validate("", "", "", register = false)
        assertEquals(FieldIssue.REQUIRED, empty.email)
        assertEquals(FieldIssue.REQUIRED, empty.password)
        assertNull(empty.confirm)
    }

    @Test
    fun `registration enforces length and matching confirmation`() {
        assertEquals(FieldIssue.TOO_SHORT, AuthValidators.validate("a@b.co", "short", "short", true).password)
        assertEquals(FieldIssue.MISMATCH, AuthValidators.validate("a@b.co", "longenough1", "different1", true).confirm)
        assertEquals(FieldIssue.REQUIRED, AuthValidators.validate("a@b.co", "longenough1", "", true).confirm)
        assertTrue(AuthValidators.validate("a@b.co", "longenough1", "longenough1", true).isValid)
        assertEquals(FieldIssue.EMAIL_INVALID, AuthValidators.validate("nope", "longenough1", "longenough1", true).email)
    }

    @Test
    fun `new password step validates password and confirmation only`() {
        assertTrue(AuthValidators.validateNewPassword("longenough1", "longenough1").isValid)
        assertEquals(FieldIssue.MISMATCH, AuthValidators.validateNewPassword("longenough1", "x").confirm)
    }
}

class AuthSessionTest {
    private val s = AuthSession("a", "r", expiresAtEpochSec = 1_000L, userId = "u", email = "e@x.co")

    @Test
    fun `expiry uses a safety skew`() {
        assertFalse(s.isExpired(nowEpochSec = 900L))   // 100s left, skew 60
        assertTrue(s.isExpired(nowEpochSec = 945L))    // 55s left, inside skew
        assertTrue(s.isExpired(nowEpochSec = 2_000L))
    }

    @Test
    fun `flow keys round-trip and unknown keys are rejected`() {
        AuthFlow.values().forEach { assertEquals(it, AuthFlow.fromKey(it.key)) }
        assertNull(AuthFlow.fromKey("nonsense"))
        assertNull(AuthFlow.fromKey(null))
        assertNotNull(AuthFlow.fromKey("google"))
    }
}


class AuthTokenFreshnessTest {
    private val session = AuthSession("fresh-access-token", "refresh-token", 1_000L, userId = "user")

    @Test
    fun onlyNonExpiredSessionsExposeAnAccessToken() {
        assertEquals("fresh-access-token", AuthManager.freshAccessToken(session, 900L))
        assertNull(AuthManager.freshAccessToken(session, 945L))
        assertNull(AuthManager.freshAccessToken(null, 900L))
    }
}
