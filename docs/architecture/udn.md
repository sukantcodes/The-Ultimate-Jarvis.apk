# JARVIS Universal Device Network (UDN)

Backend: Supabase project JARVIS-CLOUD_CLI. Android client: `app/.../udn`, UI: `ui/udn/UdnActivity`. Windows: `windows-agent/`.

## Trust model
- Account identity: Supabase user JWT. Device identity: per-device ECDSA P-256 key (Android Keystore, StrongBox if present; DPAPI on Windows).
- Each request is signed over `UDN1 / POST / action / deviceId / ts / nonce / sha256(body)`; the Edge Function verifies it, enforces a ±120 s window, single-use nonces and rate limits.
- All tables are RLS-locked (clients have no table access except reading their own `udn_wakeups`); every mutation goes through the `udn-api` function using service-role-only SQL functions. The service-role key exists only in the Edge Function environment.
- Exactly two parent slots, DB-enforced. First parent via `claim_first_parent` (only when none exists); others join with a one-time 10-minute code and must be approved by a parent. A parent cannot revoke the other parent; nobody can promote itself.

## Command lifecycle
queued → delivered → running → succeeded / failed / rejected / expired / cancelled / timed_out. Idempotent per (source, nonce); redelivered after 20 s if unacked (max 4); target-side dedupe store prevents double execution. Online = heartbeat within 135 s (3 × 45 s). Realtime wake-ups carry ids only; polling is the fallback. The reply to the user is built only from the target's reported result.

## Sensitivity
Low: runs. Medium (lock, volume, brightness, flashlight): needs the sender's confirmation (a spoken command counts). High (location, camera): explicit "yes" on the sender **and** Allow on the target's notification. Per-device policy (allow/confirm/deny) and a local "allow remote control" kill switch.

## Platform limits
| Capability | Requires | Limits |
|---|---|---|
| device.lock | Accessibility service enabled | Reported unavailable otherwise |
| volume/brightness | WRITE_SETTINGS for brightness | Verified by read-back |
| flashlight | Camera flash hardware | Verified by read-back |
| location | User opt-in + location permission + Allow tap | Foreground-service limits apply |
| camera | Allow tap | Only opens Camera Scan; no streaming |
| listening.stop | Voice service running | Verified |
| chat.ask / routine.run | — | Reported unavailable (not implemented remotely) |
| background voice | Mic permission, foreground notification | Android/OEM can stop it; battery-optimization exemption helps; no mic during calls |
| background link | Foreground service (dataSync) | Not auto-started at boot (Android 15 restrictions); starts when the app is opened |

## Not implemented
Offline LAN control (the LAN socket is only a wake-up nudge; commands still flow via Supabase), remote AI chat/routines, camera/mic streaming, boot auto-start, lock-screen voice integration.
