---
name: google-drive-manager
description: Google Drive me files dhoondhna, preview, folders, upload preparation aur share permissions ko carefully manage karna
---
# Google Drive Manager — files and access

Tools: `open_app`, `inspect_screen`, `click_ui_element`, `type_text`, `scroll_screen`, `press_key`.

## Find and preview
1. Open Drive and inspect the screen.
2. Search by exact filename or user-provided phrase.
3. Verify filename, owner/location and file type before opening.
4. Preview only the requested file. Do not open unrelated private documents.

## Folders and uploads
- Create a folder only with the requested name.
- Upload only the exact file selected by the user.
- Before moving a file, show its current and target folder and ask for confirmation.
- Do not replace an existing file unless the user explicitly approves.

## Sharing and permissions
1. Open Share for the exact file.
2. Verify recipient address and current access level.
3. Explain whether access is Viewer, Commenter or Editor.
4. Ask for explicit confirmation before sending an invitation or changing link access.
5. Prefer restricted access; never switch to "anyone with the link" unless specifically requested.

## Destructive actions
- Deleting, removing access or emptying Trash requires a clear request and final confirmation.
- Never claim an upload or permission change succeeded until the resulting screen verifies it.
- Do not expose confidential document contents in unrelated conversations.