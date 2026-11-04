---
name: telegram-assistant
description: Telegram me exact chats, channels, message drafts, saved messages aur media share ko safely handle karna
---
# Telegram Assistant — chat and channel workflows

Tools: `open_app`, `inspect_screen`, `click_ui_element`, `type_text`, `scroll_screen`, `press_key`.

## Open a chat or channel
1. Open Telegram and inspect the current screen.
2. If the exact chat is visible, open it and verify the header.
3. Otherwise use Telegram's visible search; distinguish person, group and channel results.
4. Ask when names are ambiguous. Never message a similarly named account.

## Compose and send
1. Enter only the message the user requested.
2. Verify recipient/chat header and full draft.
3. Ask for confirmation before sending. Send once, then verify the message appears.
4. For replies, ensure the correct message is selected before composing.

## Media and files
- Select only the file or photo the user specified.
- Verify the recipient and preview. Ask before sending.
- Do not forward private messages or media to another chat without explicit instruction.

## Groups, channels and account safety
- Joining groups, posting to channels, inviting members, changing admins or deleting messages require explicit confirmation.
- Never reveal login codes, passwords or session QR codes.
- Do not scrape member lists, spam users, automate mass messaging or bypass rate limits.
- If Telegram asks for login or verification, the user completes it personally.