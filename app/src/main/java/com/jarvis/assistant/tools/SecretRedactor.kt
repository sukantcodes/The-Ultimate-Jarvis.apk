package com.jarvis.assistant.tools

/**
 * Removes secrets from anything that leaves a tool: output shown to the user, text sent back to a model, the
 * audit log and generated files. Known secrets (the user's own keys and tokens) are matched exactly; common key
 * formats are matched by pattern, so a key that was never registered is still caught.
 */
class SecretRedactor {
    private val known = LinkedHashSet<String>()

    @Synchronized fun register(secret: String?) {
        val s = secret?.trim().orEmpty()
        if (s.length >= 6) known += s
    }

    @Synchronized fun forget(secret: String?) { known.remove(secret?.trim().orEmpty()) }

    fun redact(text: String): String {
        var out = text
        val snapshot = synchronized(this) { known.toList() }
        for (s in snapshot.sortedByDescending { it.length }) out = out.replace(s, MASK)
        for (p in PATTERNS) out = p.replace(out) { m -> if (m.groupValues.size > 1 && m.groupValues[1].isNotEmpty()) m.groupValues[1] + MASK else MASK }
        return out
    }

    companion object {
        const val MASK = "***"
        private val PATTERNS = listOf(
            Regex("""(?i)(authorization:\s*bearer\s+)[A-Za-z0-9._~+/=-]{8,}"""),
            Regex("""(?i)((?:api[_-]?key|access[_-]?token|secret|password|passwd|token)["']?\s*[:=]\s*["']?)[A-Za-z0-9._~+/=-]{8,}"""),
            Regex("""\bgh[pousr]_[A-Za-z0-9]{20,}\b"""),
            Regex("""\bgithub_pat_[A-Za-z0-9_]{20,}\b"""),
            Regex("""\bsk-[A-Za-z0-9_-]{16,}\b"""),
            Regex("""\bAIza[0-9A-Za-z_-]{30,}\b"""),
            Regex("""\bxox[abprs]-[A-Za-z0-9-]{10,}\b"""),
            Regex("""\bAKIA[0-9A-Z]{16}\b"""),
            Regex("""(?s)-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----""")
        )
    }
}
