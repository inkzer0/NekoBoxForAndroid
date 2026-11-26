#!/usr/bin/env python3
"""Check 64-bit native ELF segments in the final APK set or Play bundle."""
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from zipfile import ZIP_STORED, ZipFile

PAGE = 16384
ABIS = {"arm64-v8a", "x86_64"}
LIB = re.compile(r"(?:[^/]+/)?lib/(arm64-v8a|x86_64)/([^/]+[.]so)")


def check_elf(path):
    headers = subprocess.check_output(["readelf", "--file-header", "--program-headers", "--wide", path], text=True)
    if not re.search(r"Class:\s+ELF64", headers):
        raise ValueError("expected ELF64")
    loads = [line.split() for line in headers.splitlines() if line.lstrip().startswith("LOAD ")]
    if not loads:
        raise ValueError("no PT_LOAD segments")
    for load in loads:
        offset, address, align = (int(load[i], 16) for i in (1, 2, -1))
        if align < PAGE or (offset - address) % PAGE != 0:
            raise ValueError(f"PT_LOAD is not 16 KB aligned (p_align={align:#x})")


def check_archives(files):
    found = set()
    failed = False
    with tempfile.TemporaryDirectory() as work:
        lib = Path(work) / "library.so"
        for path in files:
            with ZipFile(path) as archive:
                for member in archive.infolist():
                    match = LIB.fullmatch(member.filename)
                    if match is None:
                        continue
                    abi, name = match.groups()
                    if name == "libgojni.so":
                        found.add(abi)
                    with archive.open(member) as src, lib.open("wb") as dest:
                        shutil.copyfileobj(src, dest)
                    try:
                        check_elf(lib)
                        if path.suffix == ".apk" and member.compress_type != ZIP_STORED:
                            raise ValueError("APK library is compressed; expected 16 KB ZIP-aligned storage")
                    except (subprocess.CalledProcessError, ValueError) as error:
                        print(f"{path.name}: {member.filename}: {error}", file=sys.stderr)
                        failed = True
                    else:
                        print(f"Verified ELF: {path.name}: {member.filename}")
    if missing := ABIS - found:
        print(f"Final artifacts are missing libgojni.so for {', '.join(sorted(missing))}", file=sys.stderr)
        failed = True
    return not failed


if __name__ == "__main__":
    files = [Path(p) for p in sys.argv[1:]]
    if not files:
        raise SystemExit("Specify the final APK set or Play AAB")
    if not check_archives(files):
        raise SystemExit(1)
