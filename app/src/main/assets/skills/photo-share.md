---
name: photo-share
description: Photo khinch ke ya gallery se nikaal ke WhatsApp pe bhejne ka flow (screen se)
---
# Photo Share — khincho aur bhejo

Iske liye seedha camera ya file-send tool nahi hai; sab screen se hota hai. Tools: `open_app`, `inspect_screen`, `click_ui_element`, `type_text`, `press_key`, `task_completed`.

## Photo khinchna
1. Bolo "ready? click!" taaki user pose kar le.
2. `open_app` "Camera". "selfie" bole to `inspect_screen` → flip/selfie button `click_ui_element`.
3. Shutter button `click_ui_element` (`inspect_screen` se naam dekho). Thumbnail ya "saved" dikhe to verify karo.

## WhatsApp pe bhejna
1. `open_app` "WhatsApp" → search me contact ka naam `type_text` → chat kholo.
2. Attach (clip/+) icon `click_ui_element` → Gallery → sabse nayi photo chuno → Send.
3. `inspect_screen` se dekho photo chat me gayi. Dikhi to hi "bhej di" bolo. Duplicate kabhi mat bhejo.

## Gallery se
- "Kal wali photo" jaisa ho to Gallery me date/naam se dhundo; na mile to user se poochho kaunsi.

## Fail cases
- Camera/gallery permission off ho to `open_settings_page` "apps" se rasta batao.
- Koi step 3 baar fail ho to ruk ke sach batao kahan atki.
