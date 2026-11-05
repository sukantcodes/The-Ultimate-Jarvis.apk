# Owner Analytics Dashboard

Path in the app: **Settings → Owner Section → Dashboard → password screen → dashboard.**
The Dashboard is the last row of the last Settings section. It is not on the Home screen, the dock or any
other settings section. Nothing about authorization is decided in the app: the app only hides a button,
the server enforces everything.

## Architecture

```
Android app ──(user JWT + password)──▶ Edge Function  owner-dashboard ──▶ Postgres (service role)
            ──(user JWT + session)───▶                                      RLS on, no client policies
Android app ──(user JWT, device info)▶ Edge Function  device-telemetry ──▶ record_device_seen()
```

* Both functions run with `verify_jwt` on and additionally validate the user's access token with
  Supabase Auth. Guests (no account) can never call them successfully.
* The service-role key exists only in the Edge Function environment.
* All six tables have RLS enabled and forced with **no policies**, and `anon`/`authenticated` have no
  privileges on them or on the helper functions. Direct REST access returns nothing.

## Password verification

* The password is typed on the password screen, sent over TLS to `owner-dashboard` (`action: "verify"`),
  compared with `bcrypt.compare` against the **`OWNER_PASSWORD_HASH`** function secret, and discarded.
  It is not stored on the device, in the database, in logs, in the APK or in git.
* If the secret is missing the function answers 503 and nobody gets in (fail closed).
* A wrong password and a non-owner both get the same generic `Access denied.` (HTTP 401).
* **Rate limit / lockout:** failures are counted per account (`u:<user id>`) and per server-observed IP
  (`ip:<address>`). After `max_failed_attempts` (default 5) the key is locked for `lockout_seconds`
  (default 15 min); while locked even the correct password is refused (HTTP 429).
* Optional extra gate: set `OWNER_USER_IDS` (comma-separated Supabase user UUIDs). When set, only those
  accounts can verify or use a dashboard session.

## Dashboard session

* On success the server returns a random 256-bit token. Only its SHA-256 hash is stored
  (`dashboard_sessions`), bound to the verifying user, with an absolute expiry
  (`owner_settings.session_ttl_seconds`, default **15 min**, range 1–60 min).
* Every data call needs both the user JWT and the `x-dashboard-session` header. The server rejects
  missing, unknown, expired, revoked, or other-user tokens with 401.
* The token is kept in memory only in the app. It is revoked server-side when the dashboard screen is
  closed or the app leaves the foreground, and on logout; the app also closes itself at expiry.

## What is collected

Only for signed-in accounts (guest mode sends nothing), disclosed in Settings → Privacy & Data Shield:

| Data | Source |
|---|---|
| Random per-install ID (not hardware derived), device model, Android version, JARVIS version | app |
| Sign-in / app-open events with timestamps | app (event type), server (time) |
| Public IP address, first/last seen, request count | **server only** (`cf-connecting-ip` / `x-forwarded-for`), never sent by the app |

Not collected: IMEI, SIM, phone number, Android ID, contacts, SMS, call logs, files, audio, GPS.
No IP-based geolocation is performed. IP rows are deleted after `owner_settings.ip_retention_days`
(default 30), events after `event_retention_days` (default 90), by a daily `pg_cron` job
(`purge_expired_analytics`). Deleting an account cascades to its devices, events and IPs.

## Insights tab

`action: "trends"` (7/14/30 days) returns daily sign-ins, app opens and new devices, the most common device
models, Android versions, JARVIS versions and sign-in methods (Google/Email), plus security counters
(accounts locked now, failed owner-password attempts in 24 h, open dashboard sessions). Everything is an
aggregate computed by `owner_trends()` in Postgres; no emails or other personal data are returned.
Tapping a device, IP or activity row shows its full values with a Copy button.

## Known limits (by design — nothing is faked)

* **No Pro entitlement backend exists** in JARVIS (sign-in never implies Pro). Pro users, conversion
  rate and mode-over-time are therefore reported as *not tracked*; all accounts show as Free until a real
  entitlement source is added.
* **No presence/heartbeat**, so the dashboard shows "Active devices (24 h)" based on last-seen and never
  claims a device is online.
* "Active accounts (30 days)" is based on each device's most recent app open.

## One-time setup (owner)

1. Create the hash locally (hidden prompt, prints only the hash):
   `cd scripts && npm install --no-save bcryptjs@2.4.3 && node hash-owner-password.mjs`
2. Supabase Dashboard → Project `yeggjbjcoaaoqntqyjdv` → Edge Functions → Secrets, add:
   * `OWNER_PASSWORD_HASH` = the `$2a$12$…` value printed above (required)
   * `OWNER_USER_IDS` = your own Supabase user UUID (strongly recommended)
   (CLI alternative: `supabase secrets set OWNER_PASSWORD_HASH='…' OWNER_USER_IDS='…'`)
3. Sign in to the app with your owner account, then open Settings → Owner Section → Dashboard.
4. Optional tuning: `update public.owner_settings set ip_retention_days = 14, session_ttl_seconds = 600;`
5. Recommended: Auth → Providers → Email → enable *leaked password protection*.

Deploy / update functions from this repo: `supabase functions deploy owner-dashboard device-telemetry`
(migrations are in `supabase/migrations`; they are already applied to the project).

## Tests

`cd supabase/functions/owner-dashboard && node --experimental-strip-types --test handler.test.ts`
covers: unauthenticated and session-less requests are refused, wrong password is generic, fail-closed
when unconfigured, lockout, session expiry and revocation, session bound to user, and owner allow-list.
