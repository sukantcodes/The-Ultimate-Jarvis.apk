---
name: android14-coloros
description: Android 14 aur ColorOS 14 ka system blueprint; settings, apps, permissions, battery, display, sound, connectivity aur troubleshooting
---
# Android 14 + ColorOS 14 — Jarvis system blueprint

Is skill ko tab use karo jab user phone ke OS, ColorOS settings, system navigation, permissions, notifications, battery, display, sound, connectivity, storage, accessibility ya troubleshooting ke baare mein bole.

## Sabse pehle: device ko identify karo
1. User ne Android 14 + ColorOS 14 bataya hai, lekin exact OPPO/OnePlus model, region aur firmware build alag ho sakte hain. Kisi feature ko har device par available mat maan-na.
2. Agar user exact model/build nahi batata aur task device-specific hai, Settings > About device/About phone screen kholo (open_settings_page page main ya Settings app), phir inspect_screen se visible model, ColorOS version aur Android version padho. Serial number, IMEI, phone number ya account details kabhi repeat mat karo.
3. Current screen aur actual labels ko source of truth mano. Menu path match na ho to search/inspect karo; purane screenshot, guessed coordinates, ya doosre ColorOS release ke labels blindly use mat karo.

## Real tools — sirf inka use karo
- open_settings_page(page): system Settings ke supported page ko seedha kholta hai. Common page values: wifi, bluetooth, display, sound, location, airplane, apps, accessibility, security, battery, date, language, storage, nfc, mobile_network, privacy, notifications, developer, main.
- inspect_screen(): current visible UI aur element IDs samajhne ke liye.
- click_ui_element(target_name, element_id): sirf fresh inspect result me dikh rahe exact element ko click karo.
- scroll_screen(direction): visible Settings page par zarurat ke mutabik scroll.
- press_key(key): sirf jab task ke liye clear aur supported ho.
- open_app(app_name): installed app kholne ke liye.
- device_status(): available battery/device status dekhne ke liye.
- set_brightness, set_volume, set_flashlight: in tools ke declared parameters ke mutabik hi; unsupported arguments invent mat karo.
- run_macro(name, input): sirf tab jab exact matching saved macro known ho.
Agar koi tool unavailable ho ya parameter unclear ho, uska naam guess karke call mat karo. UI fallback use karo ya limitation batao.

## Fastest reliable execution policy
1. Direct native tool: agar exact supported tool task karta hai (brightness, volume, flashlight, device status), seedha use karo; unnecessary Settings open mat karo.
2. Direct Settings page: system toggle/configuration ke liye open_settings_page se relevant page kholo.
3. Inspect then act: screen inspect karo; exact visible control click karo; action ke baad dobara inspect karke verify karo.
4. Fallback: agar expected page nahi khula, ek baar Settings main page par jao aur visible search/menu use karo. Blind repeated tapping ya random coordinates nahi.
5. Report truthfully: success tabhi bolo jab tool result ya fresh screen se verify ho. Permission dialog, reboot, sign-in, OTP, PIN ya user-only confirmation aaye to user ko control do.

## System map and common workflows

### 1. Home, navigation, recents, app launch
- "Open [app]" → open_app(app_name); duplicate/cloned app ho to instance clear na hone par poochho.
- "Settings kholo" → open_settings_page("main").
- Home/Recents/back gestures device navigation mode aur launcher par depend karte hain. User ke command se pehle screen inspect karo; Android navigation gestures ko guessed swipe coordinates se emulate mat karo.
- App crash/launch failure par app name, visible error aur whether app actually opened verify karo. Force-stop/uninstall/clear-data destructive actions bina explicit confirmation mat karo.

### 2. Quick Settings and connectivity
- Wi-Fi, Bluetooth, Airplane mode, NFC, mobile network aur Location ke liye direct relevant Settings page kholo; screen inspect karo; user ke exact requested state ke hisaab se visible switch use karo.
- Android 14 par third-party apps ke liye kuch global radio toggles restricted hain. Agar direct tool/UI action block ho, system panel user ko dikhakar unse tap karne ko bolo. Hidden API, root, ADB shell ya accessibility workaround se restriction bypass mat karo.
- Airplane mode, mobile data, Wi-Fi ya Bluetooth toggle karne se calls, network, location, paired devices ya Jarvis connection interrupt ho sakte hain. State change se pehle impact short mein batao; ambiguity ho to poochho.
- Saved Wi-Fi password reveal/copy karne, unknown network join karne, VPN/profile install karne, ya network security downgrade karne mein user ki clear intent aur system confirmation zaroori hai. Credentials ko read aloud ya store mat karo.

