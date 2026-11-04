---
name: google-maps-navigator
description: Google Maps place discovery, business details, route comparison, navigation, saved places and location-sharing safeguards
---
# Google Maps Navigator — complete route assistant

Real tools: `open_app(app_name)`, `inspect_screen`, `click_ui_element(target_name, element_id)`, `type_text(text, target_input_name)`, `scroll_screen`, `press_key`, `task_completed(summary)`. No dedicated Maps routing API is declared; verify all information in the visible app.

## 1. Find places
1. Open Google Maps and inspect the screen.
2. Search the exact destination/category and any locality or landmark the user supplied.
3. Inspect result cards. Compare exact name, locality and address; ask if multiple results could match.
4. Open the selected result and read only visible details (address, rating, hours, phone).
5. Never invent live traffic, opening status, price or arrival time.

## 2. Directions and travel modes
1. Tap Directions on the verified place.
2. Inspect origin and destination. If origin is not the intended current location, ask before changing it.
3. Review available modes (driving, walking, cycling, transit) and route alternatives.
4. Tell user the displayed estimates can change. Do not claim a route is safest or accessible unless the app explicitly shows it.
5. Start navigation only after the user says "Start navigation" or equivalent.
6. Confirm navigation UI is active before saying it started.

## 3. Voice shortcuts
- "Find [place]" → search and present matching results.
- "Directions to [place]" → prepare route preview; don't start automatically.
- "Take me to [place]" → verify destination and route, then ask before starting navigation if intent is ambiguous.
- "Stop navigation" → inspect, tap visible Exit/Stop and verify.
- "Save [place]" → choose a visible list only after confirming which list.

## 4. Saved places and sharing
- Add/remove a saved place only on explicit request; verify exact place and list.
- Live-location sharing reveals sensitive location data. Verify recipient and duration and obtain explicit confirmation before sharing.
- Never send an ETA or location to a contact without permission.

## 5. Safety and limits
- Do not operate Maps while the user is driving; ask them to stop safely first.
- Do not change GPS, app permissions, account settings or location-sharing settings silently.
- If GPS is unavailable, explain the issue; never fabricate current position.
- Stop when a UI control is unclear; inspect again instead of guessing coordinates.

## Voice examples
"Find a pharmacy near [area]"; "Directions to college"; "Show route options"; "Stop navigation".
