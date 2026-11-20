"""JARVIS Universal Device Network - Windows companion agent.

Joins the same network as the Android app, using the same signed-request protocol (see supabase/functions/udn-api).
Status: implemented, NOT yet run on a physical Windows machine. The signing/canonical-string code is unit tested
against the server's algorithm (tests/test_agent.py); the OS-level handlers are Windows-only.

Security model
  * Signs in with the owner's JARVIS account (Supabase email + password) to obtain a user JWT. The password is never
    stored; only the refresh token is, protected with Windows DPAPI.
  * Generates an ECDSA P-256 key pair on first run. The private key is stored DPAPI-encrypted for this Windows user.
  * Every request is signed over action, device id, timestamp, nonce and the exact body bytes. The server rejects
    replays and unknown/revoked devices.
  * The service-role key is never used or needed. Only the public (publishable) key is configured.
  * A Windows PC is a child device. It becomes controllable only after a parent approves it.
"""
from __future__ import annotations

import argparse
import base64
import ctypes
import hashlib
import json
import os
import platform
import secrets
import shutil
import subprocess
import sys
import time
from ctypes import wintypes
from pathlib import Path
from typing import Any, Callable, Optional

import requests
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature

HEARTBEAT_S = 45
NONCE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
STATE_DIR = Path(os.environ.get("APPDATA", str(Path.home()))) / "JarvisUdnAgent"


# --------------------------------------------------------------------------- crypto (platform independent)

def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def canonical(action: str, device_id: str, ts: str, nonce: str, body_sha256: str) -> str:
    """Must match supabase/functions/udn-api/sigcrypto.ts canonicalRequest()."""
    return "\n".join(["UDN1", "POST", action, device_id, ts, nonce, body_sha256])


def new_nonce() -> str:
    return "".join(secrets.choice(NONCE_ALPHABET) for _ in range(24))


def sign_der(private_key: ec.EllipticCurvePrivateKey, message: str) -> str:
    """ECDSA P-256 / SHA-256, ASN.1 DER, base64 - the same encoding Java's SHA256withECDSA produces."""
    return base64.b64encode(private_key.sign(message.encode(), ec.ECDSA(hashes.SHA256()))).decode()


def public_key_b64(private_key: ec.EllipticCurvePrivateKey) -> str:
    der = private_key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    return base64.b64encode(der).decode()


# --------------------------------------------------------------------------- DPAPI secret storage (Windows)

class _Blob(ctypes.Structure):
    _fields_ = [("cbData", wintypes.DWORD), ("pbData", ctypes.POINTER(ctypes.c_char))]


def _blob(data: bytes) -> _Blob:
    buf = ctypes.create_string_buffer(data, len(data))
    return _Blob(len(data), ctypes.cast(buf, ctypes.POINTER(ctypes.c_char)))


def protect(data: bytes) -> bytes:
    if sys.platform != "win32":
        raise RuntimeError("DPAPI is only available on Windows; refusing to store secrets unprotected.")
    out = _Blob()
    if not ctypes.windll.crypt32.CryptProtectData(ctypes.byref(_blob(data)), "jarvis-udn", None, None, None, 0, ctypes.byref(out)):
        raise ctypes.WinError()
    try:
        return ctypes.string_at(out.pbData, out.cbData)
    finally:
        ctypes.windll.kernel32.LocalFree(out.pbData)


def unprotect(data: bytes) -> bytes:
    if sys.platform != "win32":
        raise RuntimeError("DPAPI is only available on Windows.")
    out = _Blob()
    if not ctypes.windll.crypt32.CryptUnprotectData(ctypes.byref(_blob(data)), None, None, None, None, 0, ctypes.byref(out)):
        raise ctypes.WinError()
    try:
        return ctypes.string_at(out.pbData, out.cbData)
    finally:
        ctypes.windll.kernel32.LocalFree(out.pbData)


class Store:
    """Per-user state. Everything secret goes through DPAPI."""

    def __init__(self, directory: Path = STATE_DIR):
        self.dir = directory
        self.dir.mkdir(parents=True, exist_ok=True)

    def read_json(self, name: str) -> dict:
        p = self.dir / name
        return json.loads(p.read_text()) if p.exists() else {}

    def write_json(self, name: str, data: dict) -> None:
        (self.dir / name).write_text(json.dumps(data))

    def load_key(self) -> ec.EllipticCurvePrivateKey:
        p = self.dir / "device.key"
        if p.exists():
            return serialization.load_der_private_key(unprotect(p.read_bytes()), password=None)
        key = ec.generate_private_key(ec.SECP256R1())
        der = key.private_bytes(serialization.Encoding.DER, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())
        p.write_bytes(protect(der))
        return key

    def reset(self) -> None:
        shutil.rmtree(self.dir, ignore_errors=True)
        self.dir.mkdir(parents=True, exist_ok=True)


