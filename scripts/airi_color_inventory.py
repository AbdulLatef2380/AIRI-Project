#!/usr/bin/env python3
"""Inventory Compose color usage and verify theme contrast contracts.

The inventory intentionally scans production Kotlin outside ui/theme so every
literal Color(...) call, Color.White/Black reference, and Cosmic*/Semantic*
token use is reviewable. It emits stable JSON for CI artifacts and fails on
WCAG contrast regressions in every supported ThemeMode.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCE_ROOT = ROOT / "app/src/main"
EXCLUDED_PARTS = {"ui", "theme"}
COLOR_CALL = re.compile(r"\bColor\s*\(\s*(0x[0-9A-Fa-f]{8}|0x[0-9A-Fa-f]{6})")
COLOR_REF = re.compile(r"\bColor\.(White|Black)\b")
TOKEN_REF = re.compile(r"\b(Cosmic[A-Za-z0-9_]*|Semantic[A-Za-z0-9_]*)\b")

# Foreground/background contracts represented directly by production token names.
# SYSTEM has both OS branches; validating DARK and LIGHT covers both branches.
CONTRAST_CONTRACTS = {
    "DARK": [("Color.White", "CosmicBlack"), ("Color.White", "SurfaceBase"), ("CosmicAccent", "Color(0xFF071329)"), ("SemanticError", "CosmicBlack")],
    "LIGHT": [("LightOnBackground", "LightBackground"), ("LightOnSurface", "LightSurface"), ("Color.White", "LightPrimary"), ("Color.White", "Color(0xFFB3261E)")],
    "SYSTEM": [("Color.White", "CosmicBlack"), ("LightOnBackground", "LightBackground")],
    "AMOLED": [("Color.White", "Color.Black"), ("Color.White", "Color(0xFF0A0A0A)"), ("CosmicAccent", "Color(0xFF071329)"), ("SemanticError", "Color.Black")],
}


def _files() -> list[Path]:
    return [p for p in sorted(SOURCE_ROOT.rglob("*.kt")) if not {"ui", "theme"}.issubset(set(p.parts))]


def inventory(root: Path = ROOT) -> dict:
    source_root = root / "app/src/main"
    entries: list[dict] = []
    for path in sorted(source_root.rglob("*.kt")):
        if "ui" in path.parts and "theme" in path.parts:
            continue
        rel = str(path.relative_to(root))
        for line_no, line in enumerate(path.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
            for match in COLOR_CALL.finditer(line):
                entries.append({"file": rel, "line": line_no, "kind": "Color", "value": match.group(0)})
            for match in COLOR_REF.finditer(line):
                entries.append({"file": rel, "line": line_no, "kind": "ColorReference", "value": f"Color.{match.group(1)}"})
            for match in TOKEN_REF.finditer(line):
                entries.append({"file": rel, "line": line_no, "kind": "ThemeToken", "value": match.group(1)})
    by_kind: dict[str, int] = {}
    by_file: dict[str, int] = {}
    for entry in entries:
        by_kind[entry["kind"]] = by_kind.get(entry["kind"], 0) + 1
        by_file[entry["file"]] = by_file.get(entry["file"], 0) + 1
    return {"schema": 1, "scope": "app/src/main Kotlin excluding app/src/main/.../ui/theme", "total": len(entries), "counts": dict(sorted(by_kind.items())), "files": dict(sorted(by_file.items())), "entries": entries}


def _parse_colors(root: Path = ROOT) -> dict[str, tuple[int, int, int]]:
    values: dict[str, tuple[int, int, int]] = {"Color.White": (255, 255, 255), "Color.Black": (0, 0, 0)}
    pattern = re.compile(r"(?:val|private\s+val)\s+(\w+)\s*=\s*Color\(\s*0x([0-9A-Fa-f]{6,8})\s*\)")
    for path in [root / "app/src/main/java/com/airi/assistant/ui/theme/Color.kt", root / "app/src/main/java/com/airi/assistant/ui/theme/Theme.kt"]:
        for name, raw in pattern.findall(path.read_text(encoding="utf-8")):
            raw = raw[-6:]
            values[name] = tuple(int(raw[i:i + 2], 16) for i in (0, 2, 4))
    return values


def _resolve(value: str, colors: dict[str, tuple[int, int, int]]) -> tuple[int, int, int]:
    if value.startswith("Color(0x"):
        raw = value.split("0x", 1)[1].rstrip(")")[-6:]
        return tuple(int(raw[i:i + 2], 16) for i in (0, 2, 4))
    if value in colors:
        return colors[value]
    raise KeyError(value)


def _relative_luminance(rgb: tuple[int, int, int]) -> float:
    channels = []
    for channel in rgb:
        c = channel / 255.0
        channels.append(c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4)
    return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2]


def contrast_ratio(foreground: tuple[int, int, int], background: tuple[int, int, int]) -> float:
    a, b = sorted((_relative_luminance(foreground), _relative_luminance(background)), reverse=True)
    return (a + 0.05) / (b + 0.05)


def contrast_report(root: Path = ROOT) -> dict:
    colors = _parse_colors(root)
    results = []
    for mode, contracts in CONTRAST_CONTRACTS.items():
        for foreground, background in contracts:
            ratio = contrast_ratio(_resolve(foreground, colors), _resolve(background, colors))
            results.append({"mode": mode, "foreground": foreground, "background": background, "ratio": round(ratio, 3), "pass": ratio >= 4.5})
    return {"minimum_ratio": 4.5, "pass": all(item["pass"] for item in results), "checks": results}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path)
    parser.add_argument("--check-contrast", action="store_true", help="also enforce all ThemeMode contrast contracts")
    args = parser.parse_args(argv)
    report = inventory()
    report["contrast"] = contrast_report()
    payload = json.dumps(report, indent=2, sort_keys=True, ensure_ascii=False) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(payload, encoding="utf-8")
    else:
        print(payload, end="")
    if args.check_contrast and not report["contrast"]["pass"]:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
