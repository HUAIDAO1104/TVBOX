#!/usr/bin/env python3

import argparse
import hashlib
import json
from pathlib import Path


def parse_asset(value: str) -> tuple[str, Path]:
    key, separator, path = value.partition("=")
    if not separator or not key or not path:
        raise argparse.ArgumentTypeError("asset must be KEY=APK_PATH")
    apk = Path(path)
    if not apk.is_file():
        raise argparse.ArgumentTypeError(f"APK does not exist: {apk}")
    return key, apk


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate the PianduoDuo OTA release manifest")
    parser.add_argument("--repo", required=True, help="GitHub repository in OWNER/REPO form")
    parser.add_argument("--version-code", required=True, type=int)
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--notes", default="稳定性与设备兼容性优化")
    parser.add_argument("--mandatory", action="store_true")
    parser.add_argument("--asset", action="append", type=parse_asset, required=True)
    parser.add_argument("--output", type=Path, default=Path("latest.json"))
    args = parser.parse_args()

    root = f"https://github.com/{args.repo}/releases/latest/download"
    apks = {}
    for key, apk in args.asset:
        apks[key] = {
            "fileName": apk.name,
            "url": f"{root}/{apk.name}",
            "sha256": sha256(apk),
            "size": apk.stat().st_size,
        }

    manifest = {
        "versionCode": args.version_code,
        "versionName": args.version_name,
        "releaseNotes": args.notes,
        "mandatory": args.mandatory,
        "apks": apks,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
