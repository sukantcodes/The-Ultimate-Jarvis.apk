#!/usr/bin/env python3
"""JARVIS Home Hub (Windows 11 first; plain Python, so it also runs on Linux/macOS).

A small gateway that lives on your home network. It makes only OUTBOUND HTTPS connections to your existing Supabase
project, signs in as you (publishable key + your account; no service_role key), claims pending commands for your own
devices, runs them on the local network, and reports the device's real answer. Nothing listens on a port.

Supported device protocols (same as the phone): Tasmota (HTTP), Shelly Gen1 relays (HTTP), custom HTTP on/off URLs, and
BLE GATT writes (needs the optional `bleak` package). Matter, MQTT and manufacturer clouds are not implemented.
"""
from __future__ import annotations

import argparse
import asyncio
import json
import os
import re
import socket
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Optional

APP_DIR = Path(os.environ.get("APPDATA") or Path.home() / ".config") / "JarvisHomeHub"
STATE_FILE = APP_DIR / "state.json"
CONFIG_FILE = APP_DIR / "config.json"
ONLINE_POLL_S = 2.0
MAX_BACKOFF_S = 60.0
HTTP_TIMEOUT_S = 5


# ----------------------------------------------------------------------------------------------------------------
# Pure helpers (unit tested in test_hub.py)
# ----------------------------------------------------------------------------------------------------------------

def parse_tasmota_power(body: str) -> Optional[bool]:
    try:
        obj = json.loads(body)
        key = next((k for k in obj if k.startswith("POWER")), None)
        if key is None:
            return None
        value = str(obj[key]).upper()
        return True if value == "ON" else False if value == "OFF" else None
    except (ValueError, AttributeError, TypeError):
        return None


def parse_shelly_ison(body: str) -> Optional[bool]:
    try:
        obj = json.loads(body)
        return bool(obj["ison"]) if "ison" in obj else None
    except (ValueError, TypeError):
        return None


def parse_iso(ts: str) -> datetime:
    return datetime.fromisoformat(ts.replace("Z", "+00:00"))


def is_expired(command: dict, now: Optional[datetime] = None) -> bool:
    now = now or datetime.now(timezone.utc)
    try:
        return parse_iso(command["expires_at"]) <= now
    except (KeyError, ValueError):
        return True  # a command without a valid expiry is never run


class Seen:
    """Remembers recently executed command ids so a command can never run twice, even across restarts."""

    def __init__(self, ids: Optional[list[str]] = None, limit: int = 500):
        self.limit = limit
        self.ids: list[str] = list(ids or [])

    def __contains__(self, command_id: str) -> bool:
        return command_id in self.ids

    def add(self, command_id: str) -> None:
        if command_id not in self.ids:
            self.ids.append(command_id)
            self.ids = self.ids[-self.limit:]


Result = tuple[bool, bool, str]  # success, verified, message


