---
name: android14-coloros
description: Android 14 aur ColorOS 14 ka system blueprint; settings, permissions, battery, display, sound, connectivity aur troubleshooting
---
# Android 14 + ColorOS 14 — Jarvis system blueprint

Is skill ko OS/settings, phone navigation, permissions, notifications, battery, display, sound, connectivity, storage, accessibility aur troubleshooting ke liye use karo.

## Device identity first
- Android 14 + ColorOS 14 user ka target hai, lekin exact model, region aur firmware build ke hisaab se menus/features badal sakte hain.
- Device-specific task mein Settings > About device/About phone kholo (open_settings_page("main") ya Settings app), inspect_screen se model, Android version aur ColorOS version verify karo. IMEI, serial, phone number ya account details kabhi repeat mat karo.
- Actual visible labels source of truth hain. Guess coordinates, stale element IDs ya doosre ColorOS version ke paths blindly use mat karo.

## Available Jarvis tools
Use only real tools:
- open_settings_page(page): supported pages include wifi, bluetooth, display, sound, location, airplane, apps, accessibility, security, battery, date, language, storage, nfc, mobile_network, privacy, notifications, developer, main.
- inspect_screen(): current UI and visible element IDs.
- click_ui_element(target_name, element_id): only a fresh visible element from inspect_screen.
- scroll_screen(direction), press_key(key): only when needed and supported.
- open_app(app_name): launch an installed app.
- device_status(): available device/battery status.
- set_brightness, set_volume, set_flashlight: use only their declared parameters; never invent arguments.
- run_macro(name, input): only for an exact known saved macro.
If a tool or parameter is unavailable/unclear, don't guess it. Explain the limitation or use verified UI.

## Fast path: minimize delay safely
1. If a native tool directly supports the requested action (brightness, volume, flashlight, device status), use it first. Don't open Settings unnecessarily.
2. For a system setting, open its direct Settings page with open_settings_page.
3. Inspect screen, click only the exact visible control, then inspect again to verify.
4. If page mapping fails, open Settings main once and use visible search/navigation. No blind repeated taps.
5. Say completed only after tool result or screen confirms it. If Android presents a consent, PIN, OTP, reboot or sign-in step, hand control to the user.

## OS blueprint and workflows

### Navigation and apps
- "Open [app]" → open_app(app_name). If two cloned instances exist and user didn't specify which, ask.
- "Settings kholo" → open_settings_page("main").
- Home, Recents, Back and gestures vary by launcher/navigation mode. Inspect first; don't invent swipe coordinates.
- Never force-stop, uninstall, clear app data or reset without explicit fresh confirmation and data-loss warning.

### Quick Settings and connectivity
- For Wi-Fi, Bluetooth, Airplane mode, NFC, mobile network and Location: open the relevant page, inspect the actual switch and verify state.
- Android restricts third-party apps from changing some global radio states. If blocked, show the relevant system page/panel and let the user tap. Never use root, shell, hidden/OEM APIs or accessibility to bypass.
- Warn before a toggle that can interrupt calls, internet, paired audio, location or Jarvis connectivity. Don't change network state unless requested.
- Never reveal/store Wi-Fi passwords, join an ambiguous network, install VPN/profile or weaken network security without clear user intent and system approval.

### Notifications and DND
- Open notifications settings and inspect app + notification channel. Verify exact app before muting/allowing.
- DND schedules, allowed callers, alarm exceptions and app exceptions must match the user's stated request.
- Notification content is private. Read only what was requested; don't expose OTPs, private messages or financial previews unnecessarily.

### Permissions and special app access
- Open privacy or apps settings, then follow the visible permission-manager route.
- Common ColorOS 14 routes include Settings > Privacy > Permission manager; app info > Permissions/Manage notifications/Battery usage; and Settings > Apps > Special app access. Paths may differ by build.
- Treat camera, microphone, contacts, location, photos/videos, files, SMS, call logs, notification access, accessibility, overlays and modify-system-settings as high-impact.
- Before granting: identify app, exact permission and purpose; ask for confirmation; choose the minimum scope. Prefer one-time/while-in-use/selected photos if offered.
- Never silently enable Jarvis Accessibility, notification access, microphone, overlay or background permissions. Navigate to the setting; user accepts Android's consent screen.
- Never bypass privacy indicators, permission prompts, app sandbox, lock screen or biometric checks.

### Battery and background reliability
- device_status() first. Open Battery page to inspect saver, usage and app-specific background controls.
- ColorOS may show Battery usage, Allow background activity or Auto launch differently by firmware. Use the visible option; do not assume it exists.
- If user wants Jarvis to remain available, adjust only Jarvis-specific controls after explaining battery impact. Never disable restrictions for every app.
- Battery Saver can delay background sync, alerts and voice availability. High performance mode can increase heat and battery use; explain before enabling.
- Don't recommend RAM cleaners, hidden process killers, force-stop-all or third-party boosters. Never clear user data as a speed fix.

