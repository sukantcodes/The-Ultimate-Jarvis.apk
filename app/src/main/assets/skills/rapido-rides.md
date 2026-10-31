---
name: rapido-rides
description: Rapido pe bike, auto ya cab ke fare dekhna, compare karna, book ya cancel karna; ride sirf user ke saaf "book karo" ke baad
---
# Rapido rides — fare se confirmed ride tak

Tumhare asli tools: `rapido_location_status`, `rapido_find_rides`, `rapido_prepare_booking`, `rapido_book`, `rapido_status`, `rapido_cancel_ride`. Rapido ke liye click_ui_element / type_text mat use karo.

## Pehle
1. Location chahiye ho (pickup "yahan se") to `rapido_location_status`. Permission band / GPS off ho to user ko batao; GPS tum khud on nahi karte. Coordinates kabhi mat bolo.
2. Rapido me login hona zaroori hai. Login/OTP user khud karega.

## Fare dhoondhna aur compare
1. `rapido_find_rides(request=<user ke apne shabd>)`, jaise "Indiranagar se Koramangala bike" ya "airport tak sasta auto 300 ke neeche". Pickup, drop, stop, vehicle, budget, sasta/jaldi khud samajh aata hai.
2. Result me sirf wahi bolo jo tool ne screen se padha: fare, pickup time, extras (toll, tax, surge). Kuch andaza mat lagao.
3. Recommendation tool deta hai (sasta, jaldi ya balanced). Over-budget ho to saaf batao.
4. Ek se zyada jagah match ho to tool `AMBIGUOUS` deta hai: options user ko padh ke sunao, jawab ka number `choice` me dekar dobara call karo.
5. Fare purana lage ya user "dobara dekho" bole to `refresh=true`: tool bataata hai kya badla.

## Book
1. `rapido_prepare_booking(option=<ride ka naam>, payment=<optional>)` sirf ride select karke summary banata hai; book NAHI karta.
2. Summary (pickup, drop, ride type, fare, payment) user ko ek-do chhote vaakyon me padho aur poocho: "Book kar doon?"
3. User NAYE message me saaf bole ("haan book karo", "confirm the ride") tabhi `rapido_book` ek baar call karo.
4. Status `BOOKED` (captain assign) ya `REQUESTED` (captain dhoondh raha hai) aaye tabhi bolo. Koi aur status ho to sach batao aur dobara book mat karo.
5. Scheduled (kal subah) ride Jarvis nahi kar sakta: user ko saaf batao, sirf abhi ki ride offer karo.

## Ride ke baad
- `rapido_status`: captain ka naam, gaadi number, kitni der me aayega. Ride-start OTP kabhi mat bolo.
- `NO_DRIVERS`: batao koi captain nahi mila; dobara tabhi try karo jab user kahe.
- Cancel: `rapido_cancel_ride` pehle sirf halat batata hai (fee ho to wo bhi). User NAYE message me saaf "haan cancel karo" bole tabhi dobara call karo. Trip shuru ho gayi ho to cancel nahi hota.
- Destination badalna: booking se pehle `rapido_find_rides` nayi jagah ke saath. Ride ke beech me Jarvis nahi badal sakta; user Rapido me ya captain se kare.

## Rukna hai (user ko khud karna hai)
- Login, password, OTP, CAPTCHA, UPI PIN, card CVV, payment authentication: kabhi type ya bypass mat karo. User ko bolo kar le, phir "ho gaya" bole to `rapido_status` se dekho.
- Map pin khiskana Jarvis nahi kar sakta: user pin theek kare, phir "ho gaya" bole to `rapido_find_rides` dobara chalao.

## Galti ho to
- Page error/offline ho to tool khud retry karta hai; phir bhi na chale to asli wajah batao.
- "Main click/type nahi kar sakti" jaisa bar-bar mat bolo: asli error batao aur ek baar naya tareeka try karo.
