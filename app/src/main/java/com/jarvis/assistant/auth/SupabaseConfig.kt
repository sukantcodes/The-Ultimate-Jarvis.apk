package com.jarvis.assistant.auth

import com.jarvis.assistant.BuildConfig

/**
 * Connection settings for the existing Supabase project "JARVIS-CLOUD_CLI".
 *
 * The URL is public. The publishable (client) key is injected at build time from the
 * SUPABASE_PUBLISHABLE_KEY Gradle property / environment variable (a GitHub Actions secret in CI).
 * A service_role or secret key must never be used in this app.
 */
object SupabaseConfig {
    val url: String get() = BuildConfig.SUPABASE_URL.trimEnd('/')
    val publishableKey: String get() = BuildConfig.SUPABASE_PUBLISHABLE_KEY

    /** False for local builds made without the key; guest mode still works. */
    val isConfigured: Boolean get() = publishableKey.isNotBlank()

    const val REDIRECT_SCHEME = "com.jarvis.assistant"
    const val REDIRECT_HOST = "auth-callback"

    /**
     * Deep link Supabase redirects to after Google sign-in, email confirmation and password reset,
     * e.g. com.jarvis.assistant://auth-callback/google. A wildcard allow-list entry for the path under
     * auth-callback (see docs/architecture/authentication.md) must exist in Supabase under
     * Authentication > URL Configuration. The wildcard is written out in the docs, not here, because a
     * slash-star-star sequence would open a nested comment in Kotlin.
     */
    fun redirectUri(flow: AuthFlow): String = "$REDIRECT_SCHEME://$REDIRECT_HOST/${flow.key}"
}