def run_lan_command(device: dict, action: dict, get: Callable[[str, dict], str], password: Optional[str] = None) -> Result:
    """Executes a command on a LAN device using only documented endpoints. `get` is injected so this is testable."""
    protocol = device.get("protocol")
    config = device.get("config") or {}
    name = device.get("name", "device")
    kind = action.get("type")
    host = str(device.get("address", "")).replace("http://", "").replace("https://", "").rstrip("/")
    if not re.fullmatch(r"[A-Za-z0-9._:\-\[\]]+", host or ""):
        return False, False, f"{name} has an invalid address."
    base = f"http://{host}"
    headers: dict[str, str] = {}
    if config.get("auth_user") and password:
        import base64
        headers["Authorization"] = "Basic " + base64.b64encode(f"{config['auth_user']}:{password}".encode()).decode()
    caps = set(device.get("capabilities") or [])
    needed = {"POWER_ON": "POWER", "POWER_OFF": "POWER", "TOGGLE": "POWER", "SET_BRIGHTNESS": "BRIGHTNESS", "REFRESH": "READ_STATE"}.get(kind)
    if needed is None or needed not in caps:
        return False, False, f"{name} does not support that action."
    try:
        if kind == "REFRESH":
            if protocol == "TASMOTA":
                on = parse_tasmota_power(get(f"{base}/cm?cmnd=Power", headers))
            elif protocol == "SHELLY_GEN1":
                on = parse_shelly_ison(get(f"{base}/relay/{int(config.get('relay', 0) or 0)}", headers))
            else:
                get(base, headers)
                on = None
            return True, True, f"{name} is online" + ("" if on is None else f" and {'on' if on else 'off'}") + "."
        if kind in ("POWER_ON", "POWER_OFF", "TOGGLE"):
            word = {"POWER_ON": "on", "POWER_OFF": "off", "TOGGLE": "toggle"}[kind]
            want = True if kind == "POWER_ON" else False if kind == "POWER_OFF" else None
            if protocol == "TASMOTA":
                reported = parse_tasmota_power(get(f"{base}/cm?cmnd=Power%20{word.capitalize()}", headers))
            elif protocol == "SHELLY_GEN1":
                reported = parse_shelly_ison(get(f"{base}/relay/{int(config.get('relay', 0) or 0)}?turn={word}", headers))
            elif protocol == "GENERIC_HTTP":
                url = config.get("url_off" if kind == "POWER_OFF" else "url_on", "")
                if kind == "TOGGLE" or not url.startswith("http"):
                    return False, False, f"{name} has no matching URL configured."
                get(url, headers)
                status_url, rx = config.get("url_status", ""), config.get("status_on_regex", "")
                if not status_url or not rx:
                    return True, False, f"Sent {word.upper()} to {name}; it does not report state, so this is not confirmed."
                reported = re.search(rx, get(status_url, headers)) is not None
            else:
                return False, False, f"{name}: protocol {protocol} is not supported by the hub."
            if reported is None:
                return True, False, f"Sent the command to {name}; it did not report its state."
            if want is not None and reported != want:
                return False, False, f"{name} reports it is {'on' if reported else 'off'} instead."
            return True, True, f"{name} is now {'on' if reported else 'off'}."
        if kind == "SET_BRIGHTNESS" and protocol == "TASMOTA":
            level = max(0, min(100, int(action.get("value") or 0)))
            get(f"{base}/cm?cmnd=Dimmer%20{level}", headers)
            back = json.loads(get(f"{base}/cm?cmnd=Dimmer", headers)).get("Dimmer")
            return True, back == level, f"{name} brightness {'is' if back == level else 'was sent as'} {level}%."
        return False, False, f"{name} does not support that over {protocol}."
    except urllib.error.HTTPError as e:
        return False, False, f"{name} refused the command (HTTP {e.code})."
    except (urllib.error.URLError, socket.timeout, TimeoutError, OSError):
        return False, False, f"{name} could not be reached on the local network."
    except (ValueError, TypeError):
        return False, False, f"{name} sent an unreadable answer."


def http_get(url: str, headers: dict) -> str:
    req = urllib.request.Request(url, headers=headers, method="GET")
    with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT_S) as r:
        return r.read().decode("utf-8", "replace")


# ----------------------------------------------------------------------------------------------------------------
# BLE (optional)
# ----------------------------------------------------------------------------------------------------------------

def run_ble_command(device: dict, action: dict) -> Result:
    name = device.get("name", "device")
    kind = action.get("type")
    if kind not in ("POWER_ON", "POWER_OFF"):
        return False, False, f"{name} only supports on/off over BLE."
    config = device.get("config") or {}
    try:
        payload = bytes.fromhex(config["on_hex" if kind == "POWER_ON" else "off_hex"].replace(" ", ""))
        char = config["char_uuid"]
    except (KeyError, ValueError):
        return False, False, f"{name} has no BLE command bytes configured."
    try:
        from bleak import BleakClient  # type: ignore
    except ImportError:
        return False, False, "Install the optional 'bleak' package on the hub to control Bluetooth devices."

    async def go() -> None:
        async with BleakClient(device["address"], timeout=10) as client:
            await client.write_gatt_char(char, payload, response=True)

    try:
        asyncio.run(go())
        return True, False, f"Sent {'ON' if kind == 'POWER_ON' else 'OFF'} to {name}. It does not report state, so this is not confirmed."
    except Exception as e:  # bleak raises many types; any of them means the write did not happen
        return False, False, f"{name}: Bluetooth write failed ({type(e).__name__})."


