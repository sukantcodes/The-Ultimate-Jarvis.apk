---
name: device-control
description: Fast device and Android/ColorOS settings control using Jarvis native tools, verified settings pages and safe fallbacks
---
# JARVIS Device Control — Fast OS Blueprint

Use this skill FIRST whenever the user asks to control, inspect, configure, troubleshoot or navigate phone/device settings. Pair it with the android14-coloros skill for detailed ColorOS 14 paths. This skill is a routing and execution playbook, not permission to bypass Android.

## 1. Device-aware routing
- Treat the currently installed Android version, OEM skin, model and visible Settings labels as authoritative. The user's expected platform may be Android 14 + ColorOS, but verify when a workflow depends on a version-specific option.
- Do not fetch an online blueprint during every command. Use this local playbook and the installed android14-coloros skill first; online lookup is only for a genuinely missing or version-specific detail.
- Never infer unsupported tool parameters, hidden APIs, shell commands, root access or undocumented OEM capabilities.
- For each request, identify the exact setting, desired state/value, whether it is reversible, and whether it can interrupt connectivity, audio, privacy or background operation.

## 2. Fastest supported route
Choose the first available valid route:
1. Read-only status: call device_status().
2. Brightness: call set_brightness with the tool's declared parameter; do not open Settings first.
3. Volume: call set_volume with the declared parameter. Clarify media/ringtone/alarm/call/notification if ambiguous.
4. Flashlight: call set_flashlight with the declared parameter.
5. Direct settings navigation: open_settings_page with one supported key listed below.
6. UI fallback: inspect_screen(); interact only with a fresh visible element; verify with inspect_screen() again.
7. If the direct page route fails, open Settings main once, inspect, and use the actual visible controls. Do not repeat blind taps.

Supported open_settings_page keys:
wifi, bluetooth, display, sound, location, airplane, apps, accessibility, security, battery, date, language, storage, nfc, mobile_network, privacy, notifications, developer, main.

## 3. Intent-to-control map
- "phone/device status", "battery kitni hai" → device_status().
- "brightness kam/zyada/X percent" → set_brightness.
- "volume/media volume/ringtone/alarm volume" → set_volume; determine stream when unclear.
- "torch/flashlight on/off" → set_flashlight.
- Wi-Fi / Bluetooth → open matching page, inspect current state, then use a visible switch only if OS permits. Android may block third-party global radio toggles; if blocked, leave the system page open for the user.
- Airplane mode / NFC / mobile network / Location → open matching page and inspect. Warn about connectivity, calls, tracking or connected services before a requested state change. Android restrictions may require user action.
- Dark mode, refresh rate, screen timeout, font/display size, auto-rotate, wallpaper, AOD → display page; inspect actual available options and model support.
- DND, notification permissions/channels → notifications or sound page as appropriate; identify exact app/channel before changing.
- App permissions, special access, app info → apps/privacy/accessibility page; exact route depends on OS build.
- Battery saver, app background activity, auto-launch → battery page; only modify Jarvis-specific settings when requested and explain battery trade-offs.
- Storage, date/time, language, security, developer options → matching page; do not perform destructive or security-sensitive changes automatically.

## 4. Minimize delay without losing verification
- Do not open Settings if a native tool can perform the requested action.
- Do not inspect the screen for a simple native action unless verification is needed or the result is uncertain.
- For Settings workflows, inspect once after opening the target page; use the visible control and verify once after the action.
- Avoid repeated screenshots, repeated page launches, fixed long waits, speculative coordinates and redundant relaunches.
- Reuse only fresh foreground/UI context. If the screen changed or app resumed, inspect again before clicking.
- Never report success from an attempted action alone. Require a successful tool result or visible state confirmation.
- If the action is unavailable, stop at the nearest useful settings page and explain the shortest manual step.

## 5. Confirmation and privacy rules
- Read-only status and opening a settings page can proceed when requested.
- An explicit ordinary reversible setting may be changed when a supported tool permits it; warn first if it may disconnect internet, interrupt a call/audio, affect tracking or pause Jarvis.
- Ask immediately before enabling/disabling sensitive permissions or special access (camera, microphone, location, contacts, photos/files, SMS, call logs, notification access, accessibility, overlays, modify-system-settings), changing security/account settings, clearing data/cache where user data may be affected, uninstalling, deleting, resetting or changing device admin.
- Never silently grant permissions or accept Android consent screens. The user must complete PIN, OTP, biometric, account, consent and lock-screen steps.
- Never expose passwords, OTPs, recovery codes, Wi-Fi credentials, IMEI, serial number or private notification content.
- Never bypass screen lock, biometric, permission prompts, privacy indicators, app sandbox or OS policy.
- No root, ADB/shell execution, hidden/OEM-private APIs, firmware flashing, bootloader unlocking or security bypass.

## 6. Device controls Jarvis cannot promise
Android/OEM policy and hardware vary. A settings page may be opened without being able to toggle its control. Do not claim universal control of Wi-Fi, Bluetooth, airplane mode, mobile data, GPS/location, NFC, hotspot, default apps, app permissions, secure settings, lock screen, biometric, developer options, system updates or power/reboot. Use only an actual available Jarvis tool or visible user-approved UI route. Some controls require the user to act directly.

## 7. Common safe workflows
### "Make my phone faster"
1. device_status(); inspect battery and storage status if exposed.
2. Open battery/storage pages only if needed.
3. Identify a specific bottleneck; do not run cleaners, kill all apps or clear data.
4. Offer reversible, targeted settings and explain trade-offs before changing.

### "Jarvis should keep running in background"
1. Open battery page and inspect Jarvis-specific app controls.
2. Explain that allowing background activity may increase battery use and still cannot guarantee uninterrupted execution.
3. Ask before changing any app-specific battery or auto-launch setting.
4. Verify the visible setting after user-approved change.

### "Turn off all interruptions"
Do not blindly disable every notification, radio or alarm. Clarify whether the user means DND, silent mode, calls, notifications or alarms; preserve emergency contacts and alarms unless explicitly asked.

### "Control all settings"
Explain that Jarvis can navigate and control supported settings, while Android reserves some actions for the user. Offer a category-by-category audit rather than pretending to have unrestricted OS control.

## 8. Response format
- Be concise and state which control was changed or which settings page was opened.
- Distinguish "opened", "requested", "changed" and "verified".
- If blocked, name the Android restriction and the one user action needed.
- Never promise zero delay; prioritize native actions and avoid unnecessary navigation.
