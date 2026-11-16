package com.jarvis.assistant.tools.terminal

/** One thing a person can install from the terminal. */
data class Installable(
    val key: String,
    val title: String,
    val kind: Kind,
    /** Termux package name, npm package or pip package, depending on [kind]. */
    val target: String,
    val check: String,
    val description: String,
    val needs: List<String> = emptyList(),
    val note: String = ""
) {
    enum class Kind { PKG, NPM, PIP }
}

/** What the terminal should do with a typed line. */
sealed class PackagePlan {
    /** Text to show; nothing runs. */
    class Show(val text: String) : PackagePlan()
    /** A shell script to run in the Termux environment. [summary] is shown first. */
    class Run(val script: String, val summary: String) : PackagePlan()
    /** Switch the terminal engine. */
    class Engine(val termux: Boolean) : PackagePlan()
}

/**
 * Turns Termux-style commands typed in the Jarvis Terminal into real installs. `pkg install git`, `apt install nodejs`,
 * `install git node`, `jpkg ai` and similar are understood. Names that are not in the catalog are passed on as Termux
 * package names after a strict check, so anything Termux ships can be installed. No text from the person ever reaches
 * the shell unquoted.
 */
object PackageCommand {
    private val SAFE_NAME = Regex("""^[A-Za-z0-9@][A-Za-z0-9@/._+-]{0,99}$""")
    private val ENTRY = setOf("jpkg", "pkg", "apt", "apt-get", "install", "jinstall")

    val catalog: List<Installable> = listOf(
        // Languages and basics
        Installable("git", "Git", Installable.Kind.PKG, "git", "git", "Version control: clone, commit, push."),
        Installable("nodejs", "Node.js + npm", Installable.Kind.PKG, "nodejs", "node", "JavaScript runtime. npm and npx come with it."),
        Installable("python", "Python + pip", Installable.Kind.PKG, "python", "python", "Python 3 with pip."),
        Installable("openssh", "OpenSSH", Installable.Kind.PKG, "openssh", "ssh", "ssh and scp."),
        Installable("curl", "curl", Installable.Kind.PKG, "curl", "curl", "Download from the web."),
        Installable("wget", "wget", Installable.Kind.PKG, "wget", "wget", "Download files."),
        Installable("clang", "C / C++ compiler", Installable.Kind.PKG, "clang", "clang", "Compile C and C++."),
        Installable("make", "make", Installable.Kind.PKG, "make", "make", "Build tool."),
        Installable("cmake", "CMake", Installable.Kind.PKG, "cmake", "cmake", "Build system generator."),
        Installable("golang", "Go", Installable.Kind.PKG, "golang", "go", "Go language."),
        Installable("rust", "Rust", Installable.Kind.PKG, "rust", "rustc", "Rust and cargo."),
        Installable("ruby", "Ruby", Installable.Kind.PKG, "ruby", "ruby", "Ruby language."),
        Installable("php", "PHP", Installable.Kind.PKG, "php", "php", "PHP language."),
        Installable("openjdk-17", "Java 17", Installable.Kind.PKG, "openjdk-17", "java", "Java development kit."),
        Installable("gh", "GitHub CLI", Installable.Kind.PKG, "gh", "gh", "GitHub from the command line."),
        Installable("ffmpeg", "FFmpeg", Installable.Kind.PKG, "ffmpeg", "ffmpeg", "Convert audio and video."),
        Installable("imagemagick", "ImageMagick", Installable.Kind.PKG, "imagemagick", "magick", "Edit images from the command line."),
        Installable("jq", "jq", Installable.Kind.PKG, "jq", "jq", "Read and change JSON."),
        Installable("ripgrep", "ripgrep", Installable.Kind.PKG, "ripgrep", "rg", "Very fast text search."),
        Installable("vim", "Vim", Installable.Kind.PKG, "vim", "vim", "Text editor."),
        Installable("nano", "nano", Installable.Kind.PKG, "nano", "nano", "Simple text editor."),
        Installable("tmux", "tmux", Installable.Kind.PKG, "tmux", "tmux", "Several shells in one."),
        Installable("zip", "zip and unzip", Installable.Kind.PKG, "zip unzip", "zip", "Make and open zip files."),
        // AI model command-line tools
        Installable("ollama", "Ollama", Installable.Kind.PKG, "ollama", "ollama", "Run open AI models on the phone (ollama serve, then ollama run <model>).",
            note = "Needs a lot of free storage and RAM; small models only."),
        Installable("llama-cpp", "llama.cpp", Installable.Kind.PKG, "llama-cpp", "llama-cli", "Run GGUF models directly on the phone."),
        Installable("gemini", "Gemini CLI", Installable.Kind.NPM, "@google/gemini-cli", "gemini", "Google's Gemini AI in the terminal.", needs = listOf("nodejs")),
        Installable("claude", "Claude Code", Installable.Kind.NPM, "@anthropic-ai/claude-code", "claude", "Anthropic's coding agent in the terminal.", needs = listOf("nodejs"),
            note = "Needs an Anthropic account or API key. Some phones can not run it; the install says so if so."),
        Installable("codex", "OpenAI Codex CLI", Installable.Kind.NPM, "@openai/codex", "codex", "OpenAI's coding agent in the terminal.", needs = listOf("nodejs"),
            note = "Ships a native program that may not run on every phone."),
        Installable("qwen", "Qwen Code", Installable.Kind.NPM, "@qwen-code/qwen-code", "qwen", "Qwen's coding agent in the terminal.", needs = listOf("nodejs")),
        Installable("opencode", "opencode", Installable.Kind.NPM, "opencode-ai", "opencode", "Open-source coding agent.", needs = listOf("nodejs"),
            note = "Ships a native program that may not run on every phone."),
        Installable("aider", "Aider", Installable.Kind.PIP, "aider-chat", "aider", "AI pair programmer that edits your git repo.", needs = listOf("python", "clang", "git"),
            note = "Builds some parts on the phone, so it can take several minutes."),
        Installable("llm", "llm (Simon Willison)", Installable.Kind.PIP, "llm", "llm", "Talk to many AI models from the terminal.", needs = listOf("python"))
    )

