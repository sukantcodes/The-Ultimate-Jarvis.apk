import json
import unittest
import urllib.error
from datetime import datetime, timedelta, timezone

import jarvis_home_hub as hub


def device(protocol, caps=("POWER", "READ_STATE"), **config):
    return {"id": "d1", "name": "Plug", "protocol": protocol, "address": "192.168.1.50", "capabilities": list(caps), "config": config}


class HubTests(unittest.TestCase):
    def test_tasmota_on_verified(self):
        urls = []
        def get(url, headers):
            urls.append(url); return '{"POWER":"ON"}'
        self.assertEqual(hub.run_lan_command(device("TASMOTA"), {"type": "POWER_ON"}, get), (True, True, "Plug is now on."))
        self.assertEqual(urls, ["http://192.168.1.50/cm?cmnd=Power%20On"])

    def test_opposite_answer_fails(self):
        ok, verified, _ = hub.run_lan_command(device("TASMOTA"), {"type": "POWER_ON"}, lambda u, h: '{"POWER":"OFF"}')
        self.assertFalse(ok); self.assertFalse(verified)

    def test_shelly_relay_index(self):
        urls = []
        def get(url, headers):
            urls.append(url); return '{"ison":false}'
        r = hub.run_lan_command(device("SHELLY_GEN1", relay="1"), {"type": "POWER_OFF"}, get)
        self.assertEqual(r[:2], (True, True)); self.assertEqual(urls, ["http://192.168.1.50/relay/1?turn=off"])

    def test_unreachable(self):
        def get(url, headers): raise urllib.error.URLError("no route")
        ok, _, msg = hub.run_lan_command(device("TASMOTA"), {"type": "POWER_ON"}, get)
        self.assertFalse(ok); self.assertIn("could not be reached", msg)

    def test_unsupported_capability_never_calls_network(self):
        def get(url, headers): raise AssertionError("must not be called")
        ok, _, _ = hub.run_lan_command(device("TASMOTA", caps=("READ_STATE",)), {"type": "POWER_ON"}, get)
        self.assertFalse(ok)

    def test_generic_without_status_is_unconfirmed(self):
        d = device("GENERIC_HTTP", caps=("POWER",), url_on="http://10.0.0.9/on", url_off="http://10.0.0.9/off")
        ok, verified, _ = hub.run_lan_command(d, {"type": "POWER_ON"}, lambda u, h: "OK")
        self.assertTrue(ok); self.assertFalse(verified)

    def test_invalid_address_rejected(self):
        d = device("TASMOTA"); d["address"] = "1.2.3.4/../x?y"
        self.assertFalse(hub.run_lan_command(d, {"type": "POWER_ON"}, lambda u, h: "{}")[0])

    def test_expiry(self):
        now = datetime.now(timezone.utc)
        self.assertTrue(hub.is_expired({"expires_at": (now - timedelta(seconds=1)).isoformat()}))
        self.assertFalse(hub.is_expired({"expires_at": (now + timedelta(minutes=1)).isoformat().replace("+00:00", "Z")}))
        self.assertTrue(hub.is_expired({}))
        self.assertTrue(hub.is_expired({"expires_at": "garbage"}))

    def test_seen_blocks_duplicates_and_is_bounded(self):
        s = hub.Seen(limit=3)
        for i in "abcd": s.add(i)
        self.assertNotIn("a", s); self.assertIn("d", s)
        s.add("d"); self.assertEqual(s.ids, ["b", "c", "d"])

    def test_parsers(self):
        self.assertIsNone(hub.parse_tasmota_power("nope")); self.assertTrue(hub.parse_tasmota_power('{"POWER1":"ON"}'))
        self.assertIsNone(hub.parse_shelly_ison("{}"))


if __name__ == "__main__":
    unittest.main()
