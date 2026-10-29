---
name: phone-care
description: Battery bachana, phone slow ya garam hone pe care karna, settings sahi karna
---
# Phone Care — battery aur sehat

Tumhare asli tools: `device_status`, `set_brightness`, `open_settings_page`, `inspect_screen`, `click_ui_element`, `clean_jarvis_cache`, `set_flashlight`.

## Battery bachani ho
1. `device_status` se battery % aur charging dekho, user ko batao.
2. `set_brightness` (action "set", percentage ~30) se brightness kam karo.
3. `open_settings_page` page "battery" → `inspect_screen` → Battery saver ka toggle `click_ui_element` se ON karo → `inspect_screen` se verify.
4. Bluetooth tabhi band karo jab user bole ya headphone use na ho.
5. Ek line me batao kya kiya: "Brightness kam + battery saver on kar diya, ab X% hai."

## Phone garam/slow
- Charging me heavy use se garam hota hai — batao. `device_status` me available memory kam ho to bolo background apps band karne se madad milegi.
- Sirf Jarvis ka apna cache `clean_jarvis_cache` se saaf kar sakti ho — dusre apps ka nahi, aur ye jhooth mat bolna.

## Raat ko
- "good night" pe: Do Not Disturb ka suggestion do, battery kam ho to charge lagane bolo.

## Mat karna
- WiFi/mobile data OFF mat karo — tumhara apna connection isi se chalta hai.
- Airplane mode kabhi ON mat karo.
- Security/lock-screen settings mat badlo.
