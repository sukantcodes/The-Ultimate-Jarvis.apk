# JARVIS Master App & Android Integration Blueprint

**Project:** The Ultimate JARVIS  
**Application ID:** `com.jarvis.assistant`  
**Branch baseline:** `Chatbox`  
**Architecture baseline:** Kotlin, XML layouts, ViewBinding, Gradle Kotlin DSL; minSdk 26, compileSdk/targetSdk 34.  
**Purpose:** Give JARVIS a maintained, searchable map of its own app and a safe execution model for Android and other apps.

> This is a source-grounded architecture index. It is not a dump of fixed screen coordinates and does not grant system privileges. Source IDs and live view bounds must be resolved at runtime. Android permissions, authentication and lock-screen protections remain authoritative.

## 1. Core architecture

| Layer | Confirmed component / location | Responsibility |
|---|---|---|
| Android application | `JarvisApp` | Application-level initialization and shared preferences/services |
| App shell | `ui.home.MainActivity` | Main activity, four tabs, navigation dock, drawer, theme and fragment hosting |
| Authentication | `ui.auth.AuthActivity`, `AuthManager` | Login/register/guest and OAuth callback |
| Chat interface | `ui.chat.ChatFragment` | Conversation UI, attachments, response controls |
| Conversation runtime | `background.JarvisConversationService` | Background/live conversation session |
| Skills | `skills.SkillStore`, `skills.SkillParser`, `assets/skills/*.md` | Built-in and installed skill discovery, enable/disable, on-demand skill content |
| Cross-app UI automation | `automation.accessibility.JarvisAccessibilityService` | Inspect visible UI and interact with user-enabled accessibility service |
| Notifications | `notifications.JarvisNotificationListener` | Notification access and exposed notification actions |
| Voice assistant role | `assistant.JarvisVoiceInteractionService`, `assistant.JarvisVoiceInteractionSessionService`, `assistant.JarvisRecognitionService` | Android VoiceInteractionService integration |
| Additional voice implementation | `voice.JarvisVoiceInteractionService`, `voice.JarvisVoiceInteractionSessionService` | Second voice package declared in manifest; inspect call sites before modifying |
| Screen capture | `vision.ScreenCaptureManager` plus accessibility screenshot path | Session-based screen capture and screenshot conversion |
| Alarms | `clock.AlarmRingService`, `AlarmReceiver`, `ClockSystemReceiver` | Alarm scheduling, ring service, boot/time-change handling |
| Automation studio | `studio.ui.AutomationStudioActivity`, `AutomationEditorActivity` | Automation authoring UI |
| Diagnostics | `ui.diagnostics.DeviceDiagnosticsActivity` | Device diagnostics |
| Gallery / scan | `gallery.GalleryActivity`, `ui.scan.CameraScanActivity` | Gallery and camera scanning |
| Owner area | `ui.owner.OwnerPasswordActivity`, `OwnerDashboardActivity` | Owner-authenticated screens; remain protected |
| Data / persistence | Room, SharedPreferences, encrypted preferences (dependencies and existing app usage) | Local app state and settings |

### Skill loading contract
Built-in playbooks are files under `app/src/main/assets/skills/`. `SkillStore.builtIns()` lists and parses the asset directory. Enabled skills contribute their short descriptions to the assistant system prompt; complete bodies are loaded on demand by `use_skill`. Therefore, adding a correctly front-mattered Markdown skill under this directory automatically makes it discoverable; do not create a duplicate hard-coded registry.

## 2. App screen and route inventory

Confirmed manifest destinations and main shell destinations:

| Area | Destination / component | Notes |
|---|---|---|
| Launcher / shell | `ui.home.MainActivity` | Main entry point after auth; hosts Home, Chat, Settings, Updates |
| Home | Home fragment in main shell | Main dashboard destination |
| Chat | `ui.chat.ChatFragment` | Text chat, attachment selection and analysis, stop/new chat, response actions |
| Settings | `ui.settings.SettingsActivity` | Settings hub |
| API connection | `ui.settings.ApiConnectionActivity` | Provider/API configuration |
| Persona | `ui.settings.PersonaActivity` | Assistant persona configuration |
| Themes | `ui.settings.MoreThemeColoursActivity` | Theme customization |
| TTS | `ui.settings.PocketTtsSetupActivity` | Optional Pocket TTS setup |
| Maya | `ui.settings.MayaModeActivity` | Maya mode |
| Offline model | `ui.offline.OfflineModelActivity` | Offline model configuration |
| Overlay | `ui.settings.OverlayAvatarActivity` | Overlay avatar |
| Privacy | `ui.settings.PrivacyShieldActivity` | Privacy controls |
| Hands-free | `ui.settings.HandsFreeActivity` | Hands-free settings |
| Face lock / screen unlock | `FaceLockActivity`, `ScreenUnlockActivity` | Security-related app flows; never bypass OS lock |
| Phone permissions | `ui.settings.PhonePermissionActivity`, `ui.permission.PermissionActivity` | Permission education / routing |
| Skills | `ui.skills.SkillStoreActivity` | Built-in and store skills |
| Memory | `ui.memory.MemoryActivity` | Memory UI |
| Voice enrollment | `ui.voice.VoiceEnrollmentActivity` | Voice enrollment |
| Diagnostics | `ui.diagnostics.DeviceDiagnosticsActivity` | Diagnostics |
| Camera scan | `ui.scan.CameraScanActivity` | Camera scan |
| Gallery | `gallery.GalleryActivity` | Gallery |
| Clock | `ClockSettingsActivity`, `AlarmsActivity`, `AlarmTonesActivity`, `ClockDisplayActivity`, `AlarmBehaviourActivity`, `AlarmRingActivity` | Alarm and clock settings |
| Automation | `AutomationStudioActivity`, `AutomationEditorActivity` | Automation builder/editor |
| Debug | `ui.debug.ActionRouterActivity` | Not exported |
| Owner | `OwnerPasswordActivity`, `OwnerDashboardActivity` | Owner authentication required |
| Updates | Updates fragment in main shell | Updates tab |

The manifest and current navigation source are the authority. This list is a route index, not a claim that every page or control is reachable from every app state.

## 3. Semantic UI map and coordinate resolution

There is no reliable universal pixel coordinate for a button. Coordinates depend on physical resolution, density, display scaling, font size, status/navigation bars, keyboard, orientation, fold state, theme, content and scrolling.

Use this resolution order:

1. **In-app route:** use MainActivity navigation methods / Fragment APIs and the actual navigation IDs.
2. **View identity:** inspect current XML layout and ViewBinding for resource IDs; prefer `android:id`, contentDescription and stable labels.
3. **Live bounds:** read the active view's screen bounds or AccessibilityNodeInfo bounds for the current window.
4. **Cross-app semantic match:** match package + resource ID first; otherwise contentDescription, visible text and class. Confirm the matched node belongs to the current foreground window.
5. **Coordinate fallback:** derive a point from fresh bounds only when no semantic action is available; act once and re-inspect.
6. **Verification:** confirm changed text/state/navigation or a native API result before reporting success.

Never store or reuse hard-coded screen coordinates in the blueprint. Never tap a stale node after a screen transition. Re-resolve after scrolling, orientation changes, app switching, dialogs or keyboard display.

## 4. Cross-app control architecture

Execution should be routed by capability, not by pretending every action is a tap:

| Route | Use when | Preconditions / boundary |
|---|---|---|
| Native JARVIS action | The app already has a dedicated tool/API | Validate inputs and result |
| Public Android API / Intent | Android exposes a documented operation | Resolve target and validate intent; no arbitrary intent injection |
| Notification action | A notification exposes an action | User-enabled notification listener; use only exposed actions |
| Accessibility service | A visible app UI must be operated | User explicitly enables service; inspect fresh UI; OEM/app may block |
| Settings deep link | Android reserves a setting for the user | Open official page; user operates restricted control |
| Manual hand-off | No supported API or safe UI route exists | Explain exact user action; do not fake success |

### Cross-app safety rules
- Use package allowlists for supported integrations and validate package/action pairs.
- Never run shell commands, arbitrary code, hidden APIs or guessed OEM endpoints from natural language.
- Never silently accept Android dialogs or grant permissions.
- For sending, calling, posting, buying, deleting, uninstalling or security changes, show the target/action and require explicit confirmation; require unlock where Android or the target app demands it.
- Respect app sandboxing and other apps' privacy. Only interact with visible UI that the user asked JARVIS to operate.

## 5. Lock-screen / background capability model — highest-priority interaction

**Product priority:** Lock-screen JARVIS invocation and useful voice commands are a core requirement, not a secondary convenience. The target flow is: user invokes the configured JARVIS Digital Assistant while the phone is locked, speaks a command, JARVIS routes it to a native action, speaks a concise result, and returns control without requiring the user to open the JARVIS app. Implement and test this path before expanding lower-priority automation features.

A lock screen is not a second unlocked desktop. Android allows selected assistant and notification experiences, but restricts private content and high-impact actions. The assistant session must remain the entry point; do not depend on launching MainActivity or showing the full chat UI.