    private val byKey: Map<String, Installable> by lazy {
        val m = HashMap<String, Installable>()
        for (i in catalog) m[i.key] = i
        val aliases = mapOf(
            "node" to "nodejs", "npm" to "nodejs", "npx" to "nodejs", "nodejs-lts" to "nodejs",
            "python3" to "python", "pip" to "python", "pip3" to "python",
            "ssh" to "openssh", "scp" to "openssh", "gcc" to "clang", "g++" to "clang", "c++" to "clang", "go" to "golang", "cargo" to "rust", "rustc" to "rust",
            "java" to "openjdk-17", "jdk" to "openjdk-17", "github-cli" to "gh", "magick" to "imagemagick", "convert" to "imagemagick", "rg" to "ripgrep", "unzip" to "zip",
            "gemini-cli" to "gemini", "claude-code" to "claude", "claudecode" to "claude", "codex-cli" to "codex", "qwen-code" to "qwen", "opencode-ai" to "opencode",
            "aider-chat" to "aider", "llamacpp" to "llama-cpp", "llama.cpp" to "llama-cpp", "llama" to "llama-cpp"
        )
        for ((a, k) in aliases) m[a] = m.getValue(k)
        m
    }

    fun find(name: String): Installable? = byKey[name.lowercase()]

    /** True when the first word of [line] is one of the install commands this class handles. */
    fun handles(line: String): Boolean {
        val first = line.trim().substringBefore(' ').lowercase()
        if (first !in ENTRY) return false
        // Only plain commands: a pipe, chain or redirect means the person wants the real shell.
        return line.none { it in ";&|<>`$\n" }
    }

