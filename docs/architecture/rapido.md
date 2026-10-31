# Rapido App Mastery

JARVIS drives the installed Rapido passenger app (`com.rapido.passenger`) through the same accessibility pipeline used for
Amazon. Rapido publishes no public ride-booking API or documented deep links, so nothing here claims one: the integration is
the Accessibility service plus label-based screen reading. Package: `automation/rapido`.

## Tools (shared by Gemini Live and Groq)

| Tool | Does | Never does |
| --- | --- | --- |
| `rapido_location_status` | Reports precise / approximate / permission denied / GPS off / no fix. Can open the settings page that fixes it. | Return coordinates, or switch location on. |
| `rapido_find_rides` | Sets pickup, drop and stops, reads every ride option (fare, range, pickup time, visible tolls/taxes/surge), recommends one, re-reads and diffs on `refresh`. Asks on ambiguous places (`choice`). | Book anything. |
| `rapido_prepare_booking` | Selects the ride, proves the selection and the route on screen, optionally picks a payment method through Rapido's own selector, stores a summary. | Book anything. |
| `rapido_book` | Books after the gate passes; verifies that Rapido is searching / a captain is assigned. | Claim `BOOKED` without a captain on screen. |
| `rapido_status` | Searching / assigned / arrived / on trip / completed / no captain, captain name, vehicle, arrival. | Read or speak the ride-start OTP. |
| `rapido_cancel_ride` | Two steps: explain state and fee, then cancel after a new explicit message; verifies the ride ended. | Cancel a trip that already started. |

## Safety rules (enforced in code, covered by unit tests)

* `RideGate`: booking needs a prepared summary, a fresh fare (4 min), an explicit confirmation in a **different, later**
  utterance read from the real transcript, an unchanged fingerprint (pickup, drop, stops, ride type, fare range, payment)
  and no unresolved booking elsewhere. Cancelling follows the same shape.
* The ledger is written to disk **before** the Book tap, so a timeout, crash or uncertain result can never lead to a second
  tap. Outcomes: `BOOKED`, `REQUESTED` (still searching), `NOT_VERIFIED` (not claimed), `NO_DRIVERS` (released for a later,
  newly confirmed retry).
* `RapidoGuards` stops the generic click/type tools from pressing Book/Cancel/pay buttons or typing into login, OTP,
  CAPTCHA, UPI PIN or card screens. Those screens are always handed to the user.
* Location is read on the device only to judge accuracy and a coarse area name. Coordinates are never returned, logged or sent.

## Known limits (reported to the user, never faked)

* Scheduled/future rides, notes to the captain and dragging the map pin are not automated. Scheduled requests are
  recognised and booking is refused rather than booked for "now".
* Changing the destination mid-ride is not automated; before booking, `rapido_find_rides` with a new drop re-plans.
* Extra stops need an "Add stop" control on screen; if it is missing the tool says so and books nothing.
* Rapido's screen labels are English and change between app versions. The reader matches labels, not coordinates, and fails
  with an explicit status when a label is not found. **All of this must be validated on a real device with a logged-in Rapido app.**
