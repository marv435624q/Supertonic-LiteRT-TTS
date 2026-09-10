#!/usr/bin/env python3
"""Fail unless every ELF LOAD segment is aligned to at least 16 KiB."""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path


def main() -> int:
    if not 2 <= len(sys.argv) <= 4:
        print(
            "usage: verify_elf_16k.py ELF_FILE [LABEL] [READELF]",
            file=sys.stderr,
        )
        return 2

    elf = Path(sys.argv[1])
    label = sys.argv[2] if len(sys.argv) >= 3 else str(elf)
    readelf = sys.argv[3] if len(sys.argv) >= 4 else "readelf"
    if not elf.is_file():
        print(f"[ERROR] {label}: ELF file is missing: {elf}", file=sys.stderr)
        return 2

    result = subprocess.run(
        [readelf, "-lW", str(elf)],
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        print(result.stderr, file=sys.stderr, end="")
        print(f"[ERROR] {label}: readelf failed", file=sys.stderr)
        return result.returncode or 2

    alignments: list[int] = []
    for line in result.stdout.splitlines():
        fields = line.split()
        if fields and fields[0] == "LOAD":
            alignments.append(int(fields[-1], 0))

    rendered = ",".join(hex(value) for value in alignments) or "none"
    if not alignments or any(value < 16_384 for value in alignments):
        print(
            f"[ERROR] {label}: invalid 16 KB ELF LOAD alignment: {rendered}",
            file=sys.stderr,
        )
        return 1

    print(f"[PASS] {label}: 16 KB ELF LOAD alignment {rendered}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
