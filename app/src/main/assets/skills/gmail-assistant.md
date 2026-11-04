---
name: gmail-assistant
description: Gmail inbox, exact sender and thread search, compose, reply, attachment review, labels, archive and send-confirmation workflow
---
# Gmail Assistant — complete inbox secretary

Use this skill for Gmail tasks. Real tools: `open_app(app_name)`, `inspect_screen`, `click_ui_element(target_name, element_id)`, `type_text(text, target_input_name)`, `scroll_screen`, `press_key`, `task_completed(summary)`. There is no dedicated Gmail API tool; use the visible Gmail UI only.

## 1. Open and orient
1. `open_app(app_name="Gmail")`; then `inspect_screen`.
2. Confirm the visible account/profile if shown. If multiple accounts are present and the user did not specify one, ask which account.
3. Wait for inbox content to load; inspect again rather than tapping stale controls.

## 2. Search inbox and open a thread
1. Tap Gmail Search and enter the user's exact sender, subject or phrase.
2. Submit, inspect results and compare sender + subject + date.
3. If more than one plausible result exists, list concise options and ask. Never guess the recipient/thread.
4. Open only the selected thread. Summarize only content visible in that thread; don't inspect unrelated mail.

## 3. Compose or reply
1. For a reply, verify the thread header and recipient before tapping Reply.
2. For new mail, tap Compose. Fill To, Subject and body only from user-provided details.
3. Do not invent facts, attachments, promises, dates or signatures. Ask one question if a required detail is missing.
4. Inspect the entire draft, recipient and subject. Read back a concise preview.
5. Stop and ask: "Send this email?" Send only after a clear confirmation in a new user turn.
6. After tapping Send, inspect Sent or the thread. Say sent only if the result is visibly confirmed; never send again just because the screen is slow.

## 4. Attachments
- Attach only the exact user-selected file through Android's picker.
- Verify filename and type in the draft before sending.
- Do not open or execute unknown attachments; do not attach an entire folder.

## 5. Organize mail
- Archive, label, star, mark unread or move to Spam only when explicitly requested and only for the verified thread.
- Before Delete or Empty Trash, identify exact message/thread and obtain final confirmation.
- Never bulk-select or bulk-delete unless user specifies exact scope and confirms.

## 6. Privacy and sign-in
- Never expose passwords, OTPs, recovery codes or full payment details.
- User handles sign-in, 2-step verification, CAPTCHA and account recovery personally.
- Do not change forwarding, filters, delegation, security or recovery settings.
- If Gmail UI differs, inspect and adapt; don't invent buttons.

## Voice examples
"Open Gmail"; "Find the email from [sender]"; "Reply [text]"; "Draft an email to [address]"; "Archive this email".
