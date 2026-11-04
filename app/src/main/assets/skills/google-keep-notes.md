---
name: google-keep-notes
description: Google Keep notes, checklists, labels, colors, reminders, collaboration and safe editing workflow
---
# Google Keep Notes — complete note organizer

Real tools: `open_app(app_name)`, `inspect_screen`, `click_ui_element(target_name, element_id)`, `type_text(text, target_input_name)`, `scroll_screen`, `press_key`, `task_completed(summary)`. No dedicated Keep data API is declared; work through visible UI.

## 1. Find a note
1. Open Google Keep and inspect current screen/account.
2. Search exact title or distinctive phrase supplied by user.
3. Verify title and preview before opening. Ask if multiple notes match.
4. Read only the requested note, not unrelated notes.

## 2. Create a note
1. Ask for a title if the user wants one but hasn't supplied it; otherwise use a sensible short title.
2. Tap the appropriate text note or checklist control.
3. Enter the user's wording faithfully. Do not add personal information or assumptions.
4. Inspect full content and verify autosave/visible note list before saying done.

## 3. Checklist
- Use checklist mode only when requested.
- Preserve item order and wording.
- Mark an item complete only when user says it is done; don't infer completion.
- Add/remove/reorder only the requested items.

## 4. Edit, label and color
1. Open and verify exact note.
2. Make only the requested change; preserve all other text.
3. Apply label/color only when named or clearly requested.
4. Inspect the saved note and verify changes.

## 5. Reminders and collaboration
- Ask for exact date/time if a reminder request is ambiguous.
- Verify the reminder chip/date/time after saving.
- Sharing gives another person access to note contents. Verify recipient and ask before sending invitation.
- Do not change public access or invite multiple people without explicit instruction.

## 6. Delete and privacy
- Archive or delete only the exact note requested.
- Before delete, state note title and obtain confirmation. Never empty Trash automatically.
- Never search for passwords, recovery codes or sensitive data unless user explicitly identifies the note; do not repeat such secrets aloud.

## Voice examples
"Create a note called [title]"; "Make a checklist"; "Find my [topic] note"; "Add [item] to [note]".