# --------------------------------------------------------------------------- API client

class ApiError(Exception):
    def __init__(self, code: str, status: int = 0):
        super().__init__(code)
        self.code, self.status = code, status


class Api:
    def __init__(self, supabase_url: str, publishable_key: str, store: Store, key: ec.EllipticCurvePrivateKey):
        self.url = supabase_url.rstrip("/")
        self.pub = publishable_key
        self.store = store
        self.key = key
        self.token: Optional[str] = None
        self.token_exp = 0.0
        self.clock_offset = 0
        self.session = requests.Session()

    # --- account session (user JWT)
    def sign_in(self, email: str, password: str) -> None:
        r = self.session.post(f"{self.url}/auth/v1/token?grant_type=password", headers={"apikey": self.pub},
                              json={"email": email, "password": password}, timeout=20)
        if r.status_code != 200:
            raise ApiError("sign_in_failed", r.status_code)
        self._accept(r.json())

    def _accept(self, d: dict) -> None:
        self.token = d["access_token"]
        self.token_exp = time.time() + int(d.get("expires_in", 3600)) - 60
        self.store.write_json("session.json", {"refresh": base64.b64encode(protect(d["refresh_token"].encode())).decode()})

    def _ensure_token(self) -> str:
        if self.token and time.time() < self.token_exp:
            return self.token
        saved = self.store.read_json("session.json").get("refresh")
        if not saved:
            raise ApiError("not_signed_in")
        refresh = unprotect(base64.b64decode(saved)).decode()
        r = self.session.post(f"{self.url}/auth/v1/token?grant_type=refresh_token", headers={"apikey": self.pub},
                              json={"refresh_token": refresh}, timeout=20)
        if r.status_code != 200:
            raise ApiError("not_signed_in", r.status_code)
        self._accept(r.json())
        return self.token  # type: ignore[return-value]

    # --- signed device call
    def call(self, action: str, args: Optional[dict] = None, device_id: Optional[str] = None, _retry: bool = True) -> dict:
        body = json.dumps({**(args or {}), "action": action}, separators=(",", ":")).encode()
        ts = str(int(time.time()) + self.clock_offset)
        nonce = new_nonce()
        header_device = device_id or "register"
        sig = sign_der(self.key, canonical(action, header_device, ts, nonce, sha256_hex(body)))
        headers = {"apikey": self.pub, "Authorization": f"Bearer {self._ensure_token()}", "Content-Type": "application/json",
                   "X-UDN-Ts": ts, "X-UDN-Nonce": nonce, "X-UDN-Sig": sig}
        if device_id:
            headers["X-UDN-Device"] = device_id
        r = self.session.post(f"{self.url}/functions/v1/udn-api", data=body, headers=headers, timeout=25)
        try:
            data = r.json()
        except ValueError:
            data = {}
        if data.get("ok"):
            return data
        code = data.get("error") or f"http_{r.status_code}"
        if code == "stale_request" and _retry and r.headers.get("Date"):
            from email.utils import parsedate_to_datetime
            self.clock_offset = int(parsedate_to_datetime(r.headers["Date"]).timestamp() - time.time())
            return self.call(action, args, device_id, _retry=False)
        raise ApiError(code, r.status_code)


# --------------------------------------------------------------------------- capability handlers

def _run_ps(script: str, timeout: int = 15) -> str:
    out = subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command", script],
                         capture_output=True, text=True, timeout=timeout, check=True)
    return out.stdout.strip()