# ----------------------------------------------------------------------------------------------------------------
# Supabase (outbound only)
# ----------------------------------------------------------------------------------------------------------------

class Supabase:
    def __init__(self, url: str, publishable_key: str, state: dict):
        self.url, self.key, self.state = url.rstrip("/"), publishable_key, state
        self.access_token: Optional[str] = None
        self.expires_at = 0.0

    def _call(self, method: str, path: str, body: Any = None, auth: bool = True, extra: Optional[dict] = None) -> Any:
        headers = {"apikey": self.key, "Content-Type": "application/json", **(extra or {})}
        if auth:
            headers["Authorization"] = f"Bearer {self.token()}"
        data = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(self.url + path, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=15) as r:
                raw = r.read().decode()
                return json.loads(raw) if raw else None
        except urllib.error.HTTPError as e:
            raise RuntimeError(f"Supabase {e.code}: {e.read().decode(errors='replace')[:160]}") from None

    def sign_in(self, email: str, password: str) -> None:
        self._store_session(self._call("POST", "/auth/v1/token?grant_type=password", {"email": email, "password": password}, auth=False))

    def _store_session(self, s: dict) -> None:
        self.access_token = s["access_token"]
        self.expires_at = time.time() + int(s.get("expires_in", 3600)) - 60
        self.state["user_id"] = s["user"]["id"]
        self.state["refresh_token"] = s["refresh_token"]
        save_state(self.state)

    def token(self) -> str:
        if self.access_token and time.time() < self.expires_at:
            return self.access_token
        refresh = self.state.get("refresh_token")
        if not refresh:
            raise RuntimeError("Not signed in. Run: python jarvis_home_hub.py login")
        self._store_session(self._call("POST", "/auth/v1/token?grant_type=refresh_token", {"refresh_token": refresh}, auth=False))
        return self.access_token  # type: ignore[return-value]

    def register_hub(self, hub_id: str, name: str) -> None:
        self._call("POST", "/rest/v1/home_hubs?on_conflict=id", [{"id": hub_id, "owner_id": self.state["user_id"], "name": name, "platform": "windows" if os.name == "nt" else "other", "last_seen_at": now_iso()}],
                   extra={"Prefer": "resolution=merge-duplicates"})

    def claim(self, hub_id: str) -> Optional[dict]:
        rows = self._call("POST", "/rest/v1/rpc/home_claim_command", {"p_hub_id": hub_id})
        return rows[0] if rows else None

    def ack(self, command_id: str, hub_id: str, ok: bool, verified: bool, message: str) -> None:
        self._call("POST", "/rest/v1/rpc/home_ack_command", {"p_command_id": command_id, "p_hub_id": hub_id, "p_success": ok, "p_verified": verified, "p_message": message[:300]})

    def device(self, device_id: str) -> Optional[dict]:
        rows = self._call("GET", f"/rest/v1/home_devices?id=eq.{urllib.parse.quote(device_id)}&select=*")
        return rows[0] if rows else None

    def report_state(self, device_id: str, online: bool, powered: Optional[bool]) -> None:
        patch: dict[str, Any] = {"status": "ONLINE" if online else "OFFLINE", "updated_at": now_iso()}
        if online:
            patch["last_seen_at"] = now_iso()
            if powered is not None:
                patch["powered_on"] = powered
        self._call("PATCH", f"/rest/v1/home_devices?id=eq.{urllib.parse.quote(device_id)}", patch)


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def load_json(path: Path) -> dict:
    try:
        return json.loads(path.read_text("utf-8"))
    except (OSError, ValueError):
        return {}


def save_state(state: dict) -> None:
    APP_DIR.mkdir(parents=True, exist_ok=True)
    STATE_FILE.write_text(json.dumps(state), "utf-8")
    try:
        os.chmod(STATE_FILE, 0o600)
    except OSError:
        pass


