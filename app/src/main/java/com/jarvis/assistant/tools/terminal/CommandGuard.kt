package com.jarvis.assistant.tools.terminal

/**
 * Looks at a shell command before it runs and decides how careful to be. It is a safety net that makes the user
 * confirm risky commands; it is not a sandbox (the isolated workspace, the scrubbed environment and the permission
 * prompt are the real boundaries). When in doubt it chooses the more cautious answer.
 *
 * SAFE        - ordinary build/test/file work inside the workspace. Normal "run programs" permission applies.
 * DESTRUCTIVE - deletes or overwrites data, touches paths outside the workspace, installs software globally,
 *               pushes to a remote, pipes downloads into a shell, or hides what it runs. Always asks the user.
 * BLOCKED     - never runs: fork bombs, wiping the root or home, writing to /system or other apps' data, raw disk writes.
 */
class CommandGuard(private val workspaceRoot: String? = null) {

    enum class Verdict { SAFE, DESTRUCTIVE, BLOCKED }

    data class Result(val verdict: Verdict, val reasons: List<String>) {
        val isSafe: Boolean get() = verdict == Verdict.SAFE
        val summary: String get() = reasons.joinToString("; ")
    }

    fun classify(command: String): Result {
        val blocked = ArrayList<String>()
        val risky = ArrayList<String>()
        scan(command, blocked, risky, 0)
        return when {
            blocked.isNotEmpty() -> Result(Verdict.BLOCKED, blocked.distinct())
            risky.isNotEmpty() -> Result(Verdict.DESTRUCTIVE, risky.distinct())
            else -> Result(Verdict.SAFE, emptyList())
        }
    }

    private fun scan(command: String, blocked: MutableList<String>, risky: MutableList<String>, depth: Int) {
        if (depth > 4) { risky += "deeply nested command"; return }
        val squashed = command.filter { !it.isWhitespace() }
        if (squashed.contains(":(){:|:&};:") || FORK_BOMB.containsMatchIn(squashed)) blocked += "fork bomb"
        if (command.contains('\u0000')) risky += "contains a NUL byte"

        // Anything run from inside $( ) or backticks is classified as a command of its own.
        for (inner in substitutions(command)) {
            scan(inner, blocked, risky, depth + 1)
            risky += "runs a command hidden inside \$( ) or backticks"
        }

        val segments = ShellWords.segments(command)
        for ((index, seg) in segments.withIndex()) {
            val words = dropKeywords(ShellWords.words(stripAssignments(seg)))
            if (words.isEmpty()) continue
            checkSegment(seg, words, blocked, risky, depth)
            // A pipe into an interpreter runs whatever came down the pipe.
            if (index > 0 && isPipedInto(command, seg) && bare(words[0]) in INTERPRETERS) risky += "pipes data into ${bare(words[0])} (runs it as code)"
        }
        // Belt and braces for "curl ... | sh": look at the raw text too, in case the segment splitter lost the pipe.
        if (PIPE_TO_INTERPRETER.containsMatchIn(command)) risky += "pipes data into a shell or interpreter (runs it as code)"
        checkRedirects(command, blocked, risky)
        checkPaths(command, blocked, risky)
    }

