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

---

## 2. Future Implementation Roadmap (13 Features)

### Phase 1: High-Impact Chat & Voice Upgrades (~20 mins) — COMPLETED ✓
- [x] **Hands-Free Auto Voice Reply**: When sending a query via voice input, Jarvis automatically reads the answer aloud using TTS.
- [x] **Full Syntax Highlighting in Code Viewer**: Colorized themes (Dark+, Dracula, Monokai) for Python, Kotlin, JS, HTML, Bash, and JSON in `CodeViewerBottomSheet`.
- [x] **Camera & Screenshot Quick Capture**: Direct camera snapshot and gallery picker icons in the composer row for multimodal vision queries.
- [x] **Export to Markdown & PDF**: Save chat transcripts with formatted code blocks directly to `.md` or formatted `.pdf`.

### Phase 2: Workspace & Developer Tools (~25 mins)
- [ ] **In-App Live Web Preview**: Floating or split mini-browser to view websites, HTML files, or localhost dev servers started by Jarvis terminal without leaving the app.
- [ ] **Quick Git Dashboard Sheet**: Interactive git status, branch switcher, and 1-tap commit/push sheet directly from Chatbox.
- [ ] **Search & Filter in Chat History**: Full-text keyword and code snippet search across saved conversation turns.
- [ ] **Pinned Prompts & Custom Workflows**: Customizable shortcut chips for repetitive tasks (e.g., "Summarize Day", "Check Git Status", "Restart Dev Server").

### Phase 3: Advanced Multimodal & System Automations (~30 mins)
- [ ] **AI Image Generation**: Built-in tool allowing Jarvis to generate images (using FLUX, Stable Diffusion, or DALL-E) and display them directly in chat turns.
- [ ] **Interactive Terminal Drawer**: Slide-out live terminal output drawer to monitor background commands while chatting.
- [ ] **Floating Overlay Chat Bubble**: Messenger-style floating Jarvis bubble to access chat and voice over any app without opening full screen.
- [ ] **Notification Quick-Reply**: Answer or command Jarvis directly from the Android notification shade.
- [ ] **Scheduled Automated Routines**: Set background cron tasks (e.g., "Check repo updates every morning at 9 AM", "Daily news summary").

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
