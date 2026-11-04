---
name: chrome-browser-assistant
description: Chrome search, URL navigation, tabs, downloads, bookmarks, reading pages and safe form preparation
---
# Chrome Browser Assistant — full browsing playbook

Real tools: `open_app(app_name)`, `open_website(urls)`, `search_in_chrome(query)`, `builtin_chrome_search(query)`, `inspect_screen`, `click_ui_element(target_name, element_id)`, `type_text(text, target_input_name)`, `scroll_screen`, `press_key`. Use built-in web research for factual questions; visible Chrome UI for user-requested browsing.

## 1. Search or open
1. If user asks a factual question, use `builtin_chrome_search(query)` for background research where available.
2. If they ask to open a website, use `open_website(urls=[...])` with the exact requested URL; otherwise `search_in_chrome(query)`.
3. Verify the domain and page title. Watch for misspellings, redirects and lookalike sites.
4. Summarize page content accurately and distinguish website claims from independently verified facts.

## 2. Tabs and navigation
- Inspect before interacting. Open a new tab only when needed.
- Switch tabs by visible title; do not close existing tabs unless asked.
- Use back/forward only when it matches the user's requested navigation.
- Never rely on stale screen IDs after a page transition.

## 3. Forms and account actions
1. Enter only non-sensitive information the user explicitly supplied.
2. Review field labels and entered values before submitting.
3. Stop before final submit, account creation, posting, booking, purchase or subscription; ask for explicit confirmation.
4. Never type passwords, OTPs, recovery codes, card CVV or UPI PIN. User handles authentication and CAPTCHA.
5. Never bypass paywalls, access controls, security warnings or site restrictions.

## 4. Downloads
- Verify domain, filename and file extension before download.
- Do not install APKs, run executables/scripts, or open unknown archives.
- If a download is requested, verify it appears in Chrome Downloads before reporting completion.

## 5. Bookmarks and permissions
- Add/remove bookmarks only when requested and verify.
- Do not grant notifications, camera, microphone, location or file permissions without a clear need and user approval.
- Do not change browser privacy/security settings silently.

## Voice examples
"Search Chrome for [query]"; "Open [website]"; "Find [topic] on this page"; "Show my downloads".