    private fun checkSegment(raw: String, words: List<String>, blocked: MutableList<String>, risky: MutableList<String>, depth: Int) {
        var idx = 0
        var cmd = bare(words[0])
        // Wrappers that run the next word as a command.
        while (cmd in WRAPPERS && idx + 1 < words.size) {
            if (cmd == "sudo" || cmd == "su" || cmd == "doas") risky += "uses $cmd (elevated rights)"
            idx++
            while (idx < words.size && (words[idx].startsWith("-") || words[idx].contains('=') && !words[idx].startsWith("/"))) idx++
            if (idx >= words.size) return
            cmd = bare(words[idx])
        }
        if (cmd == "sudo" || cmd == "su" || cmd == "doas") risky += "uses $cmd (elevated rights)"
        val args = words.drop(idx + 1)
        val flags = args.filter { it.startsWith("-") }.joinToString(" ")

        if (cmd.startsWith("$") || cmd.contains("$(") || cmd.contains('`')) { risky += "the command name is built from a variable"; return }

        // mkfs.ext4, mkfs.vfat, ... are separate program names.
        if (cmd.startsWith("mkfs.")) { risky += "formats or partitions a disk ($cmd)"; if (args.any { it.startsWith("/dev/") }) blocked += "writes a raw disk device" }

        when (cmd) {
            "rm", "rmdir", "unlink", "shred", "srm" -> {
                risky += "deletes files ($cmd)"
                if (args.any { isRootOrHome(it) || it == "/*" || it == "~/*" || it == "\$HOME/*" } &&
                    (flags.contains("r") || flags.contains("R") || flags.contains("recursive") || flags.contains("f"))) blocked += "would wipe the root or home folder"
            }
            "mkfs", "mke2fs", "fdisk", "parted", "wipefs", "mkswap", "format" -> { risky += "formats or partitions a disk ($cmd)"; if (args.any { it.startsWith("/dev/") }) blocked += "writes a raw disk device" }
            "dd" -> { risky += "dd can overwrite data"; if (args.any { it.startsWith("of=/dev/") }) blocked += "writes a raw disk device" }
            "chmod", "chown", "chgrp" -> if (flags.contains("R") || flags.contains("recursive")) {
                risky += "changes permissions recursively"
                if (args.any { isRootOrHome(it) }) blocked += "recursive permission change on the root or home folder"
            }
            "shutdown", "reboot", "poweroff", "halt" -> risky += "shuts down or reboots the device"
            "init", "telinit" -> if (args.any { it == "0" || it == "6" }) risky += "shuts down or reboots the device"
            "kill", "killall", "pkill" -> {
                risky += "stops processes ($cmd)"
                if (args.any { it == "-1" || it == "0" } && flags.contains("9")) risky += "kills every process"
            }
            "mount", "umount", "insmod", "rmmod", "setenforce", "iptables", "ip6tables" -> risky += "changes the system ($cmd)"
            "pm", "am", "cmd", "settings", "svc", "wm", "input", "su-exec" -> risky += "controls Android itself ($cmd)"
            "eval" -> risky += "eval runs text built at run time"
            "source", "." -> risky += "sourcing a script runs it inside this shell"
            "crontab", "at", "nohup" -> if (cmd != "nohup") risky += "schedules a job ($cmd)"
            "find" -> {
                if (args.contains("-delete")) risky += "find -delete removes files"
                val ex = args.indexOfFirst { it == "-exec" || it == "-execdir" }
                if (ex >= 0 && ex + 1 < args.size && bare(args[ex + 1]) in setOf("rm", "rmdir", "shred", "mv", "dd")) risky += "find -exec ${bare(args[ex + 1])}"
            }
            "xargs" -> {
                val target = args.firstOrNull { !it.startsWith("-") }?.let { bare(it) }
                if (target != null && target in setOf("rm", "rmdir", "shred", "dd", "kill", "sh", "bash")) risky += "xargs runs $target"
            }
            "git" -> checkGit(args, risky)
            "npm", "pnpm", "yarn", "bun" -> {
                val g = args.any { it == "-g" || it == "--global" || it == "global" } 
                if (g && args.any { it in setOf("install", "i", "add", "uninstall", "remove", "global", "update", "upgrade") }) risky += "installs or removes a global package"
                if (args.firstOrNull() in setOf("publish", "unpublish", "deprecate", "adduser", "login", "token", "access")) risky += "publishes or changes registry data"
            }
            "pip", "pip3" -> {
                if (args.any { it == "--break-system-packages" || it == "--target=/" } || (args.firstOrNull() == "uninstall" )) risky += "changes system Python packages"
                if (args.contains("install") && args.any { it.startsWith("http") || it.startsWith("git+") }) risky += "installs a package from a URL"
            }
            "pkg", "apt", "apt-get", "apk", "dnf", "yum", "pacman", "brew" ->
                if (args.any { it in setOf("install", "add", "remove", "purge", "upgrade", "full-upgrade", "uninstall", "autoremove") }) risky += "installs or removes system software ($cmd)"
            "curl", "wget" -> {
                if (args.any { it == "-o" || it == "-O" || it == "--output" || it == "-P" } ) { /* downloads into the workspace are normal */ }
                if (args.any { it.startsWith("--upload") || it == "-T" || it == "-d" || it == "--data" || it == "--data-binary" || it == "-F" || it == "--form" || it == "--post-data" || it == "--post-file" })
                    risky += "sends data to a remote server ($cmd)"
                if (args.any { it.startsWith("file:") }) risky += "reads a local file through $cmd"
            }
            "scp", "rsync", "sftp", "ssh", "nc", "ncat", "netcat", "telnet", "socat" -> risky += "talks to another machine ($cmd)"
            "sh", "bash", "zsh", "dash", "ksh", "ash" -> {
                val c = args.indexOf("-c")
                if (c >= 0 && c + 1 < args.size) { scan(args[c + 1], blocked, risky, depth + 1); risky += "runs a nested shell command" }
                else if (args.isNotEmpty() && !args[0].startsWith("-")) risky += "runs a script file ($cmd ${args[0]})"
            }
            "python", "python3", "node", "nodejs", "perl", "ruby", "php", "lua", "deno" -> {
                val e = args.indexOfFirst { it == "-c" || it == "-e" || it == "--eval" || it == "-p" }
                if (e >= 0) risky += "runs inline $cmd code"
            }
            "base64" -> if (args.any { it == "-d" || it == "--decode" || it == "-D" }) risky += "decodes hidden data"
            "tee" -> if (args.any { isOutside(it) }) risky += "writes outside the workspace through tee"
            "mv", "cp", "ln", "install" -> if (args.any { isOutside(it) }) risky += "$cmd touches a path outside the workspace"
        }

        // A relative path that climbs out of the workspace is as much "outside" as an absolute one.
        if (args.any { it == ".." || it.startsWith("../") || it.contains("/../") || it.endsWith("/..") }) risky += "uses \"..\" to leave the current folder"
    }