class Handlers:
    """Each handler returns (data, None) on verified success or (None, (code, message)) on failure."""

    def __init__(self) -> None:
        self.table: dict[str, Callable[[dict], tuple]] = {
            "device.info": self.info, "device.battery": self.battery, "device.connectivity": self.connectivity,
            "device.lock": self.lock, "app.launch": self.launch, "notify.show": self.notify,
        }

    def availability(self) -> list[dict]:
        win = sys.platform == "win32"
        why = None if win else "Not running on Windows."
        return [{"capability": c, "available": win, **({"reason": why} if why else {})} for c in self.table]

    def info(self, _p):
        return {"platform": "windows", "os": platform.platform(), "hostname": platform.node()}, None

    def battery(self, _p):
        if sys.platform != "win32":
            return None, ("unsupported", "Not running on Windows.")

        class Status(ctypes.Structure):
            _fields_ = [("ACLineStatus", ctypes.c_ubyte), ("BatteryFlag", ctypes.c_ubyte), ("BatteryLifePercent", ctypes.c_ubyte),
                        ("SystemStatusFlag", ctypes.c_ubyte), ("BatteryLifeTime", wintypes.DWORD), ("BatteryFullLifeTime", wintypes.DWORD)]
        s = Status()
        if not ctypes.windll.kernel32.GetSystemPowerStatus(ctypes.byref(s)) or s.BatteryLifePercent == 255:
            return None, ("battery_unavailable", "This PC reports no battery (desktop or unreadable).")
        return {"percent": int(s.BatteryLifePercent), "charging": s.ACLineStatus == 1}, None

    def connectivity(self, _p):
        try:
            requests.head("https://www.msftconnecttest.com/connecttest.txt", timeout=4)
            ok = True
        except requests.RequestException:
            ok = False
        return {"connected": ok, "type": "unknown", "internet_validated": ok}, None

    def lock(self, _p):
        if sys.platform != "win32":
            return None, ("unsupported", "Not running on Windows.")
        if not ctypes.windll.user32.LockWorkStation():
            return None, ("lock_failed", "Windows refused to lock the workstation.")
        return {"locked": True}, None  # LockWorkStation returning nonzero means the lock was initiated

    def launch(self, p):
        app = str(p.get("app", "")).strip().lower()
        allowed = {"notepad": "notepad.exe", "calculator": "calc.exe", "calc": "calc.exe", "paint": "mspaint.exe",
                   "explorer": "explorer.exe", "edge": "msedge.exe", "chrome": "chrome.exe"}
        exe = allowed.get(app)
        if not exe:  # allow-list only: a remote command can never run arbitrary executables or shell text
            return None, ("app_not_allowed", f"'{app}' is not on this PC's allowed-apps list.")
        try:
            subprocess.Popen([exe], close_fds=True)
        except OSError:
            return None, ("app_not_found", f"{exe} could not be started.")
        return {"launched": app}, None

    def notify(self, p):
        text = str(p.get("text", ""))[:200]
        if not text:
            return None, ("bad_request", "No text to show.")
        script = ("Add-Type -AssemblyName System.Windows.Forms;"
                  "$n=New-Object System.Windows.Forms.NotifyIcon;$n.Icon=[System.Drawing.SystemIcons]::Information;$n.Visible=$true;"
                  f"$n.ShowBalloonTip(5000,'JARVIS',$env:UDN_TEXT,[System.Windows.Forms.ToolTipIcon]::Info);Start-Sleep 6;$n.Dispose()")
        try:
            subprocess.Popen(["powershell", "-NoProfile", "-Command", script], env={**os.environ, "UDN_TEXT": text})
        except OSError:
            return None, ("notify_failed", "Could not show a notification.")
        return {"shown": True}, None


# --------------------------------------------------------------------------- agent loop