### Lock-screen alarm commands — required first native skill
- Support direct commands such as “set an alarm for 6:30 AM”, “wake me at 7 tomorrow”, and repeat-day alarms when the time/date is unambiguous.
- Route all create/read/update/delete/ring/snooze operations through the existing shared `ClockServices.engine(context)` / `ClockEngine`; never create a second alarm store or bypass the existing alarm scheduler.
- For create: parse time/date/repeat/label into a typed `AlarmDraft`, call `ClockEngine.create`, and speak success only after `ClockResult.Ok` confirms Android accepted and persisted the schedule. On ambiguous AM/PM, date or repeat intent, ask a short clarification instead of guessing.
- For list/cancel/edit: resolve the target uniquely using ClockEngine; ask which alarm if multiple matches. Confirm destructive deletion/cancellation when the target is not unambiguously specified.
- Keep the response short and privacy-safe on the lock screen (for example, “Alarm set for 6:30 AM”). Do not expose alarm labels that may contain private information unless appropriate.
- If the OS blocks execution or requires unlock, state that it was not completed; never announce a scheduled alarm without a verified engine result.

### Implementation sequence
1. Keep the Android VoiceInteractionSession alive long enough to receive/route a command; do not immediately hide it before the command path is ready.
2. Connect assistant-session speech input to the existing conversation/tool runtime or a small deterministic native-command router. Prefer deterministic local parsing for alarms so they do not depend on a network model.
3. Route alarm actions to the shared ClockEngine and return verified outcomes through the assistant session's speech output.
4. Check device lock state and command risk before every action; do not launch the full app to complete a simple alarm command.
5. Add automated tests for locked/unlocked session routing, exact time parsing, ambiguous AM/PM, repeat days, schedule rejection, duplicate delivery and verification.
6. Test on the actual Android/OEM device: selecting JARVIS as default assistant, invocation while locked, microphone availability, foreground-service restrictions, and spoken result.



### Safe low-risk examples (only if implemented and allowed by active OS role/session)
- Speak a generic answer or time.
- Report non-private device status.
- Stop JARVIS audio or a running JARVIS session.
- Manage a previously configured timer/alarm through its own authorized flow.
- Acknowledge an exposed notification action without reading its private contents.

### Require unlock or explicit confirmation
- Read private messages, notification bodies, contacts, calendar entries, files, account data or OTPs.
- Send messages, make calls, publish content, place orders or make payments.
- Delete or modify user data; uninstall apps; change passwords, biometrics, screen-lock settings or account security.
- Open protected JARVIS owner features or another app's private screens.

### Existing implementation facts
- `AlarmRingActivity` is declared with `showWhenLocked=true` and `turnScreenOn=true` for the alarm experience only. This is not general permission to display arbitrary JARVIS content over the lock screen.
- VoiceInteractionService is declared in the manifest. The user must select JARVIS as the default digital assistant where supported.
- Accessibility and notification listener services are declared and bound behind Android system permissions. User must enable them explicitly.
- A foreground conversation service exists. Background microphone availability is subject to Android foreground-service type, microphone, notification and OEM background limits.
- No component may bypass PIN, pattern, password, biometrics, app lock, permission dialogs or consent screens.

## 6. Device / OS controls

Use the existing `device-control` and `android14-coloros` skills as detailed setting playbooks. Prefer native APIs where present; otherwise open a documented Settings page and let the user finish restricted operations.

| Control category | Expected route | Constraints |
|---|---|---|
| Torch / flashlight | CameraManager/native tool | Hardware and camera permission/state |
| Media / audio | AudioManager / media session | Stream type and focus matter |
| Brightness | Native supported API or display Settings | Modify-system-settings access may be required |
| Alarms / timers | AlarmManager and JARVIS clock components | Exact alarm policy and notification permission |
| Wi-Fi / Bluetooth | Public APIs or Settings | Global radio changes may be restricted; Bluetooth runtime permission |
| Mobile data / airplane / NFC / hotspot / location | Official Settings / documented API | Often system-restricted; user may need to act |
| Display / dark mode / refresh rate / timeout | Settings route or supported API | OEM/device-specific |
| App permissions / special access | Android Settings | Do not silently grant or revoke |
| Battery optimization / autostart | OEM Settings | User action; no guaranteed always-on process |
| Location / maps | Location APIs | Runtime permission and foreground/background policy |
| Other apps | Intent, notification action or accessibility | Target app state and its own confirmation/security policy |

The app declares permissions including audio, location, internet, media, Bluetooth, foreground service, notifications, wake lock, camera, contacts, call/SMS, alarm, notification policy, modify-system-settings, secure-settings (protected), package visibility, overlay and others. A manifest declaration is not a granted permission. Some listed permissions are privileged or restricted and cannot be granted to an ordinary app by a normal runtime prompt. Audit and request only the minimum needed for each feature.

