# Blinkit App Mastery

JARVIS drives the installed Blinkit app (`com.grofers.customerapp`) through the Accessibility service with label-based screen
reading, the same pipeline as Amazon and Rapido. Blinkit publishes no documented API or deep-link scheme, so none is claimed.
The `https://blinkit.com/s/?q=` link is only a hint; if the app ignores it the agent opens the app and types the search.
Package: `automation/blinkit` (pure logic + `BlinkitDriver` interface; Android code only in `AndroidBlinkitDriver` and `BlinkitTools`).

## Tools

| Tool | Does | Never does |
| --- | --- | --- |
| `blinkit_search` | Reads products (price, pack, rating, offers, availability), English/Hinglish, typo repair, vague asks, budgets, calculated unit price, value ranking, alternatives. | Add anything. |
| `blinkit_compare` | Compares last results; names best value, cheapest, lowest unit price. | Invent data. |
| `blinkit_budget_plan` | Best combination within a total budget (exact search up to 8 items); reports shortfall. | Add to cart. |
| `blinkit_cart` | view / add / add_plan / remove / set_quantity / coupons / apply_coupon, each verified in the cart; duplicate-add protection. | Check out. |
| `blinkit_address` | status / location / list / select (select only a saved address the user named). | Change the address silently; return coordinates. |
| `blinkit_prepare_checkout` | Reads items, bill, address, payment; stores a fingerprinted summary. | Order. |
| `blinkit_place_order` | Orders after the gate passes; verifies the confirmation screen. | Claim PLACED without a confirmation on screen. |
| `blinkit_order_status` | status / history / help / two-step cancel. | Claim delivered, cancelled or refunded without a verified screen. |

## Safety rules (enforced in code, unit-tested)

* `BlinkitGate`: ordering needs a prepared summary (8 min), an explicit confirmation in a different, later utterance (>= 2.5 s)
  taken from the real transcript, an unchanged fingerprint, and no duplicate within 30 min. The ledger is committed to disk
  before the pay tap. Cancel needs a new explicit message within 3 min with the stage unchanged.
* `BlinkitGuards` blocks generic click/type from pressing pay, place-order or cancel, and from typing on login/OTP/CAPTCHA/PIN screens.
* Payment apps and PINs are always the user's.

## Known limits

* Verified: pure logic (parser, ranker, budget planner, screen reader, gate, guards, agent flows against a simulated Blinkit) by JVM unit tests.
* NOT verified here: the Android driver and tools were not compiled locally and nothing was run on a real phone. Blinkit's real
  screen labels are assumed to be English and may differ; the final pay-button label and payment flow need real-device validation.
* Blinkit's own filter/sort UI is not driven (ranking is local). No background watcher for delays (status is read on request).
  Coupon eligibility is not checked in advance. Unit prices are calculated by Jarvis and labelled as such.
