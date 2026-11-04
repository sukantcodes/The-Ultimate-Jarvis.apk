package com.jarvis.assistant.auth

enum class FieldIssue { REQUIRED, EMAIL_INVALID, TOO_SHORT, MISMATCH }

data class FormIssues(
    val email: FieldIssue? = null,
    val password: FieldIssue? = null,
    val confirm: FieldIssue? = null
) {
    val isValid: Boolean get() = email == null && password == null && confirm == null
}

/** Client-side form validation. Pure Kotlin (no android.util.Patterns) so it can be unit tested. */
object AuthValidators {
    /** Supabase's default minimum is 6; new passwords are held to a stricter 8 here. Logging in is never length-checked. */
    const val MIN_PASSWORD_LENGTH = 8

    private val EMAIL_REGEX =
        Regex("^[A-Za-z0-9._%+'-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}$")

    fun isValidEmail(email: String): Boolean {
        val trimmed = email.trim()
        return trimmed.length <= 254 && EMAIL_REGEX.matches(trimmed)
    }

    fun emailIssue(email: String): FieldIssue? = when {
        email.isBlank() -> FieldIssue.REQUIRED
        !isValidEmail(email) -> FieldIssue.EMAIL_INVALID
        else -> null
    }

    fun validate(email: String, password: String, confirm: String, register: Boolean): FormIssues {
        return FormIssues(
            email = emailIssue(email),
            password = passwordIssue(password, register),
            confirm = if (register) confirmIssue(password, confirm) else null
        )
    }

    /** For the "set a new password" step after a password-reset link. */
    fun validateNewPassword(password: String, confirm: String): FormIssues =
        FormIssues(password = passwordIssue(password, true), confirm = confirmIssue(password, confirm))

    private fun passwordIssue(password: String, enforceLength: Boolean): FieldIssue? = when {
        password.isEmpty() -> FieldIssue.REQUIRED
        enforceLength && password.length < MIN_PASSWORD_LENGTH -> FieldIssue.TOO_SHORT
        else -> null
    }

    private fun confirmIssue(password: String, confirm: String): FieldIssue? = when {
        confirm.isEmpty() -> FieldIssue.REQUIRED
        confirm != password -> FieldIssue.MISMATCH
        else -> null
    }
}
