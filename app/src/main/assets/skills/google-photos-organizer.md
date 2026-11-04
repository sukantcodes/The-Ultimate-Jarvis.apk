---
name: google-photos-organizer
description: Google Photos search, photo details, albums, favorites, archive, sharing and backup controls with privacy checks
---
# Google Photos Organizer — full photo workflow

Real tools: `open_app(app_name)`, `inspect_screen`, `click_ui_element(target_name, element_id)`, `type_text(text, target_input_name)`, `scroll_screen`, `press_key`, `task_completed(summary)`. There is no dedicated Google Photos API tool; use visible UI.

## 1. Find and verify media
1. Open Google Photos and inspect current tab/account.
2. Search only the date, description, place or filename the user supplied.
3. Inspect matching thumbnails and details. Ask if multiple items fit.
4. Do not infer a person's identity or sensitive traits from a photo.
5. Do not open unrelated albums or private media.

## 2. Album management
1. Create an album only when requested; use the exact title.
2. Select only the items the user identifies.
3. Verify selected count and album title before saving.
4. Inspect album after save and verify items appear.
5. Adding media to an album should not delete or move originals.

## 3. Favorites, archive and details
- Favorite/unfavorite only the selected item and verify state.
- Archive only when requested; explain it hides the item from main views but does not delete it.
- Read date, size or location only when visible and relevant.
- Never alter location metadata or date/time without explicit instruction.

## 4. Share media
1. Select exact photos/videos and verify selection count.
2. Open share sheet; verify destination/app and recipient.
3. Stop before final Share/Send and ask for confirmation.
4. Share once; verify result. Never send duplicates after a delay.
5. Do not create public links or change link access unless explicitly requested.

## 5. Backup and privacy
- Cloud backup may upload personal media and use account storage. Never enable/disable backup without explaining and obtaining approval.
- Do not enable face grouping, partner sharing or location sharing on your own.
- Do not empty Trash or permanently delete media.
- If app requires sign-in or account verification, user completes it personally.

## Voice examples
"Find yesterday's photos"; "Create an album named [title]"; "Favorite this photo"; "Prepare these photos to share with [recipient]".