### Display, accessibility and sound
- Display page: inspect dark mode, timeout, font/display size, refresh rate, auto-rotate, wallpaper and AOD. Hardware/model support varies.
- Brightness: native set_brightness first; verify the requested level if possible.
- Accessibility: explain captions, magnification, color correction, TalkBack and interaction controls. Changes can alter navigation; ask before enabling/disabling. Never use Accessibility to bypass security.
- Sound: native set_volume first. Identify whether user means media, ringtone, alarm, notification or call volume. Silent mode, DND and media volume are different; verify the right one.

### Location, privacy and security
- Distinguish global Location from per-app location permission.
- Password/PIN/OTP, recovery codes, biometrics, account sign-in, app lock, screen lock, device admin, certificates, encryption, anti-theft and Find My Device remain user-controlled.
- Do not automate security changes, unknown-source installation, USB debugging, developer settings, bootloader/root, firmware flash/downgrade or factory reset. Never bypass a lock or identity check.

### Storage, files and app management
- Storage page can show available space and categories. Report only visible values.
- Cache is not the same as Clear data. Clear data, delete, uninstall, format, empty trash and reset are destructive: state the exact target/effect and get fresh confirmation.
- Respect Android scoped storage and photo picker. Never access another app's private sandbox or protected system partitions.
- Prefer Play Store/App Market for app updates. Don't enable unknown APK sources.

### Date, language and system update
- Inspect the visible date/time, time zone, language, keyboard, region and system update controls.
- Changing date/time can affect alarms, reminders and authentication; confirm if not explicit.
- You may open update page and report visible version/status. Do not flash, downgrade, enroll in beta or interrupt an update. Recommend backup, sufficient battery and trusted Wi-Fi before a user-started update.

## Troubleshooting recipes

### Jarvis closes in background
1. device_status().
2. Open Settings > Apps > Jarvis app info > Battery usage.
3. Inspect visible Jarvis-specific background/auto-launch controls.
4. Explain trade-offs, ask before changing, then verify.
5. Never promise ColorOS will never stop a background process.

### App notifications missing
1. Inspect app notification channels.
2. Check DND, battery/background restrictions and network state.
3. Report the exact visible blocker and possible trade-off.
4. Never use an OTP/private message as a test.

### App slow or crashing
1. Inspect app name and error screen.
2. Check network, free storage, Battery Saver and update status.
3. Suggest safe reopen/update steps first.
4. Cache/data clear, reinstall or reset require backup warning and fresh confirmation.

### Wi-Fi/Bluetooth problem
1. Inspect relevant page and exact network/device name.
2. Warn if reconnecting may interrupt a call/audio/network session.
3. Forget network/unpair only with exact target and confirmation. User enters passwords/OTPs.

## Confirmation rules
- Read-only inspect/status/open-page: proceed when asked.
- Explicit reversible ordinary setting: act and verify; warn about meaningful interruption.
- High-impact permissions, account/security, location sharing, notification access, accessibility, deletion, Clear data, uninstall, reset or irreversible change: explain exact effect and get fresh confirmation immediately before action.
- Never silently grant permissions or alter lock/security settings.
- No root, shell, hidden APIs, OEM-private APIs or security bypasses.
- Never promise zero delay or universal full control. Network, animation, device load, OS policy and firmware can add delay.
- If no supported tool can do it, give the shortest manual path and say what Jarvis cannot perform.

## Example commands
- "Settings kholo"; "Wi-Fi settings kholo"; "Bluetooth page kholo".
- "Battery status batao"; "Brightness 40 percent karo".
- "Dark mode setting dikhao"; "Jarvis ke background battery settings kholo".
- "WhatsApp notifications check karo"; "App permissions page kholo".
- "Storage kitna free hai batao"; "DND settings kholo, enable mat karna".

## Official references
Background references only; they do not guarantee a feature exists on this model:
- OPPO ColorOS 14 overview: https://www.oppo.com/in/coloros14/
- ColorOS 14.0 manual: https://s3.ap-southeast-1.amazonaws.com/sgp-sow-cms-oppo-com/cms/userManual/manual/f888f4f6-4f81-4759-8aaa-115f25e31bb4/ColorOS_14.0_User_Manual_V1.0_20231227.pdf
- ColorOS 14.1 manual: https://s3.eu-west-3.amazonaws.com/par-sow-cms-oppo-com/cms/userManual/manual/3524d044-8881-4c19-b61f-c2da2949c1f7/ColorOS%2014.1_User_Manual.pdf
- OPPO permission manager support: https://support.oppo.com/ph/answer/?aid=neu139

Always prioritize this phone's visible Settings labels and user-approved actions over remembered paths.
