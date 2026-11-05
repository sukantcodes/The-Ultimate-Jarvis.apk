---
name: jarvis-master-blueprint
description: JARVIS app map and safe voice, lock-screen, alarm and device-control command routing
---
# JARVIS Master Blueprint — App Awareness and Cross-App Control

Routing map for JARVIS and its Android host. Not permission to bypass Android security. Prefer native tools and semantic UI identifiers.

## 1. Identity and project facts
- Android application ID / namespace: com.jarvis.assistant
- Current project architecture: Kotlin, Android Gradle Kotlin DSL, XML layouts, ViewBinding, Material Components, minSdk 26, compileSdk 34, targetSdk 34.
- Main entry: ui.home.MainActivity. It owns the Home, Chat, Settings and Updates tabs plus navigation dock and drawer.
- Authentication entry: ui.auth.AuthActivity; first-run pre-login intro is shown before authentication when required.
- Chat UI: ui.chat.ChatFragment. Existing conversation/provider routing remains the text-chat backend. Document analysis uses DocumentAnalysisClient and currently requires a Gemini API key saved in Settings.
- Skills: assets/skills/*.md are built-in skill playbooks. SkillStore discovers them automatically; enabled skill descriptions enter the system prompt and full bodies are loaded on demand through use_skill. Do not hard-code a second skill index.
- Main background conversation engine: background.JarvisConversationService.
- Android accessibility automation: automation.accessibility.JarvisAccessibilityService, user must explicitly enable it in Android Accessibility settings.
- Notification access: notifications.JarvisNotificationListener, user must explicitly grant notification-listener access.
- Android default assistant integration: assistant.JarvisVoiceInteractionService and JarvisVoiceInteractionSessionService. A user must select JARVIS as the system Digital Assistant where supported.
- Separate voice interaction implementation also exists under voice.JarvisVoiceInteractionService and voice.JarvisVoiceInteractionSessionService. Inspect before changing either implementation; do not assume they are interchangeable.
- Alarm components: clock.AlarmRingService, AlarmReceiver, ClockSystemReceiver and alarm/settings activities.
- Screen capture uses ScreenCaptureManager; capture depends on the applicable active session and Android consent/policy. Accessibility screenshot capture is separately gated by enabled accessibility service and OS support.
- Owner-only screens: ui.owner.OwnerPasswordActivity and OwnerDashboardActivity; never expose or navigate into these without owner authentication.

## 2. App navigation and screen inventory
Use the current navigation graph, layout IDs and Activity/Fragment source as the authority. The following destinations are confirmed in the app manifest or main activity:
- Launcher / app shell: ui.home.MainActivity; fragments/tabs: Home, Chat, Settings, Updates. MainActivity setupNavigationDock() and setupDrawerNavigation() define current navigation actions.
- Authentication: ui.auth.AuthActivity (login/register/guest and OAuth callback).
- Chat: ui.chat.ChatFragment (text conversation, attachment selection, file analysis, response actions, stop/new-chat, speech playback).
- Settings: ui.settings.SettingsActivity; related pages include ApiConnectionActivity, PersonaActivity, MoreThemeColoursActivity, PocketTtsSetupActivity, MayaModeActivity, OfflineModelActivity, OverlayAvatarActivity, PrivacyShieldActivity, HandsFreeActivity, FaceLockActivity, ScreenUnlockActivity, PhonePermissionActivity.
- Permissions: ui.permission.PermissionActivity.
- Skills: ui.skills.SkillStoreActivity.
- Memory: ui.memory.MemoryActivity.
- Voice enrollment: ui.voice.VoiceEnrollmentActivity.
- Diagnostics: ui.diagnostics.DeviceDiagnosticsActivity.
- Camera scan: ui.scan.CameraScanActivity.
- Gallery: gallery.GalleryActivity.
- Clock: ui.clock.ClockSettingsActivity, AlarmsActivity, AlarmTonesActivity, ClockDisplayActivity, AlarmBehaviourActivity, AlarmRingActivity.
- Automation: studio.ui.AutomationStudioActivity and AutomationEditorActivity.
- Debug action routing: ui.debug.ActionRouterActivity (not exported).
- Owner area: ui.owner.OwnerPasswordActivity then OwnerDashboardActivity.
Do not infer the exact buttons or child views from screen names alone. Read the current XML and binding/source before acting.

## 3. Semantic location map — not fixed pixel coordinates
The app has resource IDs and runtime view bounds, not universal pixel coordinates. Screen size, density, font scaling, keyboard, navigation mode, display cut-outs, orientation, theme and content all change coordinates.
- For in-app navigation, use MainActivity's tab/drawer methods or Navigation/Fragment APIs already present.
- For a view inside a screen, resolve its current resource ID / accessibility contentDescription / visible text, then read its current screen bounds from the active view or accessibility node.
- For another app, use JarvisAccessibilityService to inspect the current foreground package, visible windows and node tree. Match stable resource-id first, then contentDescription/text/class and visible bounds. Re-inspect after every screen transition.
- Coordinates are a last resort: obtain fresh bounds from the current screen, calculate the target center, perform one action, then verify. Never reuse old coordinates after scrolling, rotation, app switch, dialog, keyboard or layout change.
- Never invent IDs, coordinates, screen positions or claim a control exists without current source/runtime evidence.

## 4. Cross-app execution routing
Choose the least privileged supported route:
1. JARVIS native app operation: call the existing native action/tool or navigate directly inside the app.
2. Android public intent / documented system API: use a verified intent or API with explicit package/action and validate resolution before launch.
3. Notification action: use only actions exposed by the notification and only with user-authorized notification access.
4. Accessibility: only when user enabled the service; inspect the current foreground app and act on a fresh visible semantic node. Use it for UI actions that have no public API.
5. Settings fallback: open the exact supported Settings page and let the user complete any restricted switch or confirmation.
6. If no supported route exists, explain the limitation and offer the nearest safe route. Never simulate a successful action.

## 5. Lock-screen command policy — highest priority
- Lock-screen JARVIS voice control is a core requirement: invoke the configured Android Digital Assistant, receive a spoken command, run a supported native action, speak a verified result, and do not open MainActivity for simple commands.
- First required command: “set an alarm for 6:30 AM” / “wake me at 7 tomorrow”. Parse time/date/repeat into AlarmDraft and use the shared ClockServices.engine(context).create(...). Never create a second alarm store.
- Announce success only after ClockResult.Ok confirms the alarm was saved and scheduled. Clarify ambiguous AM/PM/date/repeat; never guess. Resolve edit/cancel targets uniquely; ask if multiple alarms match.
- Keep lock-screen speech privacy-safe. Private messages, contacts, files, account data and OTPs require unlock and authorization.
- The assistant session must route the command before hiding; do not depend on opening the app. If Android requires unlock or rejects the action, say it was not completed.
- Never bypass PIN, password, pattern, biometrics, app lock, permission or consent screens. Follow Android microphone/foreground-service restrictions.

## 6. Device control capability matrix
- Generally available through app/native APIs when permission and hardware permit: flashlight, media playback/volume, alarms/timers, opening supported Settings pages, app launch, location-based features with permission, Bluetooth device operations with runtime permission, and app-specific actions.
- Accessibility-dependent: interacting with visible UI in other apps, pressing visible controls, reading visible labels, scrolling and supported gestures. User must enable it and Android/OEM may restrict it.
- Notification-listener-dependent: seeing notifications and invoking their exposed actions. Access is highly sensitive; request only after clear user consent and never exfiltrate notification contents.
- User/System-role dependent: default digital assistant, notification access, accessibility, display-over-other-apps, modify-system-settings, exact alarms and battery exemption. User grants these in Android Settings.
- Usually restricted to system apps, privileged apps, device-owner management, root or special OEM APIs: secure settings, silently changing app permissions, global radio toggles on newer Android, lock credentials, biometric state, force-stopping arbitrary apps, and protected device policy.
- Never claim root/system privilege, OEM private APIs or full control unless verified in code and on the actual device.
- Wi-Fi/Bluetooth/mobile data/location/airplane/NFC/hotspot and refresh-rate controls vary by OS version and OEM. Prefer opening the official Settings page when a public API cannot change the state.
- Destructive or high-impact actions (delete, reset, uninstall, send, purchase, call, SMS, change security, revoke access) require a clear command, review/confirmation, and unlock as Android requires.

## 7. Permission and trust rules
- Ask before directing user to enable Accessibility, Notification Listener, microphone, camera, location, contacts, SMS/call, overlay, modify-system-settings, all-files access, or battery exemption.
- Explain why the permission is needed, what it can expose, and how to turn it off.
- Do not silently grant permissions, accept dialogs, capture or upload screens, or collect notification contents.
- Keep secrets and private content out of logs, prompts, spoken lock-screen responses and analytics.
- Use explicit allowlists for supported package/action workflows. Validate package names, intent targets and action parameters. Never execute arbitrary natural-language shell/code.
- Owner-only operations stay behind the app's owner authentication.

## 8. Reliable action loop
For every command: (a) classify intent and risk; (b) check lock state and permission/service availability; (c) select native API, intent, notification action or fresh accessibility node; (d) request confirmation if needed; (e) execute once; (f) verify actual state/result; (g) report completed, blocked, opened, or awaiting unlock accurately.
Prefer stable IDs and APIs over coordinates. Use short bounded waits and cancellation. Avoid repeated blind taps and stale UI nodes.

## 9. Skill routing examples
- "Open my JARVIS chat" → MainActivity / Chat tab.
- "Where is API key?" → Settings → API connection; inspect current settings navigation and labels before naming a subpage.
- "Analyze this PDF" → Chat attachment picker → DocumentAnalysisClient; confirm Gemini key configured and current file type/size limits.
- "Turn on torch" → native flashlight action if present; otherwise supported device-control route and verify.
- "Tap the button in YouTube" → inspect foreground YouTube UI using accessibility; locate fresh semantic node; perform one action; verify.
- "Read my notifications while locked" → do not disclose private contents; offer a privacy-safe count/summary only if explicitly implemented and permitted, otherwise require unlock.
- "Send this message" → show recipient and message for confirmation; require unlock/confirmation where Android or the target app requires it.
- "Change a protected system setting" → open the official Settings page; user completes protected action.

## 10. Keep this blueprint current
This file is a built-in SkillStore skill. Update it whenever Activities, Fragments, resource IDs, skills, tools, permission declarations or execution routes change. For exhaustive source inventory, consult docs/architecture/JARVIS_MASTER_BLUEPRINT.md. Treat source code and the installed device's live UI as truth; this document is a navigation index, not a substitute for verification.
