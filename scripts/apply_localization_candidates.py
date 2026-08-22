#!/usr/bin/env python3
"""Apply validated localization candidate JSON without rewriting unrelated XML formatting."""
from __future__ import annotations

import html
import json
import re
import sys
from pathlib import Path
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app" / "src" / "main" / "res"
BUILD = ROOT / "build"


def values(path: Path) -> dict[str, str]:
    root = ET.parse(path).getroot()
    return {
        e.attrib["name"]: "".join(e.itertext()).strip()
        for e in root.findall("string")
        if e.attrib.get("name")
    }


def apply(locale: str) -> int:
    locale_code = locale.removeprefix("values-")
    candidate = json.loads((BUILD / f"localization_candidates_{locale_code}.json").read_text())
    if candidate.get("validationIssues"):
        raise ValueError(f"{locale}: candidate validation issues present")
    target_path = RES / locale / "strings.xml"
    source_values = values(RES / "values" / "strings.xml")
    target_values = values(target_path)
    text = target_path.read_text()
    replacements = 0
    for key, translated in candidate["items"].items():
        if target_values.get(key) != source_values.get(key):
            raise ValueError(f"{locale}:{key} changed after candidate generation")
        escaped = html.escape(translated, quote=False)
        pattern = re.compile(r'(<string\s+name="' + re.escape(key) + r'"(?:\s+[^>]*)?>)(.*?)(</string>)', re.DOTALL)
        text, count = pattern.subn(lambda m: m.group(1) + escaped + m.group(3), text, count=1)
        if count != 1:
            raise ValueError(f"{locale}:{key} expected exactly one XML string node, got {count}")
        replacements += 1
    target_path.write_text(text)
    values(target_path)  # parse check
    return replacements


def main() -> int:
    total = 0
    for locale in ("values-es", "values-zh"):
        count = apply(locale)
        total += count
        print(f"{locale}: applied={count}")
    print(f"applied_total={total}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