class Agent:
    def __init__(self, api: Api, store: Store, handlers: Optional[Handlers] = None):
        self.api, self.store = api, store
        self.h = handlers or Handlers()
        self.state = store.read_json("state.json")
        self.executed: dict[str, dict] = self.state.get("executed", {})

    @property
    def device_id(self) -> Optional[str]:
        return self.state.get("device_id")

    def enroll(self) -> None:
        if self.device_id:
            return
        r = self.api.call("register", {"public_key": public_key_b64(self.api.key), "platform": "windows",
                                       "name": platform.node()[:60] or "Windows PC", "manufacturer": "",
                                       "model": platform.machine(), "os_version": platform.platform()[:80], "app_version": "agent-1"})
        self.state["device_id"] = r["device_id"]
        self._save()
        print(f"Registered as {r['device_id']} (status: {r.get('status')}). A parent device must approve this PC.")

    def _save(self) -> None:
        # keep only recent entries so the file stays small
        recent = dict(sorted(self.executed.items(), key=lambda kv: kv[1].get("at", 0))[-300:])
        self.state["executed"] = recent
        self.store.write_json("state.json", self.state)

    def heartbeat(self) -> dict:
        return self.api.call("heartbeat", {"connection_type": "unknown", **self._battery_fields()}, self.device_id)

    def _battery_fields(self) -> dict:
        data, err = self.h.battery({})
        return {"battery_pct": data["percent"], "charging": data["charging"]} if data else {}

    def push_capabilities(self) -> None:
        self.api.call("update_capabilities", {"capabilities": self.h.availability(), "permissions": []}, self.device_id)

    def run_once(self) -> None:
        for c in self.api.call("poll_commands", {}, self.device_id).get("commands", []):
            self.execute(c)
        for cid, e in list(self.executed.items()):
            if not e.get("reported"):
                self.report(cid, e)

    def execute(self, c: dict) -> None:
        cid = c["id"]
        if cid in self.executed:  # redelivery: never execute twice
            return
        self.api.call("ack", {"command_id": cid}, self.device_id)
        if c.get("requires_consent"):
            entry = {"status": "rejected", "error_code": "consent_unavailable",
                     "error_message": "This PC cannot show an approval prompt for sensitive commands.", "at": time.time()}
        else:
            fn = self.h.table.get(c["capability"])
            if not fn:
                entry = {"status": "rejected", "error_code": "unknown_capability", "error_message": "This PC doesn't know that command.", "at": time.time()}
            else:
                try:
                    data, err = fn(c.get("params") or {})
                except Exception as exc:  # report, never crash the loop
                    data, err = None, ("handler_error", type(exc).__name__)
                entry = ({"status": "succeeded", "result": data} if data is not None
                         else {"status": "failed", "error_code": err[0], "error_message": err[1]})
                entry["at"] = time.time()
        self.executed[cid] = entry
        self._save()
        self.report(cid, entry)

    def report(self, cid: str, e: dict) -> None:
        body = {"command_id": cid, "status": e["status"]}
        for k_src, k_dst in (("result", "result"), ("error_code", "error_code"), ("error_message", "error_message")):
            if e.get(k_src) is not None:
                body[k_dst] = e[k_src]
        try:
            self.api.call("report_result", body, self.device_id)
            e["reported"] = True
            self._save()
        except ApiError as exc:
            if exc.code in ("not_found", "device_revoked"):
                e["reported"] = True
            # otherwise retried on the next loop

    def loop(self) -> None:
        failures = 0
        last_beat = 0.0
        pushed = False
        while True:
            try:
                if time.time() - last_beat >= HEARTBEAT_S:
                    hb = self.heartbeat()
                    last_beat = time.time()
                    if hb.get("status") == "authorized" and not pushed:
                        self.push_capabilities()
                        pushed = True
                    if hb.get("status") == "revoked":
                        print("This PC was removed from the network. Run with --reset to re-enroll.")
                        return
                if self.heartbeat_status_authorized():
                    self.run_once()
                failures = 0
                time.sleep(8)
            except ApiError as exc:
                if exc.code == "device_revoked":
                    print("This PC was removed from the network.")
                    return
                failures += 1
                time.sleep(min(60, 3 * 2 ** min(failures, 4)) + secrets.randbelow(3000) / 1000)
            except requests.RequestException:
                failures += 1
                time.sleep(min(60, 3 * 2 ** min(failures, 4)) + secrets.randbelow(3000) / 1000)

    def heartbeat_status_authorized(self) -> bool:
        try:
            return self.api.call("me", {}, self.device_id).get("device", {}).get("status") == "authorized"
        except ApiError:
            return False


def main(argv: Optional[list[str]] = None) -> int:
    ap = argparse.ArgumentParser(description="JARVIS Device Network Windows agent")
    ap.add_argument("--url", default=os.environ.get("JARVIS_SUPABASE_URL"), help="Supabase project URL")
    ap.add_argument("--key", default=os.environ.get("JARVIS_SUPABASE_PUBLISHABLE_KEY"), help="Supabase publishable (anon) key")
    ap.add_argument("--email", default=os.environ.get("JARVIS_EMAIL"))
    ap.add_argument("--reset", action="store_true", help="forget this PC's identity and sign-in")
    a = ap.parse_args(argv)
    if sys.platform != "win32":
        print("This agent only runs on Windows (it needs DPAPI for secure key storage).", file=sys.stderr)
        return 2
    if not a.url or not a.key:
        print("Set --url and --key (or JARVIS_SUPABASE_URL / JARVIS_SUPABASE_PUBLISHABLE_KEY).", file=sys.stderr)
        return 2
    store = Store()
    if a.reset:
        store.reset()
    api = Api(a.url, a.key, store, store.load_key())
    if not store.read_json("session.json"):
        import getpass
        email = a.email or input("JARVIS account email: ")
        api.sign_in(email, getpass.getpass("Password (not stored): "))
    agent = Agent(api, store)
    agent.enroll()
    agent.loop()
    return 0


if __name__ == "__main__":
    sys.exit(main())
