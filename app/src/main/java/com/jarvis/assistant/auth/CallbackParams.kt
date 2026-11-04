package com.jarvis.assistant.auth

import java.net.URLDecoder

/** Parsing and classification of the auth-callback deep link. Pure Kotlin so it can be unit tested. */
object CallbackParams {

    sealed class Kind {
        /** PKCE authorization code to exchange for a session. */
        data class Code(val code: String) : Kind()

        /** The provider or Supabase reported an error (for example the user declined Google consent). */
        data class Error(val code: String, val description: String) : Kind() {
            val cancelled: Boolean get() = code == "access_denied"
        }

        /**
         * Tokens delivered directly in the link (implicit flow). These are deliberately NOT accepted:
         * any app can fire a custom-scheme link, so only PKCE codes, which need our private verifier,
         * may create a session.
         */
        object ImplicitTokens : Kind()

        object Empty : Kind()
    }

    /** Merges query and fragment parameters, URL-decoding keys and values. */
    fun parse(encodedQuery: String?, encodedFragment: String?): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        addAll(out, encodedQuery)
        addAll(out, encodedFragment)
        return out
    }

    fun classify(params: Map<String, String>): Kind = when {
        params.containsKey("error") || params.containsKey("error_code") ->
            Kind.Error(
                code = params["error"] ?: params["error_code"].orEmpty(),
                description = params["error_description"].orEmpty()
            )
        !params["code"].isNullOrBlank() -> Kind.Code(params.getValue("code"))
        params.containsKey("access_token") || params.containsKey("refresh_token") -> Kind.ImplicitTokens
        else -> Kind.Empty
    }

    private fun addAll(out: MutableMap<String, String>, raw: String?) {
        if (raw.isNullOrEmpty()) return
        for (pair in raw.split('&')) {
            if (pair.isEmpty()) continue
            val idx = pair.indexOf('=')
            val key = if (idx >= 0) pair.substring(0, idx) else pair
            val value = if (idx >= 0) pair.substring(idx + 1) else ""
            out[decode(key)] = decode(value)
        }
    }

    private fun decode(s: String): String =
        try {
            URLDecoder.decode(s, "UTF-8")
        } catch (_: IllegalArgumentException) {
            s
        }
}
