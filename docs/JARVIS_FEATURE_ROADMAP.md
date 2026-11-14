# Jarvis AI Assistant — Feature Roadmap & Architecture Log

> **Last Updated:** November 11, 2026  
> **Branch:** `Antigravity`  
> **Status:** Current APK built & verified (Commit `e7b722e2`)

---

## 1. Accomplishments Completed in This Session

### 1.1 Single-Prompt Task Permissions
- **Problem Solved:** Repeated permission interruptions on each individual shell command, directory navigation, or file creation during terminal operations.
- **Solution:** 
  - Added `Decision.ALLOW_TASK` and task-scoped grant tracking in `Permissions.kt` and `ToolExecutor.kt`.
  - Primary dialog action changed to **"Allow (finish task)"**.
  - All subsequent non-destructive actions for that task run to completion without interruption.
  - Kept **"Always allow (session)"** option for session-wide auto-approvals.

### 1.2 Claude / Gemini-Style Code Artifact Cards & Interactive Viewer
- **Streaming Parser:** Built `CodeBlockParser.kt` that detects code blocks even mid-stream without closing fences.
- **Visual Artifact Card:** `layout_code_artifact_card.xml` with language icon, pulsing **`✦ Writing code…`** animation while generating, and 6-line preview.
- **Full Code Viewer:** `CodeViewerBottomSheet.kt` opens on card tap, displaying line-numbered monospaced code with 1-tap clipboard copying.

### 1.3 Live Token Counter & Token Usage Tiers (Low / High / Max)
- **Top Header Badge:** Displays real-time live tokens (`⚡ 142 TOKENS` / `⚡ 2.4K TOKENS`) in the Chatbox top bar.
- **Adaptive Thinking System Prompt:** Jarvis answers simple queries in 1–2 sentences without overthinking, while reserving deep reasoning for complex tasks and coding.
- **Work-First Token Allocation:** Explicit prompt mandate allocating tokens to code and tool execution rather than conversational filler.
- **Visual Premium Bar:** `TokenSettingsBottomSheet.kt` with an interactive tier selector:
  - **LOW (33% Capacity):** 1,500 token limit · Ultra-efficient concise answers.
  - **HIGH (66% Capacity, Default):** 4,096 token limit · Balanced thinking.
  - **MAX (100% Capacity, Premium):** 16,384 token limit · Gold/purple tier for heavy engineering tasks.

### 1.4 Voice-to-Text Input with Dynamic Waveform Animation
- **Animated Waveform:** Built `VoiceWaveView.kt` with audio-reactive oscillating bars reacting to `rmsdB` speech intensity.
- **Speech Integration:** Android `SpeechRecognizer` with live partial transcript display and automatic message send upon speech completion.

### 1.5 Autonomous Zero-Prompt Auto-Approve Permissions Mode (Premium UI)
- **Problem Solved:** Users who want completely autonomous execution without repeated confirmation prompts for file edits, scripts, terminal commands, or destructive actions.
- **Solution:**
  - Added global `PermissionPreferences.isAutoApproveAll()` and `policy.isAutoApproveAll()`.
  - In `ToolExecutor.kt`, auto-approve evaluates first to immediately grant unrestricted execution without ever invoking `confirmer().confirm(...)` or blocking the thread.
  - Built a cyber-styled **Premium UI Card** with glowing border, `★ UNRESTRICTED` badge, feature chips (Terminal, File Edits, Web, Destructive), and a smooth MaterialSwitch.
  - Integrated into both **Chatbox Settings** (`sheet_token_settings.xml` & `TokenSettingsBottomSheet.kt`), **API & Connection Settings** (`activity_api_connection.xml` & `ApiConnectionActivity.kt`), **Tool Settings** (`ToolSettingsActivity.kt`), and **Chatbox Tools** (`ChatboxTools.kt`).
  - Added live status feedback in the Chatbox top header (`Autonomous Workspace · ⚡ Auto-Approve Active`).

