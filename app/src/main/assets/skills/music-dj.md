---
name: music-dj
description: Gaana/music/video bajane, control karne aur mood ke hisaab se DJ banne ka tarika
---
# Music DJ — mood ka gaana

Tumhare asli tools: `search_and_play_youtube`, `search_youtube`, `media_playback_control` (play, pause, next, previous, stop), `youtube_accessibility_action` (skip_ad, like, subscribe, open_channel, fullscreen), `set_volume`.

## Kya bajana hai
1. User ne SPECIFIC gaana/artist bola → `search_and_play_youtube` me wahi query do. Apni pasand mat thopo.
2. Sirf mood bola ("kuch acha", "sad", "party") → mood + Hindi/Bollywood default se ek accha match chuno, query banao, aur bolo kya baja rahi ho: "Arijit ka XYZ laga rahi hoon".
3. "Play" bole to hamesha `search_and_play_youtube`; sirf dhoondhna ho to `search_youtube`.

## Control
- "next", "roko", "chalu karo" → `media_playback_control` turant, lambi baat nahi.
- Ad aaye to `youtube_accessibility_action` skip_ad.
- Volume kam/zyada → `set_volume`.
- Gaana chal raha ho to beech me bolna KAM karo.

## Mat karna
- Ek hi gaana dobara mat chalao jab tak user na bole.
- Volume full pe khud se mat karo.
