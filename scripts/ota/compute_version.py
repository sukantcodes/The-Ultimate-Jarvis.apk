#!/usr/bin/env python3
"""Single source of truth for the version of every production APK (manual production build and OTA release).

Usage: compute_version.py <app/build.gradle.kts> <github_run_number> <branch>
Prints KEY=VALUE lines: base, code, name, slug, tag, asset.

versionCode = CODE_OFFSET + run number of the build workflow. The run number only ever increases, so every new build is
newer than every earlier one, whether it was built by hand or published over the air. The offset keeps all codes far above
the codes of builds made before this scheme existed (143/144) and leaves headroom if the counter ever restarted.
versionName = <base>.<versionCode>, so an OTA build always reads as newer than the plain base version (1.8.8 < 1.8.8.10120).
"""
import re
import sys

CODE_OFFSET = 10000
LEGACY_MAX_CODE = 144          # the highest versionCode shipped before this scheme (production 1.8.8)
MAX_VERSION_CODE = 2_000_000_000


def base_version(gradle_text):
    m = re.search(r'configuredVersionName\s*=.*?\?:\s*"(\d+(?:\.\d+){1,2})"', gradle_text, re.S)
    if not m:
        raise ValueError("could not find the default version name in build.gradle.kts")
    return m.group(1)


def branch_slug(branch):
    slug = re.sub(r"[^a-z0-9]+", "-", branch.lower()).strip("-")[:60]
    return slug or "branch"


def compute(gradle_text, run_number, branch):
    if not re.fullmatch(r"\d{1,9}", str(run_number)) or int(run_number) < 1:
        raise ValueError("run number must be a positive integer")
    code = CODE_OFFSET + int(run_number)
    if code <= LEGACY_MAX_CODE or code > MAX_VERSION_CODE:
        raise ValueError("versionCode %d is outside the allowed range" % code)
    base = base_version(gradle_text)
    name = "%s.%d" % (base, code)
    if len(name) > 40:
        raise ValueError("version name too long")
    slug = branch_slug(branch)
    return {
        "base": base, "code": code, "name": name, "slug": slug,
        "tag": "ota-%s-%d" % (slug, code), "asset": "jarvis-%s-%d.apk" % (slug, code),
    }


def main(argv):
    if len(argv) != 4:
        print("usage: compute_version.py <build.gradle.kts> <run_number> <branch>", file=sys.stderr)
        return 2
    try:
        with open(argv[1], encoding="utf-8") as f:
            result = compute(f.read(), argv[2], argv[3])
    except (ValueError, OSError) as error:
        print("Version computation failed: %s" % error, file=sys.stderr)
        return 1
    for key in ("base", "code", "name", "slug", "tag", "asset"):
        print("%s=%s" % (key, result[key]))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