### 1.6 Claude-Style Work Animation, Mini-Terminal Window & Tri-Color Progress Bar
- **Problem Solved:** Cluttered raw text (`"⏳ write_file finished"`) during website/code generation, premature "Say continue" halts, and network/API timeout dropouts.
- **Solution:**
  - **Claude-style Live Work Animation:** Pulsing glowing badge (`✦ Creating index.html…` / `✦ Running terminal…` -> `✓ Created index.html` / `✓ Finished`).
  - **Inline Mini-Terminal / Code Window:** Dropdown tag `[▼ Code Window]` / `[▼ Mini Terminal]` that expands an authentic terminal window with macOS dots (🔴 🟡 🟢), line numbers, and live code preview.
  - **Tri-Color Header Progress Bar:** Positioned beside token count in the top header, calculating actual live work percentage with 3 adaptive theme colors:
    - 0% – 35%: Electric Cyan (`#00E5FF`) — Initializing & Planning
    - 36% – 75%: Cyber Purple/Blue (`#7C4DFF`) — Writing Code & Executing Scripts
    - 76% – 100%: Emerald Green (`#00E676`) / Gold (`#FFD700`) — Finalizing & Verifying
  - **Network & API Fault-Tolerance:** Enabled OkHttp connection failure auto-retry, extended timeouts to 120s, automatic 3-tier exponential backoff retries on transient socket/HTTP errors, increased tool loop limit to 50 rounds, and eliminated intermediate "Say continue" halts.
  - **Workspace Auto-Continuity:** Preserves all created files on disk across any interruption and resumes seamlessly.

### 1.7 Claude-Style Reactive Icon Animations for Every Work
- **Work Classification:** `WorkActionType.kt` dynamically identifies the executing action:
  - `FILE_CREATE`: Breathing scale + cyan aura pulse (`ic_work_file_create.xml`, `#00E5FF`).
  - `FILE_EDIT`: Writing tilt oscillation + amber glow (`ic_work_file_edit.xml`, `#FF9100`).
  - `TERMINAL`: Prompt blink cursor / heartbeat pulse (`ic_work_terminal.xml`, `#B388FF`).
  - `WEB_SEARCH`: Continuous 360° radar sweep (`ic_work_web_search.xml`, `#00E676`).
  - `FILE_READ`: Scanning horizontal translate bounce (`ic_work_file_read.xml`, `#40C4FF`).
  - `CODE_SYNTHESIS`: Cosmic sparkle orbit & scale (`ic_sparkle.xml`, `#80D8FF`).
  - `COMPLETED`: Snappy overshoot scale bounce into verified check (`ic_work_check.xml`, `#00E676`).
- **Hardware-Accelerated Animator:** `WorkIconAnimator.kt` runs distinct, smooth animations on the card icon frame `flCardIconContainer` and updates live labels.

### 1.8 Hands-Free Auto Voice Reply
- **Auto TTS on Voice Input:** Sending a query via the microphone triggers `lastMessageOriginatedFromVoice = true`. Upon reply completion, Jarvis speaks the conversational answer aloud hands-free.
- **Smart Speech Sanitization:** In `JarvisChatAdapter.kt`, code blocks, fences, and raw syntax are stripped before TTS reads, replacing them with natural spoken summaries.
- **Quick-Mute & Header Speaker Toggle:** Added `btnVoiceSpeaker` in the chat header with live state:
  - Glowing Cyan (`#00E5FF`): Auto Voice Reply Active.
  - Pulsing Gold (`#FFD700`): Currently reading response (1 tap stops immediately).
  - Muted Grey (`#78909C`): Voice muted.

### 1.9 Full Syntax Highlighting in Code Viewer & Themes
- **Native Spannable Engine:** `CodeSyntaxHighlighter.kt` applies high-performance token coloring for Kotlin, Python, JS/TS, HTML, CSS, Bash, and JSON.
- **Interactive Theme Switcher:** `sheet_code_viewer.xml` and `CodeViewerBottomSheet.kt` support 1-tap switching between:
  - **Cyber Dark+**: Futuristic neon cyan, violet, emerald, and gold.
  - **Dracula**: Classic purple, pink, green, and cyan.
  - **Monokai Neon**: High-contrast magenta, lime green, and electric blue.

