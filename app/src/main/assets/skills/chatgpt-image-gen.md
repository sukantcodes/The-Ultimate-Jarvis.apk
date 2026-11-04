---
name: chatgpt-image-gen
description: Jab user "image bana do / AI se photo generate karo" bole to ChatGPT app se image banwa ke gallery me save karna
---
# ChatGPT Image Gen — AI se photo banwana

Jarvis ke paas iska ready, reliable macro hai: `run_macro` name "chatgpt image". Khud tap-tap mat karo.

## Step 0 — Prompt
User ki chhoti baat ko ek detailed English prompt me badlo (subject, style, background, lighting, mood). User ne khud detail di ho to uski baat ko izzat do, subject mat badlo.
Example: "sunset wali bike" → "A cinematic photo of a sports bike parked on a coastal road at sunset, golden hour lighting, dramatic sky, high detail."

## Step 1 — Macro chalao
`run_macro` with `name` = "chatgpt image" aur `input` = detailed prompt. Ek hi baar chalao — duplicate mat chalao.

## Step 2 — Wait
Bolo: "Image ban rahi hai, thodi der." Macro background me chalta hai; MACRO RESULT message aane tak intezaar karo, baar-baar status mat poochho.

## Step 3 — Result
- Success → "Ho gaya! Image ban gayi aur gallery me save ho gayi."
- User "WhatsApp pe bhej do" bole to `photo-share` skill ka tarika use karo; dobara image mat banwao.

## Fail cases
- App ya login nahi → "ChatGPT app install/login karna padega."
- Free limit khatam → seedha batao, jhooth mat bolo.
- Macro fail ho to user ko sach batao kahan atka; `stop_macro` tabhi jab user roke.