    private fun checkGit(args: List<String>, risky: MutableList<String>) {
        val sub = args.firstOrNull { !it.startsWith("-") } ?: return
        val rest = args.dropWhile { it != sub }.drop(1)
        when (sub) {
            "push" -> risky += if (rest.any { it == "--force" || it == "-f" || it.startsWith("--force-with") || it == "--delete" || it == "-d" || it.startsWith("+") }) "git push --force or delete rewrites the remote" else "git push publishes your changes to a remote"
            "reset" -> if (rest.contains("--hard") || rest.contains("--merge") || rest.contains("--keep")) risky += "git reset --hard throws away local changes"
            "clean" -> risky += "git clean deletes untracked files"
            "checkout", "restore", "switch" -> if (rest.any { it == "--" || it == "." || it == "-f" || it == "--force" || it == "--discard-changes" }) risky += "git $sub can discard local changes"
            "branch" -> if (rest.any { it == "-D" || it == "--delete" || it == "-d" }) risky += "deletes a branch"
            "stash" -> if (rest.firstOrNull() in setOf("drop", "clear")) risky += "deletes stashed work"
            "rebase", "filter-branch", "filter-repo", "gc", "prune", "reflog" -> risky += "git $sub rewrites or removes history"
            "tag" -> if (rest.any { it == "-d" || it == "--delete" }) risky += "deletes a tag"
            "config" -> if (rest.any { it == "--global" || it == "--system" }) risky += "changes global git settings"
        }
    }

