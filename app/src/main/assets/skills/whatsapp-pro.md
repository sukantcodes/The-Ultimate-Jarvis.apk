---
name: whatsapp-pro
description: WhatsApp ka koi bhi kaam (message, group, call, reply) galti-free tarike se karna
---
# WhatsApp Pro — bharosemand messaging

WhatsApp se juda koi bhi kaam karne se pehle ye tarika follow karo. Tumhare asli tools:
`send_whatsapp_message`, `whatsapp_call`, `recall_activity`, `inspect_screen`, `click_ui_element`, `type_text`.

## Message bhejna
1. `send_whatsapp_message` use karo: `recipient_name` = naam EXACTLY jaisa user ne bola, `message` = user ka text.
2. Dual WhatsApp (clone) ho aur user ne bola ho "dusre WhatsApp se" to `app_number` 2 do, warna mat do.
3. User ne sirf number bola ho to number hi `recipient_name` me daalo.
4. Tool ka result "queued/opened" bole to `inspect_screen` se chat dekho: message dikh raha hai to hi bolo "bhej diya". Nahi dikha to sach batao.

## Ek hi baar bhejna
- Jab chat me message dikh jaye to kaam KHATAM. Wahi message dobara kabhi mat bhejo.

## Notification wale ko reply
- `recall_activity` (query = "WhatsApp") se dekho kisne message kiya. Reply usi naam ko bhejo jo log me hai.

## Padhna
- "Kisi ka message aaya?" → `recall_activity` with query "WhatsApp". Message ka content Jarvis ke paas nahi hota — sirf kaun aur kitne, ye batao.

## Call
- `whatsapp_call`: `recipient_name` + `call_type` ("voice" ya "video").

## File / photo bhejna
- Iske liye alag tool nahi hai. WhatsApp kholo (`open_app`), chat kholo, attach (clip) icon pe `click_ui_element`, Gallery chuno, photo chuno, send dabao — har step ke baad `inspect_screen` se verify karo.

## Mat karna
- Group ka naam guess mat karo; `inspect_screen` se chats ki list dekh ke exact naam lo.
- Kisi ka private message content kabhi mat bolo jo tumhe mila hi nahi.
