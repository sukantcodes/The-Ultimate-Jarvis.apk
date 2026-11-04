---
name: google-calendar-planner
description: Google Calendar daily agenda, event creation, editing, guests, reminders, recurring schedules and cancellation with verification
---
# Google Calendar Planner — complete scheduling workflow

Real tools: `open_app(app_name)`, `inspect_screen`, `click_ui_element(target_name, element_id)`, `type_text(text, target_input_name)`, `scroll_screen`, `press_key`, `task_completed(summary)`. No dedicated Calendar API is declared; use the visible Calendar UI.

## 1. Read the agenda
1. Open Google Calendar and inspect current date, selected account and view.
2. Navigate to the requested day/week/month.
3. Read only relevant visible event titles and times. Distinguish all-day events from timed events.
4. Confirm timezone if shown. Do not infer an event's location or attendance from its title.

## 2. Create an event
1. Ask for missing essentials: title, date, start time and end time. Don't guess.
2. Tap Create/+ and inspect the event form.
3. Enter only confirmed details: title, date, start/end, location, description.
4. For guests, verify every email address; adding guests may send invitations.
5. Configure reminders and repeat schedule only as requested. For recurring events, explain scope (this event vs this and following vs all).
6. Review the complete summary and ask for confirmation before Save.
7. Save once, inspect the calendar and verify the event appears once.

## 3. Edit or reschedule
1. Find the exact event by title and date; verify details.
2. Change only requested fields.
3. If recurring, confirm which occurrence(s) to change.
4. Review the proposed changes and ask before saving.
5. Verify the updated event.

## 4. Cancel or delete
- Identify the exact event and whether it has guests.
- Explain if cancellation notifications may be sent.
- Obtain explicit confirmation in a new turn before deleting/cancelling.
- Never decline invitations or message guests unless asked.

## 5. Reminders and limits
- A reminder is configured only if its setting is visibly present and saved.
- Do not claim guest acceptance or availability unless the app shows it.
- User completes sign-in, OTP and account verification personally.

## Voice examples
"What is on my calendar tomorrow?"; "Create [event] on [date] at [time]"; "Move [event] to [time]"; "Cancel [event]".