def device_password(device_id: str) -> Optional[str]:
    try:
        import keyring  # type: ignore
        return keyring.get_password("JarvisHomeHub", f"device:{device_id}")
    except Exception:
        return None


# ----------------------------------------------------------------------------------------------------------------
# Main loop
# ----------------------------------------------------------------------------------------------------------------

def execute(api: Supabase, command: dict, seen: Seen) -> tuple[bool, bool, str]:
    cid = command["id"]
    if cid in seen:
        return False, False, "Duplicate command ignored."
    if is_expired(command):
        return False, False, "Command expired before it could run."
    seen.add(cid)
    device = api.device(command["device_id"])
    if device is None or device.get("owner_id") != api.state.get("user_id"):
        return False, False, "Unknown device."
    action = command.get("action") or {}
    if device.get("connection") == "BLE":
        result = run_ble_command(device, action)
    else:
        result = run_lan_command(device, action, http_get, device_password(device["id"]))
    ok, verified, message = result
    try:
        api.report_state(device["id"], online=ok or "refused" in message, powered=("now on" in message) if (ok and verified and ("now on" in message or "now off" in message)) else None)
    except RuntimeError:
        pass
    return result


def run(api: Supabase, hub_id: str, state: dict) -> None:
    seen = Seen(state.get("seen"))
    backoff = ONLINE_POLL_S
    print(f"JARVIS Home Hub online ({hub_id}). Waiting for commands. Ctrl+C to stop.")
    while True:
        try:
            command = api.claim(hub_id)
            backoff = ONLINE_POLL_S
            if command is None:
                time.sleep(ONLINE_POLL_S)
                continue
            ok, verified, message = execute(api, command, seen)
            state["seen"] = seen.ids
            save_state(state)
            api.ack(command["id"], hub_id, ok, verified, message)
            print(f"[{time.strftime('%H:%M:%S')}] {'OK ' if ok else 'ERR'} {message}")
        except KeyboardInterrupt:
            print("Stopped.")
            return
        except Exception as e:  # network or auth trouble: wait and reconnect, never crash
            print(f"[{time.strftime('%H:%M:%S')}] connection problem: {e}. Retrying in {int(backoff)}s.", file=sys.stderr)
            time.sleep(backoff)
            backoff = min(MAX_BACKOFF_S, backoff * 2)


def main(argv: Optional[list[str]] = None) -> int:
    p = argparse.ArgumentParser(description="JARVIS Home Hub")
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("login", help="sign in with your JARVIS account and register this hub")
    sub.add_parser("run", help="start the hub")
    pw = sub.add_parser("set-device-password", help="store a LAN device password in the OS credential vault")
    pw.add_argument("device_id")
    args = p.parse_args(argv)

    APP_DIR.mkdir(parents=True, exist_ok=True)
    config, state = load_json(CONFIG_FILE), load_json(STATE_FILE)
    if args.cmd == "set-device-password":
        import getpass
        import keyring  # type: ignore
        keyring.set_password("JarvisHomeHub", f"device:{args.device_id}", getpass.getpass("Device password: "))
        print("Stored in the credential vault.")
        return 0
    if not config.get("supabase_url") or not config.get("publishable_key"):
        print(f"Create {CONFIG_FILE} from config.example.json (supabase_url and publishable_key).", file=sys.stderr)
        return 2
    api = Supabase(config["supabase_url"], config["publishable_key"], state)
    if args.cmd == "login":
        import getpass
        api.sign_in(input("JARVIS account email: ").strip(), getpass.getpass("Password: "))
        state.setdefault("hub_id", str(uuid.uuid4()))
        api.register_hub(state["hub_id"], config.get("hub_name") or socket.gethostname())
        save_state(state)
        print("Signed in and registered. Start it with: python jarvis_home_hub.py run")
        return 0
    if "hub_id" not in state or "refresh_token" not in state:
        print("Run `login` first.", file=sys.stderr)
        return 2
    api.register_hub(state["hub_id"], config.get("hub_name") or socket.gethostname())
    run(api, state["hub_id"], state)
    return 0


if __name__ == "__main__":
    sys.exit(main())
