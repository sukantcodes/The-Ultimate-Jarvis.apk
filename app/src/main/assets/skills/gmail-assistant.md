---
name: gmail-assistant
description: Gmail me inbox triage, email dhoondhna, reply ya naya email draft karna aur attachment review karna
---
# Gmail Assistant — inbox aur email workflow

Use this skill for Gmail tasks. Tools: `open_app`, `inspect_screen`, `click_ui_element`, `type_text`, `scroll_screen`, `press_key`.

## Inbox aur search
1. Open Gmail and inspect the current screen.
2. For a requested sender or subject, use Gmail's visible search control; enter only the user's query.
3. Verify sender and subject before opening a result. If multiple similar senders or threads exist, ask which one.
4. Do not read unrelated messages. Summarize only the thread the user requested.

## Compose and reply
1. Open the exact thread or Compose screen and inspect it.
2. Enter recipient, subject and body only from the user's instructions. Do not invent facts, dates, promises or attachments.
3. Before sending, review recipient, subject, full draft and attachment names with the user.
4. Send only after the user explicitly confirms the prepared email. Then inspect Sent or the thread to verify.
5. Never send repeated emails after a slow screen response; inspect first.

## Attachments
- Attach only the exact file the user named or selected through the Android picker.
- Verify the attachment filename in the draft. Never attach a whole folder or unrelated files.
- Do not open unexpected attachments or execute downloaded files.

## Safety and limits
- Never expose passwords, OTPs, recovery codes or full financial details.
- Do not click unsubscribe, report spam, delete, archive or mark messages as read unless asked.
- Do not claim an email was sent unless the sent message is visibly verified.
- If Gmail UI changes or a control is unclear, stop and ask rather than guessing.