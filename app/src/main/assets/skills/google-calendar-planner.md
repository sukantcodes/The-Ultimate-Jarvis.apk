---
name: google-calendar-planner
description: Google Calendar me schedule dekhna, event draft banana, reminders aur meeting details verify karna
---
# Google Calendar Planner — schedule with review

Tools: `open_app`, `inspect_screen`, `click_ui_element`, `type_text`, `scroll_screen`, `press_key`.

## Check schedule
1. Open Calendar and inspect the current date and view.
2. Navigate to the date or range the user asked about.
3. Read only relevant visible events. Distinguish all-day events from timed events and confirm the displayed timezone if visible.
4. If the user says "today" or "tomorrow", verify the device date before acting.

## Create an event
1. Open the create-event screen.
2. Fill title, date, start/end time, location and notes only when supplied or clearly stated.
3. For missing essential date or time, ask one concise question; do not guess.
4. Review the full event summary and ask before saving.
5. If guests are added, verify each email address and ask before sending invitations.
6. After save, inspect the calendar and verify the event appears once.

## Edit or delete
- Identify the exact event by title and date.
- Show the proposed change before saving.
- Deleting an event or cancelling a meeting requires explicit confirmation.
- Never decline invitations or email guests without instruction.

## Limits
- Do not promise reminders unless the event's notification setting is visibly configured.
- Never invent availability or assume an attendee accepted an invitation.