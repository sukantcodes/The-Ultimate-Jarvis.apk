package com.jarvis.assistant.tools.github

enum class Severity { LOW, MEDIUM, HIGH }

data class Finding(val severity: Severity, val rule: String, val file: String, val line: Int, val excerpt: String)

/** What a skill would need to be allowed to do, derived from what its files appear to do. */
data class RequestedPermissions(val network: Boolean, val runsPrograms: Boolean, val writesFiles: Boolean, val installsPackages: Boolean) {
    fun describe(): String = listOfNotNull(
        "use the internet".takeIf { network }, "run programs".takeIf { runsPrograms },
        "write files".takeIf { writesFiles }, "install packages".takeIf { installsPackages }
    ).ifEmpty { listOf("nothing beyond reading its own files") }.joinToString(", ")
}

/**
 * Reads a repository's scripts and manifests as text and flags what looks unsafe. It is a review aid, not proof of
 * safety: a clean scan does not make code trustworthy, which is why installing never runs anything and the user
 * still confirms. Findings are specific (file, line, excerpt) so the user can judge them.
 */
object SkillScanner {
    private class Rule(val severity: Severity, val name: String, val regex: Regex, val appliesTo: (String) -> Boolean = { true })

    private val SCRIPT_EXT = setOf("sh", "bash", "zsh", "py", "js", "mjs", "cjs", "ts", "rb", "pl", "php", "ps1", "bat", "cmd")
    private fun isScript(file: String) = file.substringAfterLast('.', "").lowercase() in SCRIPT_EXT || file.substringAfterLast('/').lowercase() in setOf("makefile", "dockerfile", "install", "setup")
    private fun isManifest(file: String) = file.substringAfterLast('/').lowercase() in setOf("package.json", "setup.py", "setup.cfg", "pyproject.toml", "requirements.txt", "makefile", "build.gradle", "build.gradle.kts")

    private val RULES = listOf(
        Rule(Severity.HIGH, "Downloads code and runs it immediately", Regex("""(curl|wget|fetch)\b[^\n|;]*\|\s*(sudo\s+)?(ba|z|da|k)?sh\b|\b(sh|bash)\s+<\(\s*(curl|wget)""")),
        Rule(Severity.HIGH, "Runs text built at run time (eval/exec of decoded or downloaded data)", Regex("""\beval\s*[\(\$"'`]|exec\s*\(\s*(base64|atob|requests\.get|urllib)|base64\s+(-d|--decode)[^\n]*\|\s*(ba)?sh|\batob\s*\(.*\)\s*\)""")),
        Rule(Severity.HIGH, "Deletes everything under the root or home folder", Regex("""\brm\s+(-[a-zA-Z]*\s+)*(/|~|[$]HOME|/\*|~/\*)(\s|$)""")),
        Rule(Severity.HIGH, "Reads SSH keys, tokens, browser data or password files", Regex("""(\.ssh/|id_rsa|id_ed25519|\.aws/credentials|\.netrc|\.git-credentials|\.npmrc|/etc/shadow|Login Data|cookies\.sqlite|keystore|\.gnupg)""", RegexOption.IGNORE_CASE)),
        Rule(Severity.HIGH, "Sends local data to a remote address", Regex("""(curl|wget)\b[^\n]*(-d|--data|--data-binary|-F|--upload-file|-T)\s+[^\n]*(@|\$\(|`)|requests\.post\([^)]*open\(|nc\s+[\w.-]+\s+\d+\s*<""")),
        Rule(Severity.HIGH, "Uses elevated rights", Regex("""\b(sudo|su\s+-|doas)\b|\bsetuid\b|chmod\s+[0-7]*[4-7][0-7]{3}\b""")),
        Rule(Severity.HIGH, "Opens a remote shell or reverse connection", Regex("""/dev/tcp/|\bnc\s+(-[a-z]*e|-e)|bash\s+-i\s*>&|socket\.socket\([^)]*\)[^\n]*connect|pty\.spawn""")),
        Rule(Severity.HIGH, "Disables security or hides what it does", Regex("""setenforce\s+0|\bhistory\s+-c\b|unset\s+HISTFILE|\bobfuscat|\\x[0-9a-f]{2}(\\x[0-9a-f]{2}){12,}""", RegexOption.IGNORE_CASE)),
        Rule(Severity.MEDIUM, "Gives everyone full permissions", Regex("""chmod\s+(-R\s+)?(0?777|a\+rwx)""")),
        Rule(Severity.MEDIUM, "Writes outside its own folder", Regex("""(>>?|tee(\s+-a)?|cp|mv|ln\s+-s|install)\s+[^\n|;]*(/system/|/data/data/|/sdcard/|/storage/|/etc/|~/|[$]HOME/|/usr/)""")),
        Rule(Severity.MEDIUM, "Installs a package that is not pinned to a version", Regex("""\b(pip3?|python3? -m pip)\s+install\s+(?!-r\b)(?![^\n]*==)[a-zA-Z]""") ) ,
        Rule(Severity.MEDIUM, "Installs packages globally", Regex("""npm\s+(i|install)\s+(-g|--global)|pip3?\s+install\s+[^\n]*--break-system-packages|gem\s+install\b|go\s+install\b""")),
        Rule(Severity.MEDIUM, "Runs a program from a downloaded location", Regex("""(chmod\s+\+x\s+[^\n]*&&\s*\./|\./[\w.-]+\s*$)""", RegexOption.MULTILINE), { isScript(it) }),
        Rule(Severity.MEDIUM, "Starts something that keeps running in the background", Regex("""\bnohup\b|\bsetsid\b|\bcrontab\b|\bat\s+now\b|systemctl\s+enable|launchctl""")),
        Rule(Severity.MEDIUM, "Runs code automatically when installed", Regex(""""(preinstall|postinstall|prepare|prepublish)"\s*:"""), { it.endsWith("package.json") }),
        Rule(Severity.LOW, "Contacts the internet", Regex("""\b(curl|wget|fetch|requests\.(get|post)|urllib|axios|XMLHttpRequest|http\.get)\b|https?://""")),
        Rule(Severity.LOW, "Runs other programs", Regex("""\b(subprocess|os\.system|child_process|Runtime\.getRuntime|ProcessBuilder|popen|execSync|spawn)\b""")),
        Rule(Severity.LOW, "Writes files", Regex("""\b(open\([^)]*['"]w|writeFile|fs\.write|File\([^)]*\)\.write|>\s*[\w./-]+\.(sh|py|js))""")),
    )

