---
name: phone-care
description: Device cleanup, storage review, battery saver, memory diagnostics and safe app management
---
# Phone Care and Device Cleanup

Device Diagnostics contains seven workflows: Junk Clean, Boost, Battery Saver, Large Files Cleanup, WhatsApp Clean, Photos Clean and App Uninstaller.

Use only tools that are actually registered. For JARVIS-owned temporary cache, use the existing cache-cleaning action and report its returned result. For device information, use the existing device-status action. For Battery Saver, open the supported Android battery settings page and let the user confirm any system switch. Do not invent tool names for the other workflows or claim they were executed by voice until a native voice route exists; guide the user to Settings > Device Diagnostics and the relevant workflow.

- **Junk Clean:** only JARVIS-owned temporary cache can be removed by the cache-cleaning action. It cannot clear another app's private cache.
- **Boost:** report available RAM and low-memory state; do not claim arbitrary apps were force-stopped or RAM was permanently increased.
- **Battery Saver:** open Android battery settings. Verify the resulting state before saying it is enabled.
- **Large Files Cleanup:** user selects a folder through Android's Storage Access Framework; list files at least 50 MB and ask before deletion.
- **WhatsApp Clean:** user selects the WhatsApp media folder; scan only that folder; review files and confirm each deletion.
- **Photos Clean:** user selects a photo folder; list images and confirm each deletion.
- **App Uninstaller:** list user-installed apps, resolve the exact target, then use Android's uninstall confirmation. Never uninstall JARVIS.

Voice examples: "clean junk", "boost phone", "turn on battery saver", "find large files", "clean WhatsApp media", "show photos to delete", "uninstall <app name>". Treat these as intent examples, not proof that every command is currently wired to a native operation. If no direct action is available, explain and navigate the user to Device Diagnostics.

Shared-storage access is limited to the folder selected by the user. Never delete automatically based on guessed duplicates, blur or quality. Android permissions, app sandboxing and system confirmations remain authoritative.
