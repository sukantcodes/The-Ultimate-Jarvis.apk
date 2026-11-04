---
name: blinkit-shopping
description: Blinkit pe saman dhoondhna, value compare karna, budget me list banana, cart, address, order aur status; order sirf user ke saaf "place karo" ke baad
---
# Blinkit — search se confirmed order tak

Tumhare asli tools: `blinkit_search`, `blinkit_compare`, `blinkit_budget_plan`, `blinkit_cart`, `blinkit_address`, `blinkit_prepare_checkout`, `blinkit_place_order`, `blinkit_order_status`. Blinkit ke liye click_ui_element / type_text mat use karo.

## Dhoondhna
1. `blinkit_search(request=<user ke apne shabd>)`: "doodh", "200 ke andar best value detergent", "nashte ka kuch". Spelling galat ho to bhi chalega.
2. Bolo sirf wahi jo tool ne screen se padha: naam, brand, pack, price, rating, offer, availability.
3. Per-kg / per-litre / per-item daam Jarvis ne **calculate** kiya hai (price / pack size). Ye saaf bolo. "Best value" sabse sasta nahi, rating aur unit price dono dekh kar hota hai.
4. Item available na ho to tool alternatives deta hai; bina poochhe badlo mat.

## Compare aur budget
1. `blinkit_compare(items=[1,3])` pichhli search ke numbers par.
2. `blinkit_budget_plan(request="doodh, bread, ande total 300 ke andar")`: total, kami (shortfall) aur kya hatana hai, tool batata hai. Plan sirf plan hai; cart me daalne ke liye `blinkit_cart(action=add_plan)`.

## Cart
`blinkit_cart(action=view|add|add_plan|remove|set_quantity|coupons|apply_coupon)`. Har kaam cart padh kar verify hota hai. Timeout ke baad pehle `view`, dobara add mat karo.

## Address
`blinkit_address(action=status|location|list|select)`. Address kabhi chupke se mat badlo; `select` sirf tab jab user ne saved address ka naam bola. GPS off ho to user ko batao, tum on nahi karte. Coordinates kabhi mat bolo.

## Order
1. `blinkit_prepare_checkout()` sirf summary banata hai, order NAHI karta. Items, total (fees ke saath), address, payment user ko padh kar poochho: "Place kar doon?"
2. User NAYE message me saaf bole tabhi `blinkit_place_order` ek baar call karo.
3. "Place ho gaya" sirf status `PLACED` par bolo. Payment app/PIN khule to wo user ka kaam hai.
4. Login, OTP, CAPTCHA, UPI PIN me kabhi type ya tap mat karo.

## Status, cancel, help
`blinkit_order_status(action=status|history|help|cancel)`. "Delivered", "cancelled" ya "refunded" tabhi bolo jab result verified ho. Cancel: pehle state samjhao, phir user NAYE message me saaf "cancel karo" bole.

## Jo nahi hota
Blinkit ka apna filter/sort UI nahi chalta (ranking Jarvis khud karta hai); delay ka background alert nahi (status maangne par padha jaata hai); coupon eligibility pehle se check nahi hoti.
