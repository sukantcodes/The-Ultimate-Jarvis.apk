# Settings screen structure

The Settings screen (`fragment_settings.xml` + `SettingsFragment.kt`) groups every option under one of
eight headings. Sections appear on screen in this order.

| # | Section | Rows |
|---|---|---|
| 1 | AI & Intelligence | AI Engine, API Key & Model, Conversation Language, AI Persona, Silent Execution (switch) |
| 2 | Voice & Listening | Voice & Gender, Noise Cancellation, Auto-Sleep, Default Assistant |
| 3 | Voice Security & Unlock | Owner Voice Profile, Owner Voice Only (switch), Screen Unlock |
| 4 | Alerts & Announcements | Notification Access, Message Announcements (switch), Announce in Jarvis Voice (switch), Battery & Charging Alerts (switch), Auto Battery Saver (switch) |
| 5 | Memory & Data | Neural Memory Bank, Activity Memory (switch), Clear Activity Memory |
| 6 | Permissions & Automation | System Permissions, App Control & Hands-Free, Automation Studio, Skill Store |
| 7 | Appearance | Theme & 3D Orb Colors, Overlay Avatars |
| 8 | Privacy & About | Privacy & Data Shield, App Identity & Integrity |

The profile card sits above section 1. The community buttons and the version footer sit below section 8.

## Where things live

- **Layout:** `app/src/main/res/layout/fragment_settings.xml`. Each section is a heading plus a card of rows.
- **Text:** `app/src/main/res/values/strings_settings.xml`. Titles and default subtitles for every row.
- **Look:** `app/src/main/res/values/styles_settings.xml`. Shared styles for headings, rows, icons, dividers and badges.
- **Logic:** `SettingsFragment.kt`. One `setup...Section()` function per section, and `refreshState()` for the values shown on rows.

## Adding a setting

1. Pick the section whose heading best describes it. Do not add a new heading for a single row.
2. Copy an existing row block in that card (a navigation row, or a switch row for an on/off option) and give it a new `rowXxx` id.
3. Add its title and subtitle to `strings_settings.xml`, using the `set_<name>_title` / `set_<name>_subtitle` pattern.
4. Wire it in that section's `setup...Section()` function. For switches use `bindSwitchRow(...)`, and add the switch to `refreshSwitchStates()` so it follows the stored value.
5. Keep one icon per row inside a section.

## Behavior notes

- Switch rows toggle when the whole row is tapped, not only the switch.
- Contacts and phone permissions (needed for calling and SIM choice) are requested when System Permissions is
  opened, not each time Settings opens.
- The footer shows the app version and publisher name from `BuildConfig`.

## Skill Store

Settings > Permissions & Automation > Skill Store opens `ui/skills/SkillStoreActivity.kt`.

- **Installed skills** lists every skill with an on/off switch. Only enabled skills are offered to the AI.
- **Skill store** lists the skills of a GitHub store (default `HunterisLive-1/mobile-maya-skills`: an `index.json` plus a `skills/` folder). Tap **Install** or **Update**. Nothing updates silently. **Source** changes the store; only use stores you trust, because installed skills give instructions to the AI that controls the phone.
- Built-in skills live in `app/src/main/assets/skills/` and are written for Jarvis's real tools. A store skill with the same name is shown as **Built in** and never replaces the built-in copy.
- Downloaded skills are saved in the app's private `files/skills/` folder.
- How the AI uses them: `SkillStore.promptSection()` adds a one-line index to the system prompt (Gemini and Groq). The AI calls the `use_skill` tool to load a skill's full steps. Downloaded skills get a tool-name map appended, because they may be written for another assistant's tools.
- Changes apply from the next conversation.

To add a built-in skill, add `assets/skills/<name>.md` (front matter with `name` and a one-line `description`, then the steps) using only tool names declared in `GeminiLiveWebSocket.kt`.

## Orb look and icon shape

Settings > Appearance > Theme & 3D Orb Colors has an **Orb look** panel under the orb buttons:

- **Glow** (0 to 200%) and **Size** (60 to 140%) are remembered separately for each orb and apply live on the Home screen (`HomeFragment.applyOrbLook`). Glow drives `JarvisOrb.setGlow` in `orb.html` and `aurora_orb.html`, plus the halo behind the orb. Size scales the orb stage.
- **Settings icon shape** is one slider: Plain (the original look, no tile), Rectangular, Pillowy, Circular. It is stored as `settingsIconShape` and painted by `SettingsFragment.applyIconTile`.
