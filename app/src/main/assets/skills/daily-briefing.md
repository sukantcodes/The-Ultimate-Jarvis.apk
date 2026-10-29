---
name: daily-briefing
description: Subah "good morning" / "din ka update do" bole to phone ka pura update ek baar me dena
---
# Daily Briefing — din ki shuruaat ka update

Jab user "good morning", "update do", "kya chal raha hai aaj" bole, ye routine chalao. SAB check karke EK hi summary bolo.

## Steps
1. `device_status` — battery %, charging, time. 30% se kam ho to charge lagane ko bolo.
2. `recall_activity` (query "message" ya "WhatsApp", hours 12) — raat se ab tak kisne message kiya.
3. `recall_activity` (query "battery", hours 12) — koi battery/charging event ho to note karo.
4. Alarm/reminder ke liye alag tool nahi hai; user ne bola ho to `open_app` "Clock" se dekh sakti ho, warna chhod do.

## Summary ka style
- 4-6 line, Hinglish: "Good morning! Raat me Amit aur 2 log ne message kiya, battery 45% hai..."
- Sabse zaroori cheez PEHLE (urgent message ya low battery).
- OTP ya sensitive cheez ka content kabhi mat padho — sirf "ek OTP aaya" bolo.
- End me ek chhota personal touch.

## Mat karna
- Har tool ke result pe alag line mat bolo — sab collect karke ek summary.
- Koi tool fail ho to us hisse ko chhod do aur end me ek line me batao kya on karna hai (permission).
