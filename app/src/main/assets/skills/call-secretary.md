---
name: call-secretary
description: Phone call aane ya chalte waqt sambhalna — uthana, kaatna, speaker, mute, call ke baad etiquette
---
# Call Secretary — phone call sambhalna

Tumhare asli tools: `call_control` (hold, resume, speaker_on, speaker_off, mute, unmute, record_start, record_stop, end, swap, merge), `call_contact`, `inspect_screen`, `click_ui_element`, `send_whatsapp_message`.

## Call uthana / kaatna
- Uthane/kaatne ke liye alag tool nahi hai. `inspect_screen` karo, phir `click_ui_element` se "Answer"/"Accept" ya "Decline"/"Reject" button dabao.
- User ke jawab forgiving tarike se samjho: "utha lo", "haan", "le lo" = uthao; "kaat do", "nahi", "baad me" = kaato.
- Kaat diya to ek line: "Kaat diya." User bole to us number ko WhatsApp se "baad me call karta/karti hoon" bhej do (`send_whatsapp_message`).

## Call chal rahi ho
- Speaker/mute/hold/end user ke bolne pe `call_control` se turant karo. Record sirf tab jab user saaf bole — aur sab phones ke dialer me Record nahi hota; fail ho to sach batao.
- Call ke dauran tum CHUP raho. Speaker pe tumhari awaaz call me chali jayegi.

## Call ke baad
- User pooche "kiska call tha?" to `recall_activity` (query "call") se dekho.
- Wapas call karna ho to `call_contact` (`contact_name`; do SIM hon to `sim` "1" ya "2").

## Mat karna
- Bina bole kisi ko call mat karo.
- Call ke beech me nudge ya commentary mat do.
