---
name: whatsapp-direct-control
description: WhatsApp ko direct navigate karna — Updates/Status, exact chat, aur gallery media share ko safe confirmation ke saath control karna
---
# WhatsApp Direct Control — fast, screen-grounded navigation

Is skill ko WhatsApp navigation aur media sharing requests par pehle use karo. Existing `whatsapp-pro` aur `photo-share` skills ke saath kaam karo; unke tools ko dobara implement mat karo.

Available real tools include: `open_app`, `inspect_screen`, `click_ui_element`, `type_text`, `scroll_screen`, `press_key`, `send_whatsapp_message`, `whatsapp_status_action`, `use_skill`.

## Core rules
- Navigation requests may be carried out immediately: open WhatsApp, open the visible Updates/Status area, or open a clearly identified visible chat.
- Before every UI click, inspect the current screen. Click only a control whose visible text, content description, or UI identity matches the requested action.
- Never guess coordinates, contact identity, or which media item the user meant.
- Never claim an action succeeded unless a fresh screen inspection verifies the destination or result.
- Do not read or disclose private message contents. Only inspect the minimum UI needed to navigate.
- Do not bypass WhatsApp login, app lock, device authentication, privacy settings, or Android permission prompts.

## Open WhatsApp Status / Updates
1. `open_app` with app name `WhatsApp`.
2. `inspect_screen` and identify the visible navigation item labelled `Updates`, `Status`, or the equivalent in the current WhatsApp version.
3. Click that exact visible navigation item with `click_ui_element`.
4. Inspect again. Confirm the Updates/Status screen is visible. WhatsApp versions may place Status under Updates; do not assume a fixed layout.
5. If the expected control is not exposed, report that the current screen does not expose a safe target. Do not tap guessed positions.

## Open a person's chat
1. Resolve the exact contact name from the user's words. If the name is ambiguous, ask which contact.
2. `open_app` WhatsApp and inspect the current screen.
3. If the exact person's chat is already visible in the chat list, click that exact row and verify the chat header.
4. If it is not visible, do not open a similarly named conversation or claim search-free direct navigation. Use only a supported contact/deep-link tool if one is explicitly available in the current tool list; otherwise explain that direct contact-to-chat navigation is not yet exposed and ask before using WhatsApp's search UI.
5. Do not use a message-send tool merely to open a chat.

## Send a photo to a person
1. Use the `photo-share` skill for the gallery-selection flow.
2. Identify the exact recipient first. If multiple contacts match, ask the user to choose.
3. Open the recipient's chat and verify the chat header.
4. Open the attachment/gallery picker and select only the photo the user identified. If "that photo" is unclear, ask which one.
5. Stop at the WhatsApp preview/composer. Show or describe the selected recipient and media item, then ask for explicit confirmation.
6. Only after the user confirms, use the visible WhatsApp Send control. Inspect the resulting chat and verify the sent media bubble before reporting success.
7. Never send twice because a UI response is slow or unclear.

## Set a photo as Status
1. Open WhatsApp and navigate to Updates/Status as described above.
2. Use `whatsapp_status_action(open_composer)` to open the user's own Status composer (for example, `My status` / `Add status`) only when clearly visible.
3. Use `whatsapp_status_action(open_gallery)` to open the gallery. The user must identify/select the specific photo; do not choose the newest item automatically unless the user explicitly said the newest photo.
4. Inspect the preview and verify the chosen image. Stop before publishing and ask for confirmation.
5. After explicit confirmation, call `whatsapp_status_action(publish, confirmed=true)` once. Inspect the screen to verify the Status was posted.
6. If the UI indicates an audience selector or privacy setting, do not change it without the user's instruction.

## Confirmation and recovery
- Opening screens and chats is navigation; it does not require a send confirmation.
- Sending a message/photo or publishing a Status is an external action and requires a clear, current confirmation naming the recipient or Status.
- A vague response, silence, or an unrelated command is not confirmation.
- If a click fails, inspect the screen again before any retry. Never repeat a send/publish action unless the screen proves the first attempt did not happen.
- If a WhatsApp update changes the layout, re-ground every step from the current screen instead of relying on old coordinates or IDs.

## Honest capability limits
- Do not promise zero delay. Android app launch, WhatsApp loading, media scanning, and accessibility event timing are controlled by the OS and WhatsApp.
- Direct opening of a chat without WhatsApp's search requires a supported contact-to-chat deep link or an exposed contact resolver action. If neither is available, say so rather than simulating a shortcut.
- WhatsApp does not expose a public, stable Android API for every internal page. Status navigation may require visible UI interaction and can vary by app version.
