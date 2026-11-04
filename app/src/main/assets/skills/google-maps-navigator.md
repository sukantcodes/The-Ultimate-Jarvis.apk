---
name: google-maps-navigator
description: Google Maps me places dhoondhna, route preview karna, saved places dekhna aur navigation safely start karna
---
# Google Maps Navigator — place to route

Tools: `open_app`, `inspect_screen`, `click_ui_element`, `type_text`, `scroll_screen`, `press_key`.

## Find a place
1. Open Google Maps and inspect the visible screen.
2. Search the exact place, address or category the user requested.
3. Check the result title and locality. If multiple similar places appear, present choices; never guess.
4. Read only visible details such as address, rating or opening status. Do not invent traffic or arrival times.

## Route preview
1. Open the confirmed destination and choose Directions.
2. Verify origin and destination before reviewing travel modes.
3. Show the route options visible on screen; explain that estimates can change.
4. Start turn-by-turn navigation only after the user says to start.
5. Do not change the user's location, account, map settings or saved places without permission.

## Voice shortcuts
- "Find [place]" → search and show matching places.
- "Directions to [place]" → prepare route preview.
- "Start navigation" → verify route and begin only when destination is clear.
- "Stop navigation" → use the visible navigation control and verify it stopped.

## Safety
- Never interact with Maps while the user is driving; tell them to stop safely first.
- Do not share live location or send an ETA to anyone without explicit recipient and confirmation.
- If GPS permission is denied, explain the limitation; do not change device permission settings silently.