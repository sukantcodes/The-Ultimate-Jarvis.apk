#!/usr/bin/env python3
"""Extract authorized Snaptube dotLottie animations from the user-supplied APK ZIP.

Usage:
  python3 tools/extract_snaptube_animations.py /path/to/Snaptube_7.63.1.76371401.zip

Only selected animation JSON and referenced image assets are copied into JARVIS assets.
The original APK is never copied into the repository.
"""
from __future__ import annotations

import argparse
import io
import zipfile
from pathlib import Path

ANIMATIONS = (
    "clean_app_junk",
    "animation_boost_loading",
    "animation_boost_noting",
    "animation_battery_loading",
    "animation_large_file_scan",
    "animation_scan_whats_app_loading",
    "animation_scan_whats_app_empty",
    "animation_all_finish",
    "animation_clean_noting",
    "animation_file_permission_guide",
)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source_zip", type=Path)
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("app/src/main/assets/lottie/snaptube"),
    )
    args = parser.parse_args()

    if not args.source_zip.is_file():
        raise SystemExit(f"Source ZIP not found: {args.source_zip}")

    with zipfile.ZipFile(args.source_zip) as outer:
        apk_names = [n for n in outer.namelist() if n.lower().endswith(".apk")]
        if not apk_names:
            raise SystemExit("The supplied ZIP does not contain an APK.")
        with zipfile.ZipFile(io.BytesIO(outer.read(apk_names[0]))) as apk:
            found = []
            for name in ANIMATIONS:
                member = f"assets/{name}.lottie"
                if member not in apk.namelist():
                    print(f"SKIP (not found): {member}")
                    continue
                with zipfile.ZipFile(io.BytesIO(apk.read(member))) as dotlottie:
                    json_candidates = [
                        item for item in dotlottie.namelist()
                        if item.startswith("animations/") and item.endswith(".json")
                    ]
                    if not json_candidates:
                        print(f"SKIP (no animation JSON): {member}")
                        continue
                    destination = args.output / name
                    destination.mkdir(parents=True, exist_ok=True)
                    (destination / "animation.json").write_bytes(
                        dotlottie.read(json_candidates[0])
                    )
                    copied = 1
                    for item in dotlottie.namelist():
                        if item.startswith("images/") and not item.endswith("/"):
                            target = destination / Path(item).name
                            target.write_bytes(dotlottie.read(item))
                            copied += 1
                    found.append(name)
                    print(f"Extracted {name}: {copied} files")

    if not found:
        raise SystemExit("No supported Lottie animations were extracted.")
    print(f"Done. Extracted {len(found)} animations to {args.output}")


if __name__ == "__main__":
    main()
