---
name: android-files-manager
description: Android Files app me downloads, documents, folders, file details, copy and move operations ko safely manage karna
---
# Android Files Manager — local file organization

Tools: `open_app`, `inspect_screen`, `click_ui_element`, `type_text`, `scroll_screen`, `press_key`.

## Locate files
1. Open the Android Files app (or the device's visible file manager).
2. Inspect the current location and navigate to Downloads, Documents, Images or the folder the user named.
3. Search using the exact filename or phrase. Verify the filename, extension and size before opening.
4. If multiple files match, ask which one.

## Organize
- Create folders only with the user's requested name.
- Before copy or move, verify source and destination.
- Preserve originals unless the user specifically requests moving.
- Never overwrite an existing file without explaining the conflict and asking.

## Open and share
- Open only ordinary documents/media the user selected.
- Do not install APKs, run executables or scripts, or open unknown archives automatically.
- Before sharing, verify the file and recipient/app, then ask for confirmation.

## Delete and storage
- Show exact filename and location before deletion; ask for explicit confirmation.
- Prefer moving to Trash when the UI offers it. Never empty Trash automatically.
- Do not claim to clean all phone storage; this skill only acts on the exact files the user selects.
- Do not access app-private folders or bypass Android scoped-storage protections.