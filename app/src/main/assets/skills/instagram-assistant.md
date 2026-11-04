---
name: instagram-assistant
description: Instagram profile and feed navigation, Reels, saved posts, DM composition, Stories, post publishing and account privacy workflow
---
# Instagram Assistant — detailed social workflow

Real tools: `open_app(app_name)`, `inspect_screen`, `click_ui_element(target_name, element_id)`, `type_text(text, target_input_name)`, `scroll_screen`, `press_key`, `task_completed(summary)`. No dedicated Instagram API tool is declared; use the visible app UI.

## 1. Navigate and search
1. Open Instagram and inspect current tab/account.
2. Navigate to Feed, Explore, Reels, Profile, Saved or Messages only as requested.
3. Search exact handle/name. Verify username and profile details; ask if multiple accounts match.
4. Do not claim access to private content or infer identity beyond visible profile information.

## 2. Reels and saved posts
- Open the exact Reel/post requested and verify creator/context.
- Save/unsave only when explicitly requested and verify the bookmark state.
- Like/unlike only the specific post the user identified.
- Never automate mass likes, follows, comments or engagement.

## 3. Direct messages
1. Open Messages and verify the exact username/chat header.
2. Compose only the user's requested text.
3. Review recipient and full draft; ask before sending.
4. Send once and verify the message bubble. Do not retry blindly.
5. Do not read unrelated DMs or reveal private conversation contents.

## 4. Story or feed post
1. Select exact photo/video and verify preview.
2. Add caption, tags, location or audience only as explicitly requested.
3. Review account, media, caption and audience.
4. Stop before Share/Post and ask for confirmation.
5. Publish once; verify visible post/story. Never claim upload succeeded without proof.

## 5. Comments and interactions
- Prepare the exact comment requested; ask before posting.
- Do not post repeated comments, spam or engage with accounts at scale.
- Do not tag people or add location without explicit instruction.

## 6. Account safety
- Never enter password/OTP, bypass challenges or expose recovery information.
- Do not change account privacy, security, contact syncing or linked accounts without explicit request.
- If Instagram requires login or a challenge, user completes it personally.

## Voice examples
"Open my Instagram profile"; "Find [username]"; "Open saved posts"; "Draft a DM to [username]"; "Prepare this photo as a Story".
