import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
import apk_facts  # noqa: E402

CERT = "AB" * 32
BADGING = (
    "package: name='com.jarvis.assistant' versionCode='1737' versionName='1.8.7-ota.1737' "
    "platformBuildVersionName='14'\n"
    "sdkVersion:'26'\ntargetSdkVersion:'34'\n"
)
SIGNERS = "Signer #1 certificate DN: CN=Jarvis\nSigner #1 certificate SHA-256 digest: " + CERT.lower() + "\n"


class ApkFactsTest(unittest.TestCase):
    def test_accepts_matching_apk(self):
        facts = apk_facts.check(BADGING, SIGNERS, "com.jarvis.assistant", "1737", CERT)
        self.assertEqual(facts["version_code"], 1737)
        self.assertEqual(facts["min_sdk"], 26)
        self.assertEqual(facts["cert_sha256"], CERT)

    def test_accepts_colon_separated_pin(self):
        pin = ":".join(CERT[i:i + 2] for i in range(0, 64, 2))
        apk_facts.check(BADGING, SIGNERS, "com.jarvis.assistant", "1737", pin)

    def test_rejects_wrong_package(self):
        with self.assertRaises(ValueError):
            apk_facts.check(BADGING.replace("com.jarvis.assistant", "com.evil.app"), SIGNERS, "com.jarvis.assistant", "1737", CERT)

    def test_rejects_wrong_version_code(self):
        with self.assertRaises(ValueError):
            apk_facts.check(BADGING, SIGNERS, "com.jarvis.assistant", "1738", CERT)

    def test_rejects_wrong_certificate(self):
        other = "CD" * 32
        with self.assertRaises(ValueError):
            apk_facts.check(BADGING, SIGNERS.replace(CERT.lower(), other.lower()), "com.jarvis.assistant", "1737", CERT)

    def test_rejects_extra_signer(self):
        extra = SIGNERS + "Signer #2 certificate SHA-256 digest: " + ("cd" * 32) + "\n"
        with self.assertRaises(ValueError):
            apk_facts.check(BADGING, extra, "com.jarvis.assistant", "1737", CERT)

    def test_rejects_missing_signer_and_bad_pin(self):
        with self.assertRaises(ValueError):
            apk_facts.check(BADGING, "", "com.jarvis.assistant", "1737", CERT)
        with self.assertRaises(ValueError):
            apk_facts.check(BADGING, SIGNERS, "com.jarvis.assistant", "1737", "short")

    def test_rejects_unparseable_badging(self):
        with self.assertRaises(ValueError):
            apk_facts.check("garbage", SIGNERS, "com.jarvis.assistant", "1737", CERT)


if __name__ == "__main__":
    unittest.main()