### 1.10 Camera & Screenshot Quick Capture
- **Direct Camera Snapshot:** Added `btnCamera` to the chat composer row. 1-tap captures photos with `TakePicturePreview()`, automatically saved to app storage and attached for multimodal vision analysis.
- **Unified Attachment Picker:** `btnAttach` opens a clean 3-option sheet for Camera, Screenshot/Gallery Image, or Files & Documents.

### 1.11 Multi-Format Export to Markdown & PDF
- **Dedicated Exporter:** `ChatExporter.kt` formats transcripts into:
  - **Markdown (.md):** GitHub-flavored markdown with timestamps, attachment links, and syntax-tagged code fences.
  - **PDF (.pdf):** Standard vector PDF with titles, metadata, styled speech cards, and line-numbered code layouts via `PdfDocument`.
- **Integrated Menus:** Accessible from `ChatboxTools.kt` with dedicated file creation launchers.

### 1.12 Zero-Latency Dual-Engine Voice System & Concise Spoken Summaries
- **Instant Pre-Warmed Engine:** `JarvisVoiceController.kt` pre-warms TextToSpeech on launch, eliminating the previous 2-second initialization delay.
- **Intelligent Dual-Engine Routing:**
  - When Gemini / voice model is configured -> Routes spoken summaries through the authentic Gemini Live Jarvis voice (`JarvisConversationService.announceMessage`).
  - When only a custom model is configured -> Uses high-performance on-device TTS with refined assistant pitch (0.96) and snappy cadence (1.05).
- **Strict Single-Utterance Deduplication:** Tracks turn IDs globally to permanently eliminate double-speaking and repetitive echo bugs.
- **Concise Executive Summaries:** `SpokenSummaryGenerator.kt` strips fences, HTML tags, and raw logs, explaining actions in 1-2 crisp sentences (under 30 words) rather than reading massive 500-word responses or code.

### 1.13 Multi-Model Simultaneous Orchestration & Connected Keys
- **Unified Coordinator:** `MultiModelCoordinator.kt` bridges all configured keys simultaneously (Gemini key + Custom Provider profiles).
- **Synergistic Work Distribution:**
  - Heavy coding, terminal tools, and file generation run on the active coding provider (Custom Model or Gemini).
  - Multimodal vision/images and authentic Jarvis voice synthesis route to Gemini.
- **Cross-Model Failover:** Automatically handles rate limits or socket disconnects without dropping the user's ongoing work.
- **Shared Memory & Workspace:** All models operate on the identical workspace filesystem and tool runtime.

### 1.14 Hands-Free Autonomous Background Execution & Instant Error Recovery
- **"Hey Jarvis" Background Wake:** Detects wake commands from standby (e.g. "Hey Jarvis, create a website and run it in local server") without opening the app.
- **Autonomous Execution Engine:** `AutonomousBackgroundExecutor.kt` triggers tool loops in the background, automatically applies Auto-Approve Permissions, creates files, and launches local web servers.
- **Live Progress Announcements:** Speaks milestone updates ("Created project files, 35% complete, about 10 seconds remaining", "Configuring server, 70% complete", "Task completed").
- **Instant Error Resume:** If an execution step encounters an error (such as an occupied port or missing folder), it immediately adapts and retries from the exact line without restarting or re-writing existing files.

### 1.15 Full Dynamic App Theme Palette Integration
- **Zero Static Locking:** Completely eradicated hardcoded `#00E5FF` cyan and `#7C4DFF` purple locks.
- **Harmonized Color System:** Extended `ThemeManager.kt` with `getThemeSecondaryColor()`, `getThemeSuccessColor()`, `getThemeWarningColor()`, and `isCurrentThemeLight()`.
- **Dynamic Adaptations:**
  - `VoiceWaveView.kt` generates smooth multi-color gradients directly from active theme accents.
  - `WorkIconAnimator.kt` and `WorkActionType.kt` dynamically resolve work colors matching the theme.
  - Mini-terminal window tag, "Writing code..." badge, code artifact borders, and `TokenSettingsBottomSheet` all dynamically follow the user's selected theme color (Dark, Red, Sunset, or Custom).
  - Tri-color progress bar maps smoothly across Primary Theme Accent, Secondary Theme Accent, and Success Emerald.

