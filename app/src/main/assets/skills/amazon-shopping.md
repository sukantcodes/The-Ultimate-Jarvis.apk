---
name: amazon-shopping
description: Amazon pe search, compare, cart aur checkout karne ka tarika; order sirf user ke saaf "order place karo" ke baad
---
# Amazon shopping — search se confirmed order tak

Tumhare asli tools: `amazon_search`, `amazon_compare`, `amazon_open_product`, `amazon_add_to_cart`, `amazon_cart`, `amazon_prepare_checkout`, `amazon_place_order`. Amazon ke liye click_ui_element / type_text mat use karo.

## Dhoondhna
1. `amazon_search` me `request` = user ke apne shabd (English ya Hinglish), jaise "iphone 15 black 60000 ke neeche". Budget, rating, Prime, sasta/best aur quantity khud samajh aata hai.
2. Result me sirf wahi bolo jo tool ne screen se padha: naam, daam, rating, delivery. Kuch andaza mat lagao; jo nahi dikha use "dikha nahi" bolo.
3. Top 3 ek-do line me batao, phir poocho kaunsa chahiye.

## Compare
- Do ya zyada options ho to `amazon_compare` (specs chahiye to `deep=true`). Sabse sasta, best rated, best value tool khud batata hai.

## Cart
- `amazon_add_to_cart(index, quantity, variants)`: tool cart padh ke verify karta hai. `verified=true` aaye tabhi bolo "cart me daal diya".
- Variant (colour/size/storage) na mile to kuch add nahi hota; user ko batao aur poocho.
- `amazon_cart` se dekho, hatao, ya quantity badlo.

## Checkout aur order
1. `amazon_prepare_checkout` sirf review screen tak jaata hai, order nahi karta.
2. Summary padho: items, total, delivery, address, payment. User se poocho: "Order place kar doon?"
3. User NAYE message me saaf bole ("haan order place karo", "confirm the order") tabhi `amazon_place_order` ek baar call karo.
4. Status `PLACED` aaye tabhi bolo order ho gaya. Koi aur status ho to sach batao aur dobara order mat dabao.

## Rukna hai (user ko khud karna hai)
- Sign-in, password, OTP, CAPTCHA, UPI PIN, card CVV, payment authentication: kabhi type ya bypass mat karo. User ko bolo kar le, phir "ho gaya" bole to aage badho. Payment check ke baad `amazon_cart(action="check_order")` se dekho order confirm hua ya nahi.

## Galti ho to
- Page error/offline ho to tool khud retry karta hai; phir bhi na chale to asli wajah batao.
- "Main click/type nahi kar sakti" jaisa bar-bar mat bolo: asli error batao aur ek baar naya tareeka try karo.
