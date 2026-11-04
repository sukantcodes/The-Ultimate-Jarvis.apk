---
name: google-drive-manager
description: Google Drive file search, preview, upload, folder organization, rename, move, download and granular sharing permissions
---
# Google Drive Manager — complete file operations

Real tools: `open_app(app_name)`, `inspect_screen`, `click_ui_element(target_name, element_id)`, `type_text(text, target_input_name)`, `scroll_screen`, `press_key`, `task_completed(summary)`. No dedicated Drive API tool is declared; all operations use visible Drive UI.

## 1. Search and preview
1. Open Drive and inspect account and current location.
2. Search exact filename or user-provided phrase.
3. Verify filename, file type, owner/location and modified date before opening.
4. Preview only the requested file. Do not inspect unrelated private documents.

## 2. Create and organize folders
- Create folder with the exact user-provided name.
- Before moving, verify source file and destination folder.
- Prefer copy when user asks for a duplicate; move only when explicitly requested.
- Never overwrite an existing file silently. Ask how to handle name conflicts.
- Verify final location after the operation.

## 3. Upload and download
1. Upload only the exact file selected by the user.
2. Verify filename and destination folder before upload.
3. Wait for visible upload completion and verify the file appears.
4. For downloads, verify the selected file and download destination.
5. Never execute downloaded files or install APKs.

## 4. Rename and delete
- Rename only the selected file/folder and preserve extension unless asked otherwise.
- Before deleting, state exact filename and location; ask for confirmation.
- Prefer Trash over permanent deletion. Never empty Trash automatically.

## 5. Share and permissions
1. Open Share for the exact file.
2. Verify recipient email and current role.
3. Explain Viewer, Commenter and Editor access in plain language.
4. Prefer restricted access. Never enable "Anyone with the link" unless specifically requested.
5. Ask for confirmation before sending invitations or changing access.
6. Verify the resulting access list.

## 6. Privacy and account
- Do not disclose confidential document contents in unrelated tasks.
- Never enter passwords, OTPs or recovery codes.
- User handles account verification and CAPTCHA.
- If a screen or permission is unavailable, explain exactly where the task stopped.

## Voice examples
"Find [filename] in Drive"; "Create a folder [name]"; "Move [file] into [folder]"; "Share [file] with [email] as Viewer".
