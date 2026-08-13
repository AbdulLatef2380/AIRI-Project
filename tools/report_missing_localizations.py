#!/usr/bin/env python3
"""Write missing string-resource keys and English source values for review."""

from __future__ import annotations

import argparse
import xml.etree.ElementTree as ET
from pathlib import Path


def load_strings(path: Path) -> dict[str, str]:
    root = ET.parse(path).getroot()
    return {
        element.attrib["name"]: "".join(element.itertext())
        for element in root.findall("string")
        if "name" in element.attrib
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--target", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    source = load_strings(args.source)
    target = load_strings(args.target)
    missing = [(key, source[key]) for key in sorted(source) if key not in target]

    lines = ["# Missing string resources", "", "| Key | English source |", "| --- | --- |"]
    lines.extend(
        f"| `{key}` | {value.replace('|', '\\|')} |" for key, value in missing
    )
    args.output.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"Wrote {len(missing)} missing keys to {args.output}")


if __name__ == "__main__":
    main()
