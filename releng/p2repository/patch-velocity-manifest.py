#!/usr/bin/env python3
"""Widen the slf4j import range of Velocity in the assembled p2 repository.

Fork patch: velocity-engine-core 2.4.1 declares
Import-Package org.slf4j;version="[1.7,2)", while JDT LS (and our own
products) ship slf4j 2.x, so the bundle never resolves. The range is widened
to [1.7,3) in place; fix-artifacts-metadata recomputes sizes and checksums
afterwards.

Usage: patch-velocity-manifest.py <p2 repository dir>
"""

import glob
import os
import sys
import zipfile

OLD = 'org.slf4j;version="[1.7,2)"'
NEW = 'org.slf4j;version="[1.7,3)"'


def unwrap(manifest):
    # manifest lines are wrapped at 72 bytes, continuation lines start with a space
    return manifest.replace("\r\n", "\n").replace("\n ", "")


def wrap(manifest):
    out = []
    for line in manifest.split("\n"):
        raw = line.encode("utf-8")
        first = True
        while True:
            limit = 72 if first else 71
            if len(raw) <= limit:
                out.append((b"" if first else b" ") + raw)
                break
            cut = limit
            while cut > 0 and (raw[cut] & 0xC0) == 0x80:  # do not split UTF-8 sequences
                cut -= 1
            out.append((b"" if first else b" ") + raw[:cut])
            raw = raw[cut:]
            first = False
    return b"\r\n".join(out)


def patch(jar):
    with zipfile.ZipFile(jar) as zin:
        entries = [(info, zin.read(info.filename)) for info in zin.infolist()]
    manifest = unwrap(dict((i.filename, d) for i, d in entries)["META-INF/MANIFEST.MF"].decode("utf-8"))
    if NEW in manifest:
        print(f"{jar}: already patched")
        return
    if manifest.count(OLD) != 1:
        sys.exit(f"{jar}: expected exactly one {OLD} in the manifest")
    manifest = wrap(manifest.replace(OLD, NEW))

    tmp = jar + ".tmp"
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as zout:
        for info, data in entries:
            zout.writestr(info, manifest if info.filename == "META-INF/MANIFEST.MF" else data)
    os.replace(tmp, jar)
    print(f"{jar}: slf4j range widened to [1.7,3)")


def main():
    jars = glob.glob(os.path.join(sys.argv[1], "plugins", "org.apache.velocity.engine-core_*.jar"))
    jars = [j for j in jars if ".source_" not in j]
    if not jars:
        sys.exit("org.apache.velocity.engine-core not found in the p2 repository")
    for jar in jars:
        patch(jar)


if __name__ == "__main__":
    main()