    fun scan(files: Map<String, String>): List<Finding> {
        val out = ArrayList<Finding>()
        for ((path, text) in files) {
            val markdown = path.endsWith(".md", true)
            if (!markdown && !isScript(path) && !isManifest(path) && !path.endsWith(".json", true)) continue
            var fence = false
            text.lineSequence().forEachIndexed { i, raw ->
                val line = raw.trim()
                if (markdown && line.startsWith("```")) { fence = !fence; return@forEachIndexed }
                if (line.isEmpty() || (line.startsWith("#") && !markdown && !line.startsWith("#!"))) return@forEachIndexed
                // In documentation only commands count (fenced blocks, "$ " lines, `inline code`); prose that merely mentions curl or sudo does not.
                val candidates: List<String> = if (!markdown) listOf(line) else when {
                    fence -> listOf(line)
                    line.startsWith("$ ") -> listOf(line.removePrefix("$ "))
                    else -> Regex("`([^`]+)`").findAll(line).map { it.groupValues[1] }.toList()
                }
                for (cand in candidates) for (rule in RULES) {
                    if (!rule.appliesTo(path)) continue
                    if (markdown && rule.severity != Severity.HIGH) continue
                    if (rule.regex.containsMatchIn(cand)) out += Finding(rule.severity, rule.name, path, i + 1, line.take(160))
                }
            }
        }
        // One finding per rule per file keeps the review readable; the first occurrence is shown.
        return out.groupBy { it.file to it.rule }.map { (_, v) -> v.first() }.sortedWith(compareByDescending<Finding> { it.severity }.thenBy { it.file }.thenBy { it.line })
    }

    fun permissions(files: Map<String, String>, findings: List<Finding>): RequestedPermissions {
        val all = files.values.joinToString("\n")
        return RequestedPermissions(
            network = findings.any { it.rule == "Contacts the internet" } || Regex("""https?://""").containsMatchIn(all),
            runsPrograms = findings.any { it.rule == "Runs other programs" || it.severity == Severity.HIGH && it.rule.startsWith("Runs") } || files.keys.any { isScript(it) },
            writesFiles = findings.any { it.rule == "Writes files" || it.rule == "Writes outside its own folder" },
            installsPackages = findings.any { it.rule.startsWith("Installs") } || files.keys.any { it.endsWith("package.json") || it.endsWith("requirements.txt") }
        )
    }

    fun highest(findings: List<Finding>): Severity? = findings.maxByOrNull { it.severity }?.severity
}