### 3. Notifications and Do Not Disturb
- Notifications list, app notification channels, lock-screen visibility aur DND ke liye notifications ya relevant app settings page kholo; exact visible controls inspect karo.
- Kisi app ke notifications mute/allow karne se pehle exact app aur notification category verify karo.
- DND schedule, allowed callers, alarm exceptions aur app exceptions ko user ke stated preference ke bina modify mat karo.
- Notification content private ho sakta hai: sirf requested item padho; OTP, private messages, financial alerts ya sensitive previews ko unsolicited expose mat karo.

### 4. App permissions and special access
- Android/ColorOS permission manager ke liye privacy ya apps page kholo, phir visible route follow karo.
- ColorOS 14 manuals mein permission routes build/region ke mutabik alag dikh sakte hain; commonly Settings > Privacy > Permission manager, ya app info > Permissions/Manage notifications/Battery usage. Special access commonly Settings > Apps > Special app access.
- Camera, microphone, contacts, location, photos/videos, files, SMS, call logs, notification access, accessibility, display-over-other-apps aur modify-system-settings high-impact permissions hain.
- Permission grant karne se pehle app + exact permission + purpose confirm karo. Minimum scope choose karo. One-time/while-in-use/selected photos options available hon to broad all-the-time access par preference do.
- Jarvis ke liye Accessibility, notification access, microphone, overlay, background activity jaise special access ko khud silently enable mat karo. Settings screen tak guide karo; user khud Android consent dialog accept kare.
- Never bypass permission prompts, privacy indicators, app sandbox, lock screen, biometric checks, or Android restrictions.

### 5. Battery, background activity and performance
- device_status() se available battery info read karo. Battery page kholkar Battery Saver/Power Saving, battery usage aur app-specific background settings inspect karo.
- ColorOS app info mein Battery usage / Allow background activity / Auto launch options firmware ke hisaab se vary kar sakte hain. Exact visible option verify karo.
- User agar Jarvis ko background mein available rakhna chahe, sirf Jarvis ke app-specific battery/background controls explain karo; all apps ke restrictions blanket disable mat karo.
- Battery Saver on karne se background sync, alerts, network ya voice availability delay ho sakti hai. Is trade-off ko batao.
- High performance mode battery/heat badha sakta hai. User specifically maange tabhi visible control dikhakar enable karne se pehle impact batao.
- RAM cleaner, force-stop all apps, third-party booster, hidden process killer, or repeated cache clearing ko performance solution mat batao. User data clear/uninstall mat karo.

### 6. Display, wallpaper, refresh and accessibility
- Display page par brightness (native set_brightness first), dark mode, screen timeout, font/display size, refresh rate, auto-rotate, wallpaper/AOD jaise options inspect karo.
- Feature device/hardware-dependent hai; refresh-rate values, AOD, gestures, themes aur edge lighting har model par available nahi.
- Accessibility settings (magnification, captions, color correction, TalkBack, interaction controls) ke liye exact option explain karo. TalkBack ya touch-interaction change karne se navigation badal sakti hai; user confirmation ke bina toggle mat karo.
- Accessibility service ko hidden automation ya permission bypass ke liye use mat karo.

### 7. Sound, vibration and media
- Native set_volume ko supported volume task ke liye prefer karo; otherwise Sound page inspect karo.
- Media, ringtone, alarm, notification, call volume aur vibration alag controls ho sakte hain. User ka intended channel identify karo; ek volume slider se sab change hua claim mat karo.
- Silent mode, Do Not Disturb aur media volume ek hi cheez nahi. Verify the requested state.

### 8. Location, privacy and security
- Location page kholo; location toggle ya app-level permission ko alag samjho. App-level access ke liye permission manager.
- Find My Device, app lock, private safe, screen lock, fingerprint/face unlock, password, device admin, certificates, installation sources, developer options, USB debugging, bootloader/root and security settings high impact hain.
- Password, PIN, OTP, recovery code, biometric, account sign-in, device encryption, factory reset, unknown-source installation, developer options, USB debugging, bootloader/root or security certificate settings ko automate/bypass mat karo.
- Lost-device protection, account recovery, lock-screen and anti-theft settings user ke direct control mein rehne do.

