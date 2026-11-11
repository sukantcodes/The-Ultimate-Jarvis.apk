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

---

## 2. Future Implementation Roadmap (13 Features)

### Phase 1: High-Impact Chat & Voice Upgrades (~20 mins)
- [ ] **Hands-Free Auto Voice Reply**: When sending a query via voice input, Jarvis automatically reads the answer aloud using TTS.
- [ ] **Full Syntax Highlighting in Code Viewer**: Colorized themes (Dark+, Dracula, Monokai) for Python, Kotlin, JS, HTML, Bash, and JSON in `CodeViewerBottomSheet`.
- [ ] **Camera & Screenshot Quick Capture**: Direct camera snapshot and gallery picker icons in the composer row for multimodal vision queries.
- [ ] **Export to Markdown & PDF**: Save chat transcripts with formatted code blocks directly to `.md` or formatted `.pdf`.

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
