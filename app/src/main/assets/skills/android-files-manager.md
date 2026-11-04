---
name: android-files-manager
description: Android Files app downloads and documents search, folder creation, file details, safe copy/move/rename/share and trash workflow
---
# Android Files Manager — full local storage workflow

Real tools: `open_app(app_name)`, `inspect_screen`, `click_ui_element(target_name, element_id)`, `type_text(text, target_input_name)`, `scroll_screen`, `press_key`, `task_completed(summary)`. No dedicated file-manager API is declared; use the device's visible Files app and Android picker.

## 1. Locate files
1. Open the device's Files app (or the exact file manager the user named).
2. Inspect current location. Navigate to Downloads, Documents, Images, Audio or the requested folder.
3. Search exact filename/phrase. Verify name, extension, size and modified date.
4. If multiple files match, present choices; never guess.

## 2. File details and preview
- Open only ordinary documents/media selected by the user.
- Verify MIME/type and filename before opening.
- Do not execute scripts, install APKs, launch executables or open unknown archives.
- Never browse app-private folders or bypass scoped-storage restrictions.

## 3. Create, copy and move
1. Create a folder only with the requested name.
2. Before copy/move, verify source and destination.
3. Preserve original when user says copy; move only when explicitly requested.
4. If destination already contains same name, stop and ask; never overwrite silently.
5. Verify the resulting file at destination.

## 4. Rename
- Confirm exact selected file and new filename.
- Preserve file extension unless the user explicitly requests a change.
- Check for name conflicts before applying.
- Verify the new name after rename.

## 5. Share
1. Select exact file and open Android Sharesheet.
2. Verify destination app/contact and file count.
3. Ask before final share/send. Share once and verify.
4. Do not share folders or unrelated files.

## 6. Delete and storage
- Show exact filename and location before moving to Trash; require confirmation.
- Prefer Trash when available. Never empty Trash automatically.
- Do not claim to free "all phone storage"; only the exact selected items are affected.
- Do not change permissions or grant broad storage access as a workaround.

## Voice examples
"Find [filename] in Downloads"; "Create folder [name]"; "Copy [file] to Documents"; "Show details of [file]".