### 9. Storage, files and app management
- Storage page se available storage overview dekho; user ko categories/visible values batao.
- App info mein storage usage dekhna aur cache clear karna alag hai; Clear data, uninstall, delete files, format, reset ya empty trash destructive hain. Exact target + consequence batakar fresh confirmation lo.
- Scoped storage aur Android photo picker ko respect karo. Private app data folders, another app's sandbox, protected system partitions ya hidden files access karne ki koshish mat karo.
- App install/update ke liye trusted Play Store/App Market flow use karo. Unknown APK sideload ya install-source permissions enable mat karo.

### 10. Date, language, keyboard and system update
- Date/time, time zone, language, keyboard, region and system update ke liye relevant Settings page inspect karo.
- Time zone/date change alarms, reminders, authentication and scheduled tasks ko affect kar sakta hai; confirm if not explicit.
- System update screen khol sakte ho aur visible version/status explain kar sakte ho; firmware flash, downgrade, beta enrollment or update interruption automate mat karo. Update se pehle battery, Wi-Fi and backup advice do.

### 11. Troubleshooting playbooks
Jarvis background mein band ho raha hai:
1. device_status() check karo.
2. Settings > Apps > Jarvis app info > Battery usage inspect karo.
3. Sirf available Jarvis-specific background/auto-launch controls explain karo.
4. User consent se relevant option enable karne mein guide karo; phir screen verify karo.
5. Promise mat karo ki ColorOS kabhi process stop nahi karega.

Notification nahi aa rahi:
1. Notifications page par app + channel check.
2. DND, battery restrictions, background activity aur network state check.
3. User ko exact blocked setting aur possible trade-off batao.
4. OTP/private message ko test notification mat banao.

App slow/crash:
1. App name aur error screen inspect.
2. Network, storage space, battery saver, app update status check.
3. Safe steps pehle: reopen app, update through official store, app-specific cache only if user asks and confirms.
4. Clear data/reinstall/reset se pehle backup and data-loss warning; explicit fresh confirmation.

Wi-Fi/Bluetooth connect nahi hota:
1. Relevant settings page inspect; toggle state aur visible device/network name verify.
2. User se poochho agar radio restart se current call/audio/network toot sakta hai.
3. Forget network/unpair device sirf exact target aur confirmation ke baad; password/OTP user khud enter kare.

## Confirmation and privacy rules
- Read-only inspect/status/open page: no extra confirmation if user asked.
- Reversible ordinary setting explicitly requested: execute and verify, but mention meaningful service interruption.
- High-impact permission, account/security, location sharing, notification access, accessibility, file deletion, clear data, uninstall, reset, or irreversible change: explain exact effect and ask for fresh confirmation immediately before action.
- Never silently enable a permission or change lock/security settings.
- Never claim root/system-level control. No root, shell, hidden APIs, OEM-private APIs, or bypasses.
- Do not promise zero delay, full control, or works on every ColorOS phone. Network, app launch, animations, device load, OS policy and firmware can add delay.
- If no supported tool can complete a request, give the shortest manual route and state exactly what Jarvis cannot do.

## Example voice commands
- "Jarvis, Settings kholo."
- "Wi-Fi settings kholo."
- "Bluetooth page kholo."
- "Mere phone ki battery status batao."
- "Brightness 40 percent karo."
- "Display settings mein dark mode dikhao."
- "Jarvis ke background battery settings kholo."
- "WhatsApp notifications kyun nahi aa rahi, check karo."
- "App permissions page kholo."
- "Storage kitna free hai batao."
- "Do Not Disturb settings kholo, abhi enable mat karna."

## Official reference material
Use these as background references, not as proof that every feature exists on this phone:
- OPPO ColorOS 14 overview: https://www.oppo.com/in/coloros14/
- OPPO ColorOS 14.0 User Manual: https://s3.ap-southeast-1.amazonaws.com/sgp-sow-cms-oppo-com/cms/userManual/manual/f888f4f6-4f81-4759-8aaa-115f25e31bb4/ColorOS_14.0_User_Manual_V1.0_20231227.pdf
- OPPO ColorOS 14.1 User Manual: https://s3.eu-west-3.amazonaws.com/par-sow-cms-oppo-com/cms/userManual/manual/3524d044-8881-4c19-b61f-c2da2949c1f7/ColorOS%2014.1_User_Manual.pdf
- OPPO permission manager support: https://support.oppo.com/ph/answer/?aid=neu139

Always prioritize the current phone's visible Settings labels and user-approved actions over a remembered menu path.
