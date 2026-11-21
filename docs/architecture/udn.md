# JARVIS Universal Device Network (UDN)

One shared network for every JARVIS account. There are no pairing codes.

## Roles
- **Parents** are the accounts `sukantxplodeprime@gmail.com` (Parent 1) and `sentubhattacharya2021@gmail.com` (Parent 2), allowlisted in `udn_parent_emails`. The server derives the role from the verified account e-mail on every request; the client can't claim it. Every device signed in to a parent account is a parent device.
- **Children** are every other device. They join automatically (`register` -> `udn_auto_enroll`) and appear on the parent devices straight away.

## Who can do what
| Action | Parent | Child |
|---|---|---|
| See all devices | yes | only itself and accepted circle members |
| Command another device | any other device, including the other parent | only accepted members of a shared circle; never a parent |
| Camera / location (high sensitivity) | yes (with on-device Allow) | never |
| Remove a child (`revoke`) / ban its account (`ban`, `unban`) | yes | no (can only leave itself) |
| Rename, policies, groups, audit log | yes | no |
| Create / manage circles | read all, delete any | create, invite, remove members, leave |
| Messages | to any device; sees all | to circle members; sees own |

The rule lives in SQL: `udn_may_command(owner, source, target)`, checked at `send_command` and again at `poll_commands` (delivery time).

## Reliability
- Signed requests (ECDSA P-256 from the Android Keystore), replay-protected nonces, idempotent command queue with redelivery and target-side dedupe.
- Realtime wake-ups (`udn_wakeups`, kinds `command`, `registry`, `circle`) tell devices to refresh at once; polling and a 45 s heartbeat are the fallback; a LAN socket is only a nudge.
- Foreground link service plus a WorkManager watchdog (about every 15 minutes) that restarts the link. Android and some manufacturers can still stop background apps, so "never fails" can't be guaranteed; a device that is off or offline is reported as offline, never as a success.
- Parents get a local notification when a new device joins (first sync is a silent baseline) and children when invited to a circle.

## Backend
Tables: `udn_devices`, `udn_parent_emails`, `udn_command_queue`, `udn_messages`, `udn_bans`, `udn_circles`, `udn_circle_members`, and others (all RLS-locked; only `udn_wakeups` is readable, ids only). Edge Function `udn-api` is the only client entry point; the service-role key exists only there.

Migrations: `20261118000000_udn_core`, `20261120000000_udn_shared_network`, `20261121000000_udn_account_based_parent_roles`, `20261121010000_udn_circles_bans`.