## 7. Privacy, consent and permission lifecycle

For every sensitive capability, JARVIS should show: (1) why it is needed, (2) what data/action it enables, (3) how to enable it in Android Settings, (4) how to disable it later. Ask before directing the user to enable accessibility, notification access, microphone, camera, location, contacts, SMS/call, overlay, modify-system-settings, broad file access or battery exemption.

Never expose passwords, recovery codes, OTPs, private notification bodies or secrets in logs, voice output, analytics or a lock-screen response. Avoid screen capture unless the user explicitly starts the feature and the relevant OS permission/consent is active.

## 8. Action contract and state machine

Each action should carry:
- `intent`: normalized action name.
- `target`: app/package/resource ID or setting key.
- `parameters`: typed, validated values.
- `risk`: read-only / reversible / sensitive / destructive.
- `requiresUnlock`: derived from action and OS/target app requirements.
- `permission`: required Android permission/role/service and current grant state.
- `source`: native API / public intent / notification / accessibility / Settings.
- `verification`: expected observable state/result.

Execution lifecycle:

```text
RECEIVED
  -> CLASSIFY_INTENT_AND_RISK
  -> CHECK_LOCK_STATE_AND_PERMISSION
  -> RESOLVE_NATIVE_API_OR_FRESH_UI_TARGET
  -> ASK_CONFIRMATION_IF_REQUIRED
  -> EXECUTE_ONCE
  -> VERIFY_RESULT
  -> REPORT_COMPLETED | OPENED | AWAITING_UNLOCK | PERMISSION_REQUIRED | BLOCKED | FAILED
```

The assistant must distinguish an opened page from a changed setting, a requested action from a completed action, and a queued action from an executed action.

## 9. Latency and reliability

- Route simple actions to native APIs; do not inspect screenshots unnecessarily.
- Cache static app metadata, not live screen coordinates or stale AccessibilityNodeInfo objects.
- Load long skill bodies on demand through SkillStore.
- Use bounded timeouts, cancellation and one verification pass.
- Avoid repeated blind taps, polling loops and unnecessary network lookups.
- If a tool is unavailable or permission is missing, stop and explain the shortest next step.
- Do not promise zero delay, uninterrupted background operation or universal control.

## 10. Maintenance checklist

Whenever the app changes:
- Update Activity / Fragment / service inventory.
- Update resource IDs and route map from XML and Kotlin source.
- Update built-in skill inventory and trigger descriptions.
- Update manifest permission/service/receiver declarations.
- Update native action catalog and cross-app allowlist.
- Re-check lock-screen restrictions and confirmation gates.
- Add tests for routing, permission-denied, locked-device, cancellation and verification states.
- Keep `app/src/main/assets/skills/jarvis-master-blueprint.md` aligned with this document.

## 11. Current known built-in skill inventory

The repository baseline includes these playbooks:
- `amazon-shopping.md`
- `android-files-manager.md`
- `android14-coloros.md`
- `blinkit-shopping.md`
- `call-secretary.md`
- `chatgpt-image-gen.md`
- `chrome-browser-assistant.md`
- `daily-briefing.md`
- `device-control.md`
- `gmail-assistant.md`
- `google-calendar-planner.md`
- `google-drive-manager.md`
- `google-keep-notes.md`
- `google-maps-navigator.md`
- `google-photos-organizer.md`
- `instagram-assistant.md`
- `maya-companion.md`
- `music-dj.md`
- `phone-care.md`
- `photo-share.md`
- `rapido-rides.md`
- `study-focus.md`
- `telegram-assistant.md`
- `whatsapp-direct-control.md`
- `whatsapp-pro.md`
- `youtube-assistant.md`
- `jarvis-master-blueprint.md` (this blueprint skill)

This inventory is a source snapshot; SkillStore's runtime asset listing is authoritative.


## 12. Device cleanup and diagnostics
- Device Diagnostics includes Junk Clean, Boost, Battery Saver, Large Files Cleanup, WhatsApp Clean, Photos Clean and App Uninstaller.
- Junk Clean is limited to JARVIS-owned cache. Boost reports available RAM and opens supported app-management controls; it must not claim to force-stop arbitrary apps.
- Large-file, WhatsApp and photo scans operate only within a folder explicitly selected through Android's Storage Access Framework. Review targets and ask before deletion.
- App uninstall uses Android's own confirmation. Battery Saver opens the supported system control page and must be verified.
- Voice intents should route to these same workflows, not a parallel implementation.
- Recreate the requested Snaptube-like scan/progress/result presentation with original JARVIS animation work; do not package proprietary Snaptube animation assets or code.