    fun parse(line: String): PackagePlan? {
        if (!handles(line)) return null
        val words = line.trim().split(Regex("\\s+"))
        val first = words[0].lowercase()
        var args = words.drop(1).filter { it != "-y" && it != "--yes" && it != "-q" && it != "--assume-yes" }
        // "install git node" has no verb of its own.
        val verb = if (first == "install" || first == "jinstall") "install" else args.firstOrNull()?.lowercase() ?: "help"
        if (first != "install" && first != "jinstall" && args.isNotEmpty()) args = args.drop(1)
        return when (verb) {
            "install", "i", "add" -> install(args)
            "remove", "uninstall", "rm", "purge", "autoremove" -> remove(args)
            "update", "upgrade", "full-upgrade", "up" -> PackagePlan.Run("pkg update -y && pkg upgrade -y", "Updating every installed package")
            "list", "list-installed", "installed" -> PackagePlan.Run("pkg list-installed 2>/dev/null | head -n 200", "Installed packages")
            "search", "find" -> search(args)
            "info", "show" -> info(args)
            "ai", "models", "ai-tools" -> PackagePlan.Show(aiList())
            "tools", "catalog", "available" -> PackagePlan.Show(toolList())
            "engine" -> when (args.firstOrNull()?.lowercase()) {
                "termux" -> PackagePlan.Engine(true)
                "app", "local" -> PackagePlan.Engine(false)
                else -> PackagePlan.Show("Usage: jpkg engine termux   (real Linux tools)\n       jpkg engine app      (the app's own small shell)")
            }
            "setup", "help", "-h", "--help", "?" -> PackagePlan.Show(help())
            else -> PackagePlan.Show("Unknown command \"$verb\".\n\n" + help())
        }
    }

