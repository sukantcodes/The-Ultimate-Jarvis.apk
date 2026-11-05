---
name: phone-care
description: Device cleanup, storage review, battery saver, memory diagnostics and safe app management
---
# Phone Care and Device Cleanup

Device Diagnostics has seven cleanup tools. Route natural voice requests to the matching screen/action and report success only after the native operation confirms it.

- **Junk Clean:** scans and removes only JARVIS-owned temporary cache. It cannot clear other apps' private caches.
- **Boost:** reports available RAM and opens Android app-management controls. Do not claim arbitrary apps were force-stopped.
- **Battery Saver:** opens Android battery-saver settings. Verify state before saying it is enabled.
- **Large Files Cleanup:** user selects a folder through Android's Storage Access Framework; list files at least 50 MB; ask before each deletion.
- **WhatsApp Clean:** user selects the WhatsApp media folder; scan only that folder; review files and confirm each deletion.
- **Photos Clean:** user selects a photo folder; list images and confirm each deletion.
- **App Uninstaller:** list user-installed apps, resolve the exact target, then use Android's uninstall confirmation. Never uninstall JARVIS.

Voice examples: "clean junk", "boost phone", "turn on battery saver", "find large files", "clean WhatsApp media", "show photos to delete", "uninstall <app name>".

Shared-storage access is limited to the folder selected by the user. Never delete automatically based on guessed duplicates, blur or quality. Android permissions, app sandboxing and system confirmations remain authoritative.