### 1.16 Monotonic Cumulative Chat Token Tracker
- **Session-Wide Accumulation:** Replaced single-turn resetting with a monotonic cumulative counter (`cumulativeChatTokens`) in `AppPreferences.kt`.
- **No Resets on New Input:** Typing or sending new prompts never resets the header token counter to 0; it displays the grand total tokens consumed throughout the entire chat session.
- **Clean Reset Lifecycle:** Resets strictly when the user confirms a "New Chat" in the chat menu.

### 1.17 Theme-Synced Settings & Production-Grade Dual-Tier Neural TTS Engine
- **Global Dynamic Theme Synchronization:** Audited and bound Token Tier selector (Low/High/Max), Auto-Approve switches, and all HUD controls dynamically to `ThemeManager.getThemeAccentColor()`. Zero hardcoded static hex colors; borders, backgrounds, and switch thumbs adapt instantly to theme changes without restarting the app.
- **Primary Local Engine (Kokoro-82M Int8 Multi-Lang):** Default studio-grade on-device neural voice with ~135 MB active RAM footprint. Curated speaker profiles include `bm_george` (Classic Jarvis, British Male, sid=11), `bm_lewis` (sid=12), `af_bella` (sid=1), `bf_emma` (sid=7), and `am_adam` (sid=5).
- **Fallback Local Engine (Piper VITS Alan Medium):** High-speed, lightweight offline fallback (< 50 MB RAM, 20-40 ms synthesis latency).
- **Cloud Synthesis Service (Gemini & ElevenLabs):** Authenticated REST integration for Gemini 3.8 Flash Cloud TTS (Charon, Orus, Kore, Puck) and ElevenLabs Turbo v2.5 (Rachel, Adam) with in-memory `MediaDataSource` streaming.
- **Zero Flash Degradation & In-Memory Streaming:** Native PCM FloatArray piped directly into `AudioTrack` configured with `USAGE_ASSISTANT` and `CONTENT_TYPE_SPEECH`. Zero intermediate WAV/MP3 files written to disk.
- **Strict Pre-Synthesis Sanitization:** Rigorous regex filters stripping code blocks, JSON payloads, URLs, and markdown before voice synthesis.
- **Resilient Model Downloader & Unpacker:** OkHttp 8 KB streaming downloader with real-time `StateFlow<DownloadState>` progress and Apache Commons Compress `.tar.bz2` + `.zip` safe extraction with strict canonical path traversal security against Zip Slip.
- **Cybernetic Holographic HUD & Visualizer:** OLED Obsidian canvas with dynamic frosted glass panels and custom Canvas-rendered `HolographicVisualizerView` actively reacting to speech RMS amplitude in real-time. Immediate voice validation on save: *"Systems initialized. Voice parameters verified, Sir."*

---

## 2. Future Implementation Roadmap (13 Features)

### Phase 1: High-Impact Chat & Voice Upgrades (~20 mins) — COMPLETED ✓
- [x] **Hands-Free Auto Voice Reply**: When sending a query via voice input, Jarvis automatically reads the answer aloud using TTS.
- [x] **Full Syntax Highlighting in Code Viewer**: Colorized themes (Dark+, Dracula, Monokai) for Python, Kotlin, JS, HTML, Bash, and JSON in `CodeViewerBottomSheet`.
- [x] **Camera & Screenshot Quick Capture**: Direct camera snapshot and gallery picker icons in the composer row for multimodal vision queries.
- [x] **Export to Markdown & PDF**: Save chat transcripts with formatted code blocks directly to `.md` or formatted `.pdf`.

