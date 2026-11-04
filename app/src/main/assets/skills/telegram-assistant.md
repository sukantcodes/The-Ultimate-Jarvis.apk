---
name: telegram-assistant
description: Telegram chat and channel navigation, message drafts, saved messages, groups, media, calls and privacy-safe sharing
---
# Telegram Assistant — full messaging playbook

Real tools: `open_app(app_name)`, `inspect_screen`, `click_ui_element(target_name, element_id)`, `type_text(text, target_input_name)`, `scroll_screen`, `press_key`, `task_completed(summary)`. No dedicated Telegram API tools are declared; use the visible app UI.

## 1. Open exact chat
1. Open Telegram and inspect the current screen.
2. If exact chat is visible, verify the header and open it.
3. Otherwise use Telegram's visible search; distinguish private person, group, bot and channel.
4. Compare exact name/username and ask if ambiguous. Never message a similarly named account.
5. Do not read unrelated chats.

## 2. Compose and send message
1. Verify the chat header before typing.
2. Enter only the text requested by the user. Do not add claims or attachments.
3. Inspect the complete draft and recipient.
4. Ask for explicit confirmation before sending.
5. Send once and inspect the chat to verify the message. If result is unclear, inspect before any retry.

## 3. Replies and forwarding
- Verify the exact message bubble before replying.
- Forwarding can reveal private content; verify destination and item, then ask before forwarding.
- Do not quote or expose message contents from unrelated chats.

## 4. Media and files
1. Open attachment menu and inspect available types.
2. Select only the exact user-chosen media/file through the picker.
3. Verify recipient, caption and preview.
4. Ask before final Send; send once and verify.

## 5. Groups, channels and calls
- Joining a group/channel, posting, creating a group, inviting members, changing admin rights or deleting content requires explicit confirmation.
- Do not mass-message, scrape member lists, spam, automate reactions or bypass rate limits.
- Start a call only when user explicitly names the contact and asks to call; verify target first.

## 6. Account safety
- Never expose login codes, passwords, session QR codes or recovery phrases.
- User completes login, 2-step verification and CAPTCHA personally.
- Do not change privacy/phone-number visibility or active sessions without explicit request.

## Voice examples
"Open [person]'s Telegram chat"; "Find channel [name]"; "Draft [message]"; "Send this file to [chat]".
