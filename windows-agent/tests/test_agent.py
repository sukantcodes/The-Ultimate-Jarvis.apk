import base64, sys, tempfile, unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import jarvis_udn_agent as a
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature
from cryptography.hazmat.primitives import serialization


class FakeApi:
    def __init__(self): self.calls = []; self.key = None
    def call(self, action, args=None, device_id=None):
        self.calls.append((action, args)); return {"ok": True}


class Fake(a.Handlers):
    def __init__(self):
        super().__init__(); self.ran = 0
        self.table["device.info"] = self.count
    def count(self, p): self.ran += 1; return {"x": 1}, None


def agent(h=None):
    d = Path(tempfile.mkdtemp()); st = a.Store(d); api = FakeApi()
    ag = a.Agent(api, st, h or Fake()); ag.state["device_id"] = "dev"
    return ag, api


class T(unittest.TestCase):
    def test_canonical_matches_server_format(self):
        self.assertEqual(a.canonical("heartbeat", "d", "1", "n", "h"), "UDN1\nPOST\nheartbeat\nd\n1\nn\nh")

    def test_signature_is_der_and_verifies(self):
        k = ec.generate_private_key(ec.SECP256R1())
        sig = base64.b64decode(a.sign_der(k, "msg"))
        self.assertEqual(sig[0], 0x30)  # ASN.1 SEQUENCE, what the server converts to r||s
        r, s = decode_dss_signature(sig)
        k.public_key().verify(sig, b"msg", ec.ECDSA(hashes.SHA256()))
        spki = base64.b64decode(a.public_key_b64(k))
        serialization.load_der_public_key(spki)

    def test_nonce_shape(self):
        n = a.new_nonce(); self.assertEqual(len(n), 24); self.assertRegex(n, r"^[A-Za-z0-9]+$")

    def test_no_double_execution_on_redelivery(self):
        h = Fake(); ag, api = agent(h)
        c = {"id": "c1", "capability": "device.info", "params": {}}
        ag.execute(c); ag.execute(c)
        self.assertEqual(h.ran, 1)
        self.assertEqual([x[0] for x in api.calls].count("report_result"), 1)

    def test_success_only_when_handler_succeeds(self):
        ag, api = agent()
        ag.table = None
        ag.h.table["device.lock"] = lambda p: (None, ("lock_failed", "no"))
        ag.execute({"id": "c2", "capability": "device.lock", "params": {}})
        rep = [x for x in api.calls if x[0] == "report_result"][0][1]
        self.assertEqual(rep["status"], "failed"); self.assertEqual(rep["error_code"], "lock_failed")

    def test_consent_commands_are_rejected_not_executed(self):
        h = Fake(); ag, api = agent(h)
        ag.execute({"id": "c3", "capability": "device.info", "params": {}, "requires_consent": True})
        self.assertEqual(h.ran, 0)
        rep = [x for x in api.calls if x[0] == "report_result"][0][1]
        self.assertEqual(rep["status"], "rejected")

    def test_unknown_capability_rejected(self):
        ag, api = agent()
        ag.execute({"id": "c4", "capability": "camera.open", "params": {}})
        rep = [x for x in api.calls if x[0] == "report_result"][0][1]
        self.assertEqual(rep["error_code"], "unknown_capability")

    def test_launch_is_allow_list_only(self):
        data, err = a.Handlers().launch({"app": "cmd /c calc & del *"})
        self.assertIsNone(data); self.assertEqual(err[0], "app_not_allowed")

    def test_dpapi_refuses_off_windows(self):
        if sys.platform != "win32":
            with self.assertRaises(RuntimeError): a.protect(b"x")


if __name__ == "__main__":
    unittest.main()
