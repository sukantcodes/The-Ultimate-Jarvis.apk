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
    val note: String = "",
    /** When not blank, this tool can not run on Android/Termux right now: the text says why, and nothing is installed. */
    val unsupported: String = ""
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
    /** Shared start of every script: no prompts, colours on, and small helpers for headings and failures. */
    private val PRELUDE = """
        export DEBIAN_FRONTEND=noninteractive TERM=xterm-256color CLICOLOR_FORCE=1 FORCE_COLOR=1 NPM_CONFIG_COLOR=always
        step() { printf '\n\033[1;36m==> %s\033[0m\n' "§1"; }
        fail() { printf '\n\033[1;31m✖ %s\033[0m\n' "§1"; exit 1; }
        pk() { pkg install -y -o Dpkg::Options::=--force-confnew "§@"; }
        upg() { pkg upgrade -y -o Dpkg::Options::=--force-confnew; }
        # Fixes the usual reasons an install fails: a half-finished earlier install, a broken dependency state, stale lists
        # or a dead mirror, and packages that are out of step with each other (Termux does not support partial upgrades).
        heal() {
          step 'Repairing the package system'
          dpkg --configure -a --force-confnew 2>&1 | tail -n 5
          apt-get -f install -y -o Dpkg::Options::=--force-confnew 2>&1 | tail -n 5
          apt-get clean 2>/dev/null
          pkg update -y -o Dpkg::Options::=--force-confnew || pkg --check-mirror update -y -o Dpkg::Options::=--force-confnew
          upg
        }
        # One install with automatic recovery: try, repair and try again, then pick a different mirror and try once more.
        pk_safe() {
          pk "§@" && return 0
          echo; echo 'That did not work. Repairing and trying again...'
          heal
          pk "§@" && return 0
          echo; echo 'Still failing. Choosing a different mirror and trying once more...'
          pkg --check-mirror update -y -o Dpkg::Options::=--force-confnew
          pk "§@"
        }
        node_ok() { command -v node >/dev/null 2>&1 && node --version >/dev/null 2>&1 && command -v npm >/dev/null 2>&1 && npm --version >/dev/null 2>&1; }
        # Installs Node.js and proves it starts (a mismatched library makes it install fine but die on launch).
        # §1 is the Termux package to try first (nodejs = newest, nodejs-lts = long-term support); the other one is the fallback.
        install_node() {
          local first="§1" second
          if [ "§first" = nodejs ]; then second=nodejs-lts; else second=nodejs; fi
          step "Installing Node.js (§first)"
          pk_safe "§first" || true
          if ! node_ok && command -v node >/dev/null 2>&1; then
            echo; echo 'Node.js is installed but does not start. Details:'; node --version 2>&1 | head -n 4
            heal
            pk --reinstall "§first" || true
          fi
          if ! node_ok; then
            step "Trying §second instead"
            pk_safe "§second" || true
          fi
          node_ok || fail 'Node.js could not be installed. The messages above say why. Try:  pkg upgrade   and then run this again.'
          local used=nodejs; dpkg -s nodejs-lts >/dev/null 2>&1 && used=nodejs-lts
          printf 'Node %s, npm %s (Termux package: %s)\n' "§(node --version)" "§(npm --version)" "§used"
        }
        # glibc lets Termux run standard Linux programs (needed by some AI tools). It lives in a separate repository.
        ensure_glibc() {
          local ld="§{PREFIX:-/data/data/com.termux/files/usr}/glibc/lib/ld-linux-aarch64.so.1"
          [ -e "§ld" ] && return 0
          [ "§(uname -m)" = aarch64 ] || fail 'This tool needs a 64-bit ARM phone (aarch64).'
          step 'Installing the glibc runtime (lets Termux run standard Linux programs)'
          pk_safe glibc-repo || fail 'Could not add the glibc repository.'
          pkg update -y -o Dpkg::Options::=--force-confnew || true
          pk_safe glibc || fail 'Could not install glibc.'
          [ -e "§ld" ] || fail 'glibc was installed but its loader is missing.'
        }
        # For npm tools: install Node.js (the stable LTS build) only if there is no working one yet.
        ensure_node() { node_ok && return 0; install_node nodejs-lts; }
    """.trimIndent().replace('§', '$') + "\n"

    private val SAFE_NAME = Regex("""^[A-Za-z0-9@][A-Za-z0-9@/._+-]{0,99}$""")
    private val ENTRY = setOf("jpkg", "pkg", "apt", "apt-get", "install", "jinstall")

    val catalog: List<Installable> = listOf(
        // Languages and basics
        Installable("git", "Git", Installable.Kind.PKG, "git", "git", "Version control: clone, commit, push."),
        Installable("nodejs", "Node.js + npm", Installable.Kind.PKG, "nodejs", "node", "Newest JavaScript runtime. npm and npx come with it. For the most stable choice use nodejs-lts."),
        Installable("nodejs-lts", "Node.js LTS + npm", Installable.Kind.PKG, "nodejs-lts", "node", "Long-term-support JavaScript runtime: the safest choice for npm tools. Replaces nodejs if that is installed."),
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
        Installable("agy", "Antigravity CLI (agy)", Installable.Kind.NPM, "@bash0816/agy-termux", "agy", "Google's Antigravity coding agent in the terminal; the successor to Gemini CLI.",
            needs = listOf("nodejs", "glibc"),
            note = "Community port for Termux (not made by Google): it downloads Google's own agy program and adjusts a local copy so it runs on Android. " +
                "The first run asks you to accept Google's terms (antigravity.google/terms) and that adjustment, so type  agy  once in the Termux app itself; the question needs a real terminal. 64-bit ARM phones only."),
        Installable("gemini", "Gemini CLI", Installable.Kind.NPM, "@google/gemini-cli", "gemini", "Google's Gemini AI in the terminal.", needs = listOf("nodejs"),
            note = "Google is moving people to Antigravity CLI (jpkg install agy). This package is still published and, being plain JavaScript, runs on Termux."),
        Installable("codex", "OpenAI Codex CLI", Installable.Kind.PKG, "codex", "codex", "OpenAI's coding agent in the terminal (Termux's own build).",
            note = "Installed from Termux's own package, which is built for Android. Sign in with  codex  in the Termux app."),
        Installable("claude", "Claude Code", Installable.Kind.NPM, "@anthropic-ai/claude-code", "claude", "Anthropic's coding agent in the terminal.", needs = listOf("nodejs"),
            unsupported = "Claude Code can not be installed directly in Termux right now. Its npm package delivers a native Linux program, and the Android build it looks for " +
                "(claude-code-linux-arm64-android) is not published, so the install always ends with a stub that does not run. " +
                "Use codex or agy instead (jpkg install codex / agy), or run Claude Code inside an Ubuntu environment (pkg install proot-distro)."),
        Installable("qwen", "Qwen Code", Installable.Kind.NPM, "@qwen-code/qwen-code", "qwen", "Qwen's coding agent in the terminal.", needs = listOf("nodejs")),
        Installable("opencode", "opencode", Installable.Kind.NPM, "opencode-ai", "opencode", "Open-source coding agent.", needs = listOf("nodejs"),
            unsupported = "opencode's npm package lists only macOS, Linux and Windows, so npm refuses to install it on Android (Termux reports its system as android). Use codex or agy instead."),
        Installable("aider", "Aider", Installable.Kind.PIP, "aider-chat", "aider", "AI pair programmer that edits your git repo.", needs = listOf("python", "clang", "git"),
            note = "Builds some parts on the phone, so it can take several minutes."),
        Installable("llm", "llm (Simon Willison)", Installable.Kind.PIP, "llm", "llm", "Talk to many AI models from the terminal.", needs = listOf("python")),
        // More programs
        Installable("neovim", "Neovim", Installable.Kind.PKG, "neovim", "nvim", "Modern Vim."),
        Installable("micro", "micro", Installable.Kind.PKG, "micro", "micro", "Easy terminal editor (Ctrl+S, Ctrl+Q)."),
        Installable("emacs", "Emacs", Installable.Kind.PKG, "emacs", "emacs", "Emacs editor."),
        Installable("htop", "htop", Installable.Kind.PKG, "htop", "htop", "Process viewer."),
        Installable("tree", "tree", Installable.Kind.PKG, "tree", "tree", "Show folders as a tree."),
        Installable("fzf", "fzf", Installable.Kind.PKG, "fzf", "fzf", "Fuzzy finder."),
        Installable("bat", "bat", Installable.Kind.PKG, "bat", "bat", "cat with colours."),
        Installable("fd", "fd", Installable.Kind.PKG, "fd", "fd", "Fast file finder."),
        Installable("eza", "eza", Installable.Kind.PKG, "eza", "eza", "Modern ls."),
        Installable("zsh", "Zsh", Installable.Kind.PKG, "zsh", "zsh", "Z shell."),
        Installable("fish", "Fish", Installable.Kind.PKG, "fish", "fish", "Friendly shell."),
        Installable("rsync", "rsync", Installable.Kind.PKG, "rsync", "rsync", "Copy and sync folders."),
        Installable("openssl", "OpenSSL", Installable.Kind.PKG, "openssl", "openssl", "Certificates and encryption."),
        Installable("nmap", "nmap", Installable.Kind.PKG, "nmap", "nmap", "Network scanner (your own network)."),
        Installable("dnsutils", "DNS tools", Installable.Kind.PKG, "dnsutils", "dig", "dig and nslookup."),
        Installable("netcat", "netcat", Installable.Kind.PKG, "netcat-openbsd", "nc", "Read and write network connections."),
        Installable("whois", "whois", Installable.Kind.PKG, "whois", "whois", "Domain lookup."),
        Installable("sqlite", "SQLite", Installable.Kind.PKG, "sqlite", "sqlite3", "Small SQL database."),
        Installable("postgresql", "PostgreSQL", Installable.Kind.PKG, "postgresql", "psql", "SQL database server."),
        Installable("mariadb", "MariaDB / MySQL", Installable.Kind.PKG, "mariadb", "mariadb", "SQL database server."),
        Installable("redis", "Redis", Installable.Kind.PKG, "redis", "redis-server", "In-memory database."),
        Installable("nginx", "nginx", Installable.Kind.PKG, "nginx", "nginx", "Web server."),
        Installable("pandoc", "Pandoc", Installable.Kind.PKG, "pandoc", "pandoc", "Convert documents (md, docx, pdf, html)."),
        Installable("lua", "Lua", Installable.Kind.PKG, "lua54", "lua", "Lua language."),
        Installable("perl", "Perl", Installable.Kind.PKG, "perl", "perl", "Perl language."),
        Installable("kotlin", "Kotlin", Installable.Kind.PKG, "kotlin", "kotlinc", "Kotlin compiler."),
        Installable("gradle", "Gradle", Installable.Kind.PKG, "gradle", "gradle", "Build Android and Java projects."),
        Installable("maven", "Maven", Installable.Kind.PKG, "maven", "mvn", "Java builds."),
        Installable("git-lfs", "Git LFS", Installable.Kind.PKG, "git-lfs", "git-lfs", "Large files in git."),
        Installable("tig", "tig", Installable.Kind.PKG, "tig", "tig", "Browse git history."),
        Installable("lazygit", "lazygit", Installable.Kind.PKG, "lazygit", "lazygit", "Git screen UI."),
        Installable("yt-dlp", "yt-dlp", Installable.Kind.PKG, "yt-dlp", "yt-dlp", "Download video and audio from websites."),
        Installable("aria2", "aria2", Installable.Kind.PKG, "aria2", "aria2c", "Fast multi-part downloader."),
        Installable("rclone", "rclone", Installable.Kind.PKG, "rclone", "rclone", "Sync with cloud drives."),
        Installable("tesseract", "Tesseract OCR", Installable.Kind.PKG, "tesseract", "tesseract", "Read text from images."),
        Installable("sox", "SoX", Installable.Kind.PKG, "sox", "sox", "Audio tool."),
        Installable("termux-api", "Termux:API tools", Installable.Kind.PKG, "termux-api", "termux-battery-status", "Phone features from the shell (needs the Termux:API app)."),
        Installable("proot-distro", "proot-distro", Installable.Kind.PKG, "proot-distro", "proot-distro", "Install Ubuntu, Debian or Alpine inside Termux."),
        Installable("binutils", "binutils", Installable.Kind.PKG, "binutils", "ld", "Linker and object tools."),
        Installable("python-numpy", "NumPy", Installable.Kind.PKG, "python-numpy", "python", "Numbers and arrays for Python."),
        Installable("python-pillow", "Pillow", Installable.Kind.PKG, "python-pillow", "python", "Images in Python."),
        Installable("tmate", "tmate", Installable.Kind.PKG, "tmate", "tmate", "Share a terminal over the web."),
        Installable("ncdu", "ncdu", Installable.Kind.PKG, "ncdu", "ncdu", "See what uses storage."),
        Installable("mc", "Midnight Commander", Installable.Kind.PKG, "mc", "mc", "File manager."),
        Installable("ranger", "ranger", Installable.Kind.PKG, "ranger", "ranger", "File manager."),
        Installable("screen", "screen", Installable.Kind.PKG, "screen", "screen", "Terminal sessions."),
        Installable("bc", "bc", Installable.Kind.PKG, "bc", "bc", "Calculator."),
        Installable("neofetch", "neofetch", Installable.Kind.PKG, "neofetch", "neofetch", "Show system info."),
        Installable("man", "man pages", Installable.Kind.PKG, "man", "man", "Manuals for commands."),
        Installable("lynx", "lynx", Installable.Kind.PKG, "lynx", "lynx", "Text web browser."),
        Installable("cowsay", "cowsay", Installable.Kind.PKG, "cowsay", "cowsay", "Fun speaking cow."),
        Installable("figlet", "figlet", Installable.Kind.PKG, "figlet", "figlet", "Big text banners."),
        Installable("android-tools", "Android tools (adb)", Installable.Kind.PKG, "android-tools", "adb", "adb and fastboot."),
        Installable("apksigner", "apksigner", Installable.Kind.PKG, "apksigner", "apksigner", "Sign APK files."),
        Installable("zsh-completions", "Zsh completions", Installable.Kind.PKG, "zsh-completions", "zsh", "Zsh tab completion."),
        // More JavaScript tools
        Installable("yarn", "Yarn", Installable.Kind.NPM, "yarn", "yarn", "JavaScript package manager.", needs = listOf("nodejs")),
        Installable("pnpm", "pnpm", Installable.Kind.NPM, "pnpm", "pnpm", "Fast JavaScript package manager.", needs = listOf("nodejs")),
        Installable("typescript", "TypeScript", Installable.Kind.NPM, "typescript", "tsc", "TypeScript compiler.", needs = listOf("nodejs")),
        Installable("tsx", "tsx", Installable.Kind.NPM, "tsx", "tsx", "Run TypeScript files directly.", needs = listOf("nodejs")),
        Installable("nodemon", "nodemon", Installable.Kind.NPM, "nodemon", "nodemon", "Restart node on changes.", needs = listOf("nodejs")),
        Installable("pm2", "pm2", Installable.Kind.NPM, "pm2", "pm2", "Keep node apps running.", needs = listOf("nodejs")),
        Installable("http-server", "http-server", Installable.Kind.NPM, "http-server", "http-server", "Serve a folder over HTTP.", needs = listOf("nodejs")),
        Installable("vercel", "Vercel CLI", Installable.Kind.NPM, "vercel", "vercel", "Deploy sites to Vercel.", needs = listOf("nodejs")),
        Installable("firebase", "Firebase CLI", Installable.Kind.NPM, "firebase-tools", "firebase", "Firebase from the terminal.", needs = listOf("nodejs")),
        Installable("netlify", "Netlify CLI", Installable.Kind.NPM, "netlify-cli", "netlify", "Deploy sites to Netlify.", needs = listOf("nodejs")),
        Installable("eas", "Expo EAS CLI", Installable.Kind.NPM, "eas-cli", "eas", "Build Expo apps.", needs = listOf("nodejs")),
        Installable("prettier", "Prettier", Installable.Kind.NPM, "prettier", "prettier", "Format code.", needs = listOf("nodejs")),
        Installable("eslint", "ESLint", Installable.Kind.NPM, "eslint", "eslint", "Check JavaScript.", needs = listOf("nodejs")),
        Installable("vite", "Vite", Installable.Kind.NPM, "vite", "vite", "Front-end build tool.", needs = listOf("nodejs")),
        Installable("create-vite", "create-vite", Installable.Kind.NPM, "create-vite", "create-vite", "Start a Vite project.", needs = listOf("nodejs")),
        Installable("wrangler", "Cloudflare Wrangler", Installable.Kind.NPM, "wrangler", "wrangler", "Cloudflare Workers CLI.", needs = listOf("nodejs")),
        // More AI command-line tools
        Installable("copilot", "GitHub Copilot CLI", Installable.Kind.NPM, "@github/copilot", "copilot", "GitHub's coding agent in the terminal.", needs = listOf("nodejs"),
            unsupported = "GitHub Copilot CLI starts a platform program that only exists for Linux, macOS and Windows; on Termux (Android) none is available, so it can not run."),
        Installable("kilo", "Kilo Code CLI", Installable.Kind.NPM, "@kilocode/cli", "kilo", "Open-source coding agent.", needs = listOf("nodejs"),
            unsupported = "Kilo Code CLI's npm package lists only macOS, Linux and Windows, so npm refuses to install it on Android. Use codex or agy instead."),
        Installable("crush", "Crush", Installable.Kind.NPM, "@charmland/crush", "crush", "Charm's AI coding agent.", needs = listOf("nodejs"),
            note = "Not tested on phones: its installer downloads a program that may not support Android. The install reports clearly if it fails."),
        Installable("continue", "Continue CLI", Installable.Kind.NPM, "@continuedev/cli", "cn", "Continue's coding agent.", needs = listOf("nodejs")),
        Installable("mcp-inspector", "MCP Inspector", Installable.Kind.NPM, "@modelcontextprotocol/inspector", "mcp-inspector", "Test MCP servers.", needs = listOf("nodejs")),
        Installable("repomix", "Repomix", Installable.Kind.NPM, "repomix", "repomix", "Pack a repo into one file for AI.", needs = listOf("nodejs")),
        Installable("sgpt", "Shell GPT", Installable.Kind.PIP, "shell-gpt", "sgpt", "Ask AI for shell commands.", needs = listOf("python")),
        Installable("ytdlp-pip", "yt-dlp (pip)", Installable.Kind.PIP, "yt-dlp", "yt-dlp", "Video downloader via pip.", needs = listOf("python")),
        Installable("httpie", "HTTPie", Installable.Kind.PIP, "httpie", "http", "Friendly HTTP client.", needs = listOf("python")),
        Installable("flask", "Flask", Installable.Kind.PIP, "flask", "flask", "Python web framework.", needs = listOf("python")),
        Installable("fastapi", "FastAPI + uvicorn", Installable.Kind.PIP, "fastapi uvicorn", "uvicorn", "Python API framework.", needs = listOf("python")),
        Installable("requests", "requests + bs4", Installable.Kind.PIP, "requests beautifulsoup4", "python", "Web scraping basics.", needs = listOf("python")),
        Installable("openai", "OpenAI Python", Installable.Kind.PIP, "openai", "python", "OpenAI library for Python.", needs = listOf("python")),
        Installable("anthropic", "Anthropic Python", Installable.Kind.PIP, "anthropic", "python", "Claude library for Python.", needs = listOf("python")),
        Installable("google-genai", "Google Gen AI Python", Installable.Kind.PIP, "google-genai", "python", "Gemini library for Python.", needs = listOf("python")),
        Installable("ollama-python", "Ollama Python", Installable.Kind.PIP, "ollama", "python", "Talk to Ollama from Python.", needs = listOf("python")),
        Installable("black", "Black", Installable.Kind.PIP, "black", "black", "Format Python code.", needs = listOf("python")),
        Installable("ruff", "Ruff", Installable.Kind.PIP, "ruff", "ruff", "Fast Python linter.", needs = listOf("python")),
        Installable("pytest", "pytest", Installable.Kind.PIP, "pytest", "pytest", "Python tests.", needs = listOf("python")),
        Installable("poetry", "Poetry", Installable.Kind.PIP, "poetry", "poetry", "Python project manager.", needs = listOf("python")),
        Installable("pipx", "pipx", Installable.Kind.PIP, "pipx", "pipx", "Install Python apps in isolation.", needs = listOf("python")),
        Installable("litellm", "LiteLLM", Installable.Kind.PIP, "litellm", "litellm", "One API for many AI models.", needs = listOf("python"))
    )

    private val AI_KEYS = setOf(
        "ollama", "llama-cpp", "agy", "gemini", "claude", "codex", "qwen", "opencode", "aider", "llm", "copilot", "kilo", "crush", "continue",
        "sgpt", "litellm", "openai", "anthropic", "google-genai", "ollama-python"
    )

    private val byKey: Map<String, Installable> by lazy {
        val m = HashMap<String, Installable>()
        for (i in catalog) m[i.key] = i
        val aliases = mapOf(
            "node" to "nodejs-lts", "npm" to "nodejs-lts", "npx" to "nodejs-lts", "nodejs-current" to "nodejs", "node-lts" to "nodejs-lts",
            "python3" to "python", "pip" to "python", "pip3" to "python",
            "ssh" to "openssh", "scp" to "openssh", "gcc" to "clang", "g++" to "clang", "c++" to "clang", "go" to "golang", "cargo" to "rust", "rustc" to "rust",
            "java" to "openjdk-17", "jdk" to "openjdk-17", "github-cli" to "gh", "magick" to "imagemagick", "convert" to "imagemagick", "rg" to "ripgrep", "unzip" to "zip",
            "gemini-cli" to "gemini", "claude-code" to "claude", "claudecode" to "claude", "codex-cli" to "codex", "qwen-code" to "qwen", "opencode-ai" to "opencode",
            "aider-chat" to "aider", "antigravity" to "agy", "antigravity-cli" to "agy", "agy-termux" to "agy", "llamacpp" to "llama-cpp", "llama.cpp" to "llama-cpp", "llama" to "llama-cpp"
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
            "update", "upgrade", "full-upgrade", "up" -> PackagePlan.Run(PRELUDE + "step 'Updating package lists'\npkg update -y -o Dpkg::Options::=--force-confnew\nstep 'Upgrading packages'\npkg upgrade -y -o Dpkg::Options::=--force-confnew || heal || fail 'Upgrade failed.'\n", "Updating every installed package")
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
        var node: String? = null      // Node.js asked for by name: "nodejs" or "nodejs-lts"
        var needNode = false          // Node.js only needed by another tool
        var needGlibc = false         // the glibc runtime, needed by some AI tools
        val skipped = ArrayList<Installable>()   // tools that can not run on Android right now
        val verify = ArrayList<Installable>()    // tools to check on the PATH at the end
        fun add(i: Installable) {
            if (i.unsupported.isNotBlank()) { if (skipped.none { it.key == i.key }) skipped += i; return }
            i.needs.forEach { n ->
                when (n) {
                    "nodejs" -> needNode = true
                    "glibc" -> needGlibc = true
                    else -> byKey[n]?.let { if (it.key != i.key) add(it) }
                }
            }
            titles += i.title
            if (verify.none { it.key == i.key }) verify += i
            when (i.kind) {
                Installable.Kind.PKG -> if (i.key == "nodejs" || i.key == "nodejs-lts") node = i.target else pkgs += i.target.split(' ')
                Installable.Kind.NPM -> npm += i.target
                Installable.Kind.PIP -> pip += i.target
            }
            if (i.note.isNotBlank()) notes += "${i.title}: ${i.note}"
        }
        for (n in names) {
            val known = find(n)
            if (known != null) add(known) else { pkgs += n.lowercase(); titles += n }
        }
        if (skipped.isNotEmpty() && pkgs.isEmpty() && npm.isEmpty() && pip.isEmpty() && node == null && !needNode && !needGlibc) {
            return PackagePlan.Show(skipped.joinToString("\n\n") { "✖ ${it.title}: ${it.unsupported}" })
        }
        val script = buildString {
            append(PRELUDE)
            for (k in skipped) {
                append("printf '\\n\\033[1;33m⚠ Skipped %s:\\033[0m %s\\n' ").append(ShellQuote.quote(k.title)).append(' ').append(ShellQuote.quote(k.unsupported)).append("\n")
            }
            if (pkgs.isNotEmpty() || node != null || needGlibc) {
                append("step 'Updating package lists'\n")
                append("pkg update -y -o Dpkg::Options::=--force-confnew || echo '(update reported warnings, continuing)'\n")
            }
            node?.let { append("install_node ").append(it).append("\n") }
            if (pkgs.isNotEmpty()) {
                val q = pkgs.joinToString(" ") { ShellQuote.quote(it) }
                append("step 'Installing programs: ").append(pkgs.joinToString(" ")).append("'\n")
                append("pk_safe ").append(q).append(" || fail 'Could not install: ").append(pkgs.joinToString(" "))
                append(". Check the name with: pkg search <name>'\n")
            }
            if (needGlibc) append("ensure_glibc\n")
            if (needNode && node == null) append("ensure_node\n")
            if (npm.isNotEmpty()) {
                val q = npm.joinToString(" ") { ShellQuote.quote(it) }
                append("step 'Installing npm tools: ").append(npm.joinToString(" ")).append("'\n")
                append("npm install -g --no-fund --no-audit ").append(q)
                append(" || { echo; echo 'Retrying after clearing the npm cache...'; npm cache clean --force >/dev/null 2>&1; npm install -g --no-fund --no-audit ").append(q)
                append(" || { echo; echo 'Retrying with --force...'; npm install -g --no-fund --no-audit --force ").append(q)
                append(" || fail 'npm could not install that tool on this phone.'; }; }\n")
            }
            if (pip.isNotEmpty()) {
                val q = pip.joinToString(" ") { ShellQuote.quote(it) }
                append("step 'Installing Python tools: ").append(pip.joinToString(" ")).append("'\n")
                append("pip install --upgrade --no-input ").append(q).append(" || { echo; echo 'Retrying...'; pip install --no-input --no-cache-dir ").append(q)
                append(" || fail 'pip could not install that tool on this phone.'; }\n")
            }
            if (verify.isNotEmpty()) {
                append("step 'Checking what was installed'\nhash -r\n")
                for (v in verify) {
                    val t = ShellQuote.quote(v.title); val c = ShellQuote.quote(v.check)
                    append("if command -v ").append(c).append(" >/dev/null 2>&1; then printf '\\033[1;32m✓\\033[0m %s is ready (type: %s)\\n' ").append(t).append(' ').append(c)
                    append("; else printf '\\033[1;33m⚠\\033[0m %s was installed but \"%s\" is not on the PATH yet. Try:  hash -r\\n' ").append(t).append(' ').append(c).append("; fi\n")
                }
            }
            append("printf '\\n\\033[1;32m✔ Done. Installed: ${titles.distinct().joinToString(", ").replace("'", "")}\\033[0m\\n'\n")
        }
        val summary = "Installing ${titles.distinct().joinToString(", ")}" + if (notes.isEmpty()) "" else "\n" + notes.joinToString("\n") { "ℹ $it" }
        return PackagePlan.Run(script, summary)
    }

    private fun remove(names: List<String>): PackagePlan {
        if (names.isEmpty()) return PackagePlan.Show("Say what to remove, for example:  pkg remove git")
        val bad = names.firstOrNull { !SAFE_NAME.matches(it) }
        if (bad != null) return PackagePlan.Show("\"$bad\" is not a valid package name.")
        val pkgs = LinkedHashSet<String>(); val npm = ArrayList<String>(); val pip = ArrayList<String>()
        for (n in names) {
            val k = find(n)
            when (k?.kind) {
                Installable.Kind.NPM -> npm += k.target
                Installable.Kind.PIP -> pip += k.target
                Installable.Kind.PKG -> if (k.key == "nodejs" || k.key == "nodejs-lts") { pkgs += "nodejs"; pkgs += "nodejs-lts" } else pkgs += k.target.split(' ')
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
        catalog.filter { it.key in AI_KEYS }
            .joinToString("\n") { "  ${it.key.padEnd(10)} ${it.title} - ${it.description}" + (if (it.unsupported.isNotBlank()) "\n             ✖ Not available on Android right now: type  jpkg install ${it.key}  for why." else "") + if (it.note.isBlank()) "" else "\n             ℹ ${it.note}" }

    fun toolList(): String = "Ready-made tools (type: jpkg install <name>). Any other Termux package works too.\n" +
        catalog.filter { it.key !in AI_KEYS }.joinToString("\n") { "  ${it.key.padEnd(12)} ${it.title} - ${it.description}" } +
        "\n\nAI tools: jpkg ai"

    fun help(): String = """
        Install tools like in Termux:
          pkg install git nodejs python     install programs (nodejs-lts = most stable Node)
          jpkg install gemini ollama         install AI tools (jpkg ai lists them)
          pkg remove git                     remove
          pkg update                         update everything
          pkg search <text>                  look for a package
          pkg list-installed                 what is installed
          jpkg tools                         ready-made list
          jpkg engine termux | app           choose where commands run
        Installs run in Termux (real Linux) and show live output here. Termux must be installed: jpkg setup explains the one-time steps.
    """.trimIndent()

    /** Shown when an install is typed but commands are not running in Termux. */
    fun termuxRequiredText(installed: Boolean, spec: TermuxBridgeSpec = TermuxBridgeSpec()): String =
        if (installed) "Installing programs needs Termux, but this app could not use it.\nRun  jpkg setup  and check the steps, then try again."
        else "Installing programs needs Termux (a real Linux environment), which is not installed on this phone.\nOne-time setup:\n" +
            spec.userSetupSteps.mapIndexed { i, s -> "  ${i + 1}. $s" }.joinToString("\n") + "\nThen type:  jpkg engine termux"
}
