#!/usr/bin/env python3
"""Parses `aapt2 dump badging` and `apksigner verify --print-certs` output and enforces the OTA release gates.

Usage: apk_facts.py <badging.txt> <apksigner-certs.txt> <expected_package> <expected_version_code> <pinned_cert_sha256> [expected_version_name]
Prints KEY=VALUE lines (package, version_code, version_name, min_sdk, cert_sha256) on success; exits non-zero with a
one-line reason on any mismatch. Used by the release job and by test_apk_facts.py.
"""
import re
import sys


def parse_badging(text):
    m = re.search(r"package: name='([^']*)' versionCode='(\d+)' versionName='([^']*)'", text)
    if not m:
        raise ValueError("badging has no package line")
    sdk = re.search(r"^sdkVersion:'(\d+)'", text, re.M)
    if not sdk:
        raise ValueError("badging has no sdkVersion")
    return {"package": m.group(1), "version_code": int(m.group(2)), "version_name": m.group(3), "min_sdk": int(sdk.group(1))}


def parse_signers(text):
    """Returns the set of signer certificate SHA-256 digests (upper-case hex)."""
    return {m.group(1).replace(":", "").upper() for m in re.finditer(r"certificate SHA-256 digest: ([0-9A-Fa-f:]{64,95})", text)}


def check(badging, signers_text, package, version_code, pinned, version_name=None):
    facts = parse_badging(badging)
    if facts["package"] != package:
        raise ValueError("package name %s != expected %s" % (facts["package"], package))
    if facts["version_code"] != int(version_code):
        raise ValueError("versionCode %d != expected %s" % (facts["version_code"], version_code))
    if version_name is not None and facts["version_name"] != version_name:
        raise ValueError("versionName %s != expected %s" % (facts["version_name"], version_name))
    pinned = pinned.replace(":", "").upper()
    if not re.fullmatch(r"[A-F0-9]{64}", pinned):
        raise ValueError("pinned certificate digest is not a SHA-256")
    signers = parse_signers(signers_text)
    if not signers:
        raise ValueError("no signer certificate found")
    if signers != {pinned}:
        raise ValueError("signer certificates %s do not exactly match the pinned certificate" % sorted(signers))
    facts["cert_sha256"] = pinned
    return facts


def main(argv):
    if len(argv) not in (6, 7):
        print("usage: apk_facts.py <badging> <certs> <package> <version_code> <pinned_cert> [version_name]", file=sys.stderr)
        return 2
    try:
        with open(argv[1], encoding="utf-8", errors="replace") as f:
            badging = f.read()
        with open(argv[2], encoding="utf-8", errors="replace") as f:
            certs = f.read()
        facts = check(badging, certs, argv[3], argv[4], argv[5], argv[6] if len(argv) == 7 else None)
    except (ValueError, OSError) as error:
        print("APK verification failed: %s" % error, file=sys.stderr)
        return 1
    for key in ("package", "version_code", "version_name", "min_sdk", "cert_sha256"):
        print("%s=%s" % (key, facts[key]))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
