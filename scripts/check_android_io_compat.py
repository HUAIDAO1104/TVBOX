#!/usr/bin/env python3
"""Reject Java stream APIs absent on supported Android versions in final APK DEX files.

This deliberately checks packaged method references (including dependencies), not source
text. desugar_jdk_libs_nio does not backport these InputStream methods to Android 7-12.
Run Android NewApi lint as well; this is a binary regression guard for the 5.5.85 crash.
"""
import argparse
import struct
import zipfile
from pathlib import Path


def methods(data):
    if not data.startswith(b"dex\n"):
        raise ValueError("Not a standard DEX file")
    u32 = lambda offset: struct.unpack_from("<I", data, offset)[0]
    strings = []
    for index in range(u32(56)):
        offset = u32(u32(60) + index * 4)
        while data[offset] & 0x80:
            offset += 1
        offset += 1  # Skip the final byte of the UTF-16 length.
        strings.append(data[offset:data.index(0, offset)].decode("utf-8", errors="replace"))
    types = [strings[u32(u32(68) + i * 4)] for i in range(u32(64))]
    for index in range(u32(88)):
        owner, _, name = struct.unpack_from("<HHI", data, u32(92) + index * 8)
        yield types[owner], strings[name]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", nargs="+", type=Path)
    args = parser.parse_args()
    failures = []
    for apk in args.apk:
        count = 0
        with zipfile.ZipFile(apk) as archive:
            for name in archive.namelist():
                if not name.startswith("classes") or not name.endswith(".dex"):
                    continue
                count += 1
                for owner, method in methods(archive.read(name)):
                    if owner.startswith("Ljava/io/") and method in {"readNBytes", "readAllBytes", "skipNBytes"}:
                        failures.append(f"{apk.name}: {name}: {owner}->{method}")
        if not count:
            raise ValueError(f"No DEX files in {apk}")
        print(f"Checked {apk.name}: {count} DEX files")
    if failures:
        raise SystemExit("Unsupported Android stream API references:\n" + "\n".join(failures))
    print("PASS: no unsupported stream API references")


if __name__ == "__main__":
    main()
