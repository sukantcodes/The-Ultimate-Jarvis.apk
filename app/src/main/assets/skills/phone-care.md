---
name: phone-care
description: Phone-wide shared-storage cleanup, memory diagnostics, battery saver, media review and app management
---
# Phone Care and Device Cleanup

The seven Device Diagnostics workflows operate on the **phone**, not just JARVIS. On Android 11+, JARVIS requests the system's special All files access only when the user starts a phone-wide scan. If the user declines, offer a single-folder SAF scan instead.

- **Junk Clean:** scan accessible shared storage for temporary/cache folders and thumbnail files. Show a selectable review list and require confirmation before deletion. Do not describe this as clearing private caches belonging to other apps.
- **Boost:** show available/total RAM and low-memory state, then offer Android's app-management screen. Android owns process lifecycle; never claim to force-stop all apps, empty RAM, or make a permanent speed increase.
- **Battery Saver:** open the device-wide Android Battery Saver page. Report only that the page opened unless the resulting enabled state is actually verified.
- **Large Files Cleanup:** scan accessible shared storage for files of at least 50 MB. Let the user select items and confirm deletion.
- **WhatsApp Clean:** find WhatsApp / WhatsApp Business media in accessible shared storage, review selected files and confirm deletion.
- **Photos Clean:** find image files across accessible shared storage, review selected files and confirm deletion.
- **App Uninstaller:** list installed user apps, exclude JARVIS, and launch Android's native uninstall confirmation for the selected app.

Android sandbox limits remain in force. All files access does not grant access to another app's private internal data or private cache; Android/data and Android/obb are excluded from traversal. The app cannot silently clear another app's cache, force-stop arbitrary apps, uninstall without Android confirmation, or bypass the user's storage permission choice.

Voice examples express intent, not proof of a completed action. Use a registered native action only when one exists; otherwise open Device Diagnostics and let the same UI workflow execute. Never claim a scan, cleanup, boost or uninstall occurred until the native operation returns a verified result.

The scan UI uses original JARVIS progress animation and review states. Do not copy Snaptube's proprietary source code, Lottie files, graphics or exact assets.