    private fun install(names: List<String>): PackagePlan {
        if (names.isEmpty()) return PackagePlan.Show("Say what to install, for example:  pkg install git nodejs\nSee everything with:  jpkg tools   or   jpkg ai")
        val bad = names.firstOrNull { !SAFE_NAME.matches(it) }
        if (bad != null) return PackagePlan.Show("\"$bad\" is not a valid package name.")
        val pkgs = LinkedHashSet<String>()
        val npm = LinkedHashSet<String>()
        val pip = LinkedHashSet<String>()
        val notes = ArrayList<String>()
        val titles = ArrayList<String>()
        fun add(i: Installable) {
            i.needs.forEach { n -> byKey[n]?.let { if (it.key != i.key) add(it) } }
            titles += i.title
            when (i.kind) {
                Installable.Kind.PKG -> pkgs += i.target.split(' ')
                Installable.Kind.NPM -> npm += i.target
                Installable.Kind.PIP -> pip += i.target
            }
            if (i.note.isNotBlank()) notes += "${i.title}: ${i.note}"
        }
        for (n in names) {
            val known = find(n)
            if (known != null) add(known) else { pkgs += n.lowercase(); titles += n }
        }
        val script = buildString {
            append("export DEBIAN_FRONTEND=noninteractive\n")
            if (pkgs.isNotEmpty()) append("pkg install -y ").append(pkgs.joinToString(" ") { ShellQuote.quote(it) }).append(" || exit \$?\n")
            if (npm.isNotEmpty()) append("npm install -g ").append(npm.joinToString(" ") { ShellQuote.quote(it) }).append(" || exit \$?\n")
            if (pip.isNotEmpty()) append("pip install --upgrade ").append(pip.joinToString(" ") { ShellQuote.quote(it) }).append(" || exit \$?\n")
            append("echo\necho \"Done. Installed: ${titles.distinct().joinToString(", ").replace("\"", "")}\"\n")
        }
        val summary = "Installing ${titles.distinct().joinToString(", ")}" + if (notes.isEmpty()) "" else "\n" + notes.joinToString("\n") { "ℹ $it" }
        return PackagePlan.Run(script, summary)
    }

    private fun remove(names: List<String>): PackagePlan {
        if (names.isEmpty()) return PackagePlan.Show("Say what to remove, for example:  pkg remove git")
        val bad = names.firstOrNull { !SAFE_NAME.matches(it) }
        if (bad != null) return PackagePlan.Show("\"$bad\" is not a valid package name.")
        val pkgs = ArrayList<String>(); val npm = ArrayList<String>(); val pip = ArrayList<String>()
        for (n in names) {
            val k = find(n)
            when (k?.kind) {
                Installable.Kind.NPM -> npm += k.target
                Installable.Kind.PIP -> pip += k.target
                Installable.Kind.PKG -> pkgs += k.target.split(' ')
                null -> pkgs += n.lowercase()
            }
        }
        val script = buildString {
            if (pkgs.isNotEmpty()) append("pkg uninstall -y ").append(pkgs.joinToString(" ") { ShellQuote.quote(it) }).append("\n")
            if (npm.isNotEmpty()) append("npm uninstall -g ").append(npm.joinToString(" ") { ShellQuote.quote(it) }).append("\n")
            if (pip.isNotEmpty()) append("pip uninstall -y ").append(pip.joinToString(" ") { ShellQuote.quote(it) }).append("\n")
        }
        return PackagePlan.Run(script, "Removing ${names.joinToString(", ")}")
    }

    private fun search(args: List<String>): PackagePlan {
        val q = args.joinToString(" ").trim()
        if (q.isEmpty()) return PackagePlan.Show("Say what to look for, for example:  pkg search python")
        if (!SAFE_NAME.matches(q.replace(' ', '-'))) return PackagePlan.Show("That search text has characters that are not allowed.")
        val local = catalog.filter { q.lowercase() in (it.key + " " + it.title + " " + it.description).lowercase() }
        val head = if (local.isEmpty()) "" else "Ready-made:\n" + local.joinToString("\n") { "  ${it.key.padEnd(12)} ${it.title} - ${it.description}" } + "\n\n"
        return PackagePlan.Run("printf '%s' ${ShellQuote.quote(head)}\npkg search ${ShellQuote.quote(q)} 2>&1 | head -n 60", "Searching for \"$q\"")
    }

    private fun info(args: List<String>): PackagePlan {
        val n = args.firstOrNull() ?: return PackagePlan.Show("Say which package, for example:  pkg show git")
        if (!SAFE_NAME.matches(n)) return PackagePlan.Show("\"$n\" is not a valid package name.")
        val k = find(n)
        val target = if (k?.kind == Installable.Kind.PKG) k.target.substringBefore(' ') else n.lowercase()
        return PackagePlan.Run("pkg show ${ShellQuote.quote(target)} 2>&1 | head -n 40", "About $n")
    }

    fun aiList(): String = "AI tools you can install (type: jpkg install <name>)\n" +
        catalog.filter { it.kind != Installable.Kind.PKG || it.key in setOf("ollama", "llama-cpp") }
            .joinToString("\n") { "  ${it.key.padEnd(10)} ${it.title} - ${it.description}" + if (it.note.isBlank()) "" else "\n             ℹ ${it.note}" }

    fun toolList(): String = "Ready-made tools (type: jpkg install <name>). Any other Termux package works too.\n" +
        catalog.filter { it.kind == Installable.Kind.PKG }.joinToString("\n") { "  ${it.key.padEnd(12)} ${it.title} - ${it.description}" } +
        "\n\nAI tools: jpkg ai"

    fun help(): String = """
        Install tools like in Termux:
          pkg install git nodejs python     install programs
          jpkg install gemini ollama         install AI tools (jpkg ai lists them)
          pkg remove git                     remove
          pkg update                         update everything
          pkg search <text>                  look for a package
          pkg list-installed                 what is installed
          jpkg tools                         ready-made list
          jpkg engine termux | app           choose where commands run
        Installing needs the Termux engine (real Linux). jpkg engine termux sets it up.
    """.trimIndent()

    /** Shown when an install is typed but commands are not running in Termux. */
    fun termuxRequiredText(installed: Boolean, spec: TermuxBridgeSpec = TermuxBridgeSpec()): String =
        if (installed) "Installing programs needs the Termux engine, which is off.\nType:  jpkg engine termux   to turn it on, then run this again."
        else "Installing programs needs Termux (a real Linux environment), which is not installed on this phone.\nOne-time setup:\n" +
            spec.userSetupSteps.mapIndexed { i, s -> "  ${i + 1}. $s" }.joinToString("\n") + "\nThen type:  jpkg engine termux"
}
