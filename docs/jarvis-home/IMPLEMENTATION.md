# JARVIS Home — implementation status

Branch: `Antigravity` only. Spec: [Home.md](Home.md). This file separates what is built and unit-tested from what is built but
untested on hardware, and from what is not implemented.

## Code map
| Area | Location |
|---|---|
| Models, registry (devices, rooms, groups, activity, backup) | `app/src/main/java/com/jarvis/assistant/home/` |
| Adapters: LAN HTTP (Tasmota, Shelly Gen1, custom URLs), BLE GATT, paired Bluetooth status, discovery | `home/adapters/` |
| Controller (capability check, local-first, hub fallback, state + activity recording) | `home/HomeController.kt` |
| Automation engine, schedules, WorkManager scheduler | `home/automation/` |
| Voice intent engine (EN/HI/Hinglish) + AI tools (`home_control`, `home_list_devices`, `home_run_scene`) | `home/voice/`, `home/HomeTools.kt`, `home/HomeAssistant.kt` |
| Supabase remote client | `home/remote/SupabaseRemote.kt` |
| UI (12 pages, Liquid Glass) | `ui/jarvishome/` |
| Supabase migration (tables, RLS, claim/ack functions) | `supabase/migrations/20261117000000_jarvis_home.sql` |
| Windows Home Hub | `hub/` |

## Verified
* JVM unit tests for registry, intent engine, controller, automation engine, schedule maths and the LAN adapter (62 tests) pass
  when compiled with Kotlin 2.0.21 against a stand-in `org.json`. They run in CI via `testDebugUnitTest`.
* Home Hub unit tests (`hub/test_hub.py`, 10 tests) pass.

## Built, not verified on real hardware
* BLE GATT writes, paired-Bluetooth status, BLE/mDNS scanning, Tasmota/Shelly control against real devices.
* Supabase migration has not been applied to the live project; RLS has not been exercised against a database.
* Hub sign-in, claim/ack against Supabase; hub BLE (needs `bleak`).
* UI rendering on devices; voice tools through a live Gemini session.

## Not implemented (by design, nothing pretends otherwise)
* Matter commissioning/control, MQTT, manufacturer cloud APIs (need official credentials/approval).
* Android cannot connect Bluetooth Classic audio devices on its own; JARVIS shows live status and opens Bluetooth settings.
* Multi-user homes (`home_members`); every row belongs to one account.
* Hindi/Hinglish UI strings (voice understanding is multilingual; screen text is English).
* The reference photo supplied by the owner is not copied into `assets/`; add approved screenshots there.

## Safe defaults
* Control only shows what an adapter advertises. A write that the device cannot confirm is reported as "sent", never "done".
* Templates (Good Night, Movie Mode…) start with no steps and refuse to run until configured.
* Remote commands: unique id, expire ≤ 10 min, claimed once via `home_claim_command`, acknowledged via `home_ack_command`.
* Device passwords: EncryptedSharedPreferences (phone) / OS credential vault (hub); never in the registry, Supabase or backups.