### Phase 2: Workspace & Developer Tools (Next Up)
- [ ] **In-App Live Web Inspector & Mini-Browser**: Floating or split mini-browser to preview websites, HTML files, or localhost dev servers started by Jarvis terminal with mobile/tablet/desktop viewports and live reload without leaving the app.
- [ ] **Visual Git Diff & Dashboard Sheet**: Interactive color-coded git diff (additions/deletions), 1-tap commit/push with AI-generated commit message, and branch switcher.
- [ ] **Live Interactive Terminal Drawer & Cybernetic Console**:
  - **Active Termux IPC Bridge**: Connect `TermuxBridgeSpec` to execute `git`, `node`, `npm`, `python3`, and `cargo` via `com.termux.RUN_COMMAND`.
  - **ANSI 256/RGB Color Streamer**: Real-time ANSI escape parser and carriage return (`\r`) spinner deduplication.
  - **Mobile Virtual Accessory Row**: Touch keyboard extension for `ESC`, `TAB`, `CTRL`, `ALT`, `|`, `-`, `/`, `~`, arrow keys, and `Ctrl+C`/`Ctrl+D` signals.
  - **Localhost Port Detection & Preview**: Automatic detection of dev server ports (e.g. `127.0.0.1:5173`) with a floating `[ 🚀 Open Live Preview ]` launcher.
  - **Inline 'Fix with Jarvis' Error Diagnostics**: 1-tap failure analysis button on non-zero exit codes.
  - **Daemon Process Manager & WakeLock**: Background process table with memory/runtime telemetry and Android foreground wake-lock.
- [ ] **Workspace File Tree Drawer (`📁 Explorer`)**: Directory tree view of workspace files, file size & modified badges, and 1-tap zip export.
- [ ] **Search & Filter in Chat History**: Full-text keyword and code snippet search across saved conversation turns.
- [ ] **Pinned Prompts & Custom Workflows**: Customizable shortcut chips for repetitive tasks.

### Phase 3: Advanced Multimodal & System Automations
- [ ] **Continuous Live Duplex Voice Mode (with Voice Barge-In)**: Interruption-capable continuous voice mode where speaking automatically pauses Jarvis TTS and starts listening naturally.
- [ ] **AI Image Generation (`generate_image` Tool)**: Built-in tool allowing Jarvis to generate images (using FLUX, Stable Diffusion, or DALL-E) with 1-tap gallery saving.
- [ ] **Floating Overlay Chat Bubble**: Messenger-style floating Jarvis bubble to access chat and voice over any app without opening full screen.
- [ ] **Notification Quick-Reply & Live Progress Bar**: Ongoing status notification with progress bar and quick actions in the Android notification shade.
- [ ] **Encrypted Workspace Backup & Cloud Sync**: 1-tap exportable `.jarvis` encrypted package for full restore.

---

## 3. Architecture & Quick-Reference Notes

| Module | Core Files | Responsibility |
| :--- | :--- | :--- |
| **Chat UI** | `ChatFragment.kt`, `JarvisChatAdapter.kt` | Chatbox controls, streaming rebinds, attachment picker, voice toggle. |
| **Code Artifacts** | `CodeBlockParser.kt`, `CodeArtifactCardBinder.kt`, `CodeViewerBottomSheet.kt` | Real-time code extraction, animated cards, full-screen code viewer. |
| **Token System** | `TokenMode.kt`, `TokenSettingsBottomSheet.kt`, `ProviderChatEngine.kt` | Token tiers, live header badge, adaptive reasoning prompt. |
| **Voice & Mic** | `VoiceWaveView.kt`, `fragment_chat.xml` | Audio waveform rendering, SpeechRecognizer management. |
| **Permissions** | `Permissions.kt`, `ToolExecutor.kt`, `ToolRuntime.kt` | Task grants, session grants, audit logging. |

> **Next Session Prompt:**  
> When ready to resume, simply say:  
> *"Let's start implementing Phase 1 from the feature roadmap."*