    private fun checkRedirects(command: String, blocked: MutableList<String>, risky: MutableList<String>) {
        for (m in REDIRECT.findAll(stripQuoted(command))) {
            val target = m.groupValues[2].trim('"', '\'')
            if (target.isEmpty() || target in SAFE_DEVICES || target.startsWith("&")) continue
            if (isSystemWrite(target)) blocked += "writes to a protected system location"
            else if (isOutside(target)) risky += "redirects output to a file outside the workspace"
        }
    }

    private fun checkPaths(command: String, blocked: MutableList<String>, risky: MutableList<String>) {
        // Every word, including words inside quotes, that names a real location outside the workspace.
        for (word in allWords(command)) {
            val w = word.trim()
            if (w.isEmpty()) continue
            val pathPart = w.substringAfter('=', w)
            val cand = if (pathPart.startsWith("/") || pathPart.startsWith("~") || pathPart.startsWith("\$HOME") || pathPart.startsWith("\${HOME}") || pathPart.startsWith("\$PREFIX")) pathPart else w
            if (isSystemWrite(cand) && WRITING_VERBS.containsMatchIn(command)) { blocked += "touches a protected system location (${shorten(cand)})"; continue }
            if (isOtherAppData(cand)) { blocked += "touches another app's private data (${shorten(cand)})"; continue }
            if (isOutside(cand)) risky += "uses a path outside the workspace (${shorten(cand)})"
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------

    /** "do rm x", "then git push", "( rm x )": the real command comes after the shell keyword or bracket. */
    private fun dropKeywords(words: List<String>): List<String> {
        var w = words
        while (w.isNotEmpty()) {
            val first = w[0]
            val trimmed = first.trimStart('(', '{', '!')
            w = when {
                first in KEYWORDS -> w.drop(1)
                trimmed.isEmpty() -> w.drop(1)
                trimmed != first -> listOf(trimmed) + w.drop(1)
                else -> return w
            }
        }
        return w
    }

    private fun isRootOrHome(p: String) = p == "/" || p == "~" || p == "~/" || p == "\$HOME" || p == "\${HOME}" || p == "/." || p == "/data" || p == "/sdcard" || p == "/storage"

    private fun isSystemWrite(p: String) = p == "/system" || p.startsWith("/system/") || p == "/vendor" || p.startsWith("/vendor/") || p == "/proc/sysrq-trigger" || p.startsWith("/boot") || p == "/dev/block" || p.startsWith("/dev/block/")

    private fun isOtherAppData(p: String): Boolean {
        val prefixes = listOf("/data/data/", "/data/user/", "/data/user_de/", "/data/app/", "/data/misc/", "/data/system/", "/sdcard/Android/data/", "/storage/emulated/0/Android/data/")
        if (prefixes.none { p.startsWith(it) }) return false
        return workspaceRoot == null || !(p == workspaceRoot || p.startsWith(workspaceRoot.trimEnd('/') + "/"))
    }

    private fun isOutside(p: String): Boolean {
        if (p.isEmpty()) return false
        if (p in SAFE_DEVICES) return false
        if (p.startsWith("~") || p.startsWith("\$HOME") || p.startsWith("\${HOME}") || p.startsWith("\$PREFIX") || p.startsWith("\${PREFIX}")) return true
        if (!p.startsWith("/")) return false
        val root = workspaceRoot?.trimEnd('/')
        if (root != null && (p == root || p.startsWith("$root/"))) return false
        return p == "/" || OUTSIDE_ROOTS.containsMatchIn(p)
    }

    private fun shorten(s: String) = if (s.length > 40) s.take(37) + "..." else s

    private fun bare(word: String): String = word.replace("\\", "").replace("'", "").replace("\"", "").substringAfterLast('/').lowercase()

    private fun stripAssignments(seg: String): String {
        var s = seg.trimStart()
        while (true) {
            val m = ASSIGN.find(s) ?: break
            s = s.substring(m.range.last + 1).trimStart()
        }
        return s
    }

    private fun isPipedInto(command: String, seg: String): Boolean {
        val i = command.indexOf(seg)
        if (i <= 0) return false
        val before = command.substring(0, i).trimEnd()
        return before.endsWith("|")
    }

    /** Text inside $( ) and `...`, at any depth, one level at a time (nested ones are found when the inner text is scanned). */
    private fun substitutions(command: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        var inSingle = false
        while (i < command.length) {
            val c = command[i]
            if (c == '\'') inSingle = !inSingle
            if (!inSingle) {
                if (c == '$' && i + 1 < command.length && command[i + 1] == '(' && !(i + 2 < command.length && command[i + 2] == '(')) {
                    var depth = 1; var j = i + 2
                    while (j < command.length && depth > 0) { if (command[j] == '(') depth++ else if (command[j] == ')') depth--; j++ }
                    out += command.substring(i + 2, maxOf(i + 2, j - 1)); i = j; continue
                }
                if (c == '`') {
                    val j = command.indexOf('`', i + 1)
                    if (j > i) { out += command.substring(i + 1, j); i = j + 1; continue }
                }
            }
            i++
        }
        return out
    }

    private fun allWords(command: String): List<String> {
        val out = ArrayList<String>()
        for (seg in ShellWords.segments(command)) for (w in ShellWords.words(seg)) {
            out += w
            // "-o/etc/x" and "--out=/etc/x" style attachments
            if (w.startsWith("-") && w.contains('/')) out += w.substring(w.indexOf('/'))
        }
        return out
    }

    private fun stripQuoted(command: String): String {
        val sb = StringBuilder(); var q = 0.toChar()
        for (c in command) {
            if (q != 0.toChar()) { if (c == q) q = 0.toChar(); sb.append(' ') }
            else if (c == '\'' || c == '"') { q = c; sb.append(' ') }
            else sb.append(c)
        }
        return sb.toString()
    }

    companion object {
        private val FORK_BOMB = Regex("""(\w+)\(\)\{\1\|\1&\};?\1""")
        private val ASSIGN = Regex("""^[A-Za-z_][A-Za-z0-9_]*=(?:"[^"]*"|'[^']*'|\S*)\s+""")
        private val REDIRECT = Regex("""(\d?>>?|&>>?)\s*(\S+)""")
        private val WRITING_VERBS = Regex("""\b(rm|mv|cp|dd|tee|chmod|chown|mount|ln|install|touch|mkdir|truncate)\b""")
        private val SAFE_DEVICES = setOf("/dev/null", "/dev/stdout", "/dev/stderr", "/dev/stdin", "/dev/zero", "/dev/tty", "/dev/urandom", "/dev/random")
        private val OUTSIDE_ROOTS = Regex("""^/(etc|usr|bin|sbin|lib|lib64|opt|var|root|home|sdcard|storage|data|system|proc|sys|dev|mnt|vendor|cache|tmp|Users|apex|product|odm|boot|run|srv|mnt)(/|$)""")
        private val KEYWORDS = setOf("do", "then", "else", "elif", "if", "while", "until", "time", "!", "{", "(", "}", ")", "done", "fi", "esac", "in")
        private val WRAPPERS = setOf("sudo", "doas", "su", "env", "nohup", "time", "command", "exec", "nice", "ionice", "timeout", "stdbuf", "setsid", "busybox", "toybox")
        private val PIPE_TO_INTERPRETER = Regex("""(?<!\|)\|(?!\|)\s*(?:sudo\s+|env\s+)?(?:\S*/)?(?:sh|bash|zsh|dash|ksh|ash|python3?|node|nodejs|perl|ruby|php)\b""")
        private val INTERPRETERS = setOf("sh", "bash", "zsh", "dash", "ksh", "ash", "python", "python3", "node", "nodejs", "perl", "ruby", "php", "lua", "deno")
    }
}
