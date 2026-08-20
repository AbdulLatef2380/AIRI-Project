#!/usr/bin/env python3
"""Generate an auditable direct-dependency inventory for AIRI Core.

This script reports declared dependencies, not a legal opinion and not a
replacement for resolving the complete transitive Gradle graph.
"""

from __future__ import annotations

import argparse
import re
import sys
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / "gradle/libs.versions.toml"
APP_BUILD = ROOT / "app/build.gradle.kts"
NATIVE_BUILD = ROOT / "app/src/main/cpp/CMakeLists.txt"

LITERAL_DEPENDENCY = re.compile(
    r"\b(?:implementation|api|ksp|testImplementation|androidTestImplementation)\("
    r"(?:platform\()?\"([^\"]+)\"\)?\)"
)
CATALOG_DEPENDENCY = re.compile(r"\b(?:implementation|api|ksp|testImplementation|androidTestImplementation)\(libs\.([A-Za-z0-9_.-]+)\)")
DYNAMIC_VERSION = re.compile(r":(?:\+|latest\.[^\"]+|\[[^\]]*\])(?:\"|$)", re.IGNORECASE)


def catalog_coordinates(catalog: dict) -> dict[str, str]:
    versions = catalog.get("versions", {})
    result: dict[str, str] = {}
    for alias, value in catalog.get("libraries", {}).items():
        group = value.get("group")
        name = value.get("name")
        version = value.get("version")
        if isinstance(version, dict):
            version = versions.get(version.get("ref"))
        if group and name:
            result[alias.replace("-", ".")] = f"{group}:{name}:{version or 'BOM-managed'}"
    return result


def declared_dependencies() -> tuple[list[str], list[str], list[str]]:
    build = APP_BUILD.read_text(encoding="utf-8")
    catalog = tomllib.loads(CATALOG.read_text(encoding="utf-8"))
    aliases = catalog_coordinates(catalog)
    resolved_aliases = []
    unknown_aliases = []
    for alias in CATALOG_DEPENDENCY.findall(build):
        coordinate = aliases.get(alias)
        if coordinate:
            resolved_aliases.append(coordinate)
        else:
            unknown_aliases.append(alias)
    literals = LITERAL_DEPENDENCY.findall(build)
    return sorted(set(resolved_aliases + literals)), sorted(set(unknown_aliases)), literals


def native_components() -> list[str]:
    components = ["llama.cpp via JNI/CMake"] if NATIVE_BUILD.exists() else []
    if "vosk-android" in APP_BUILD.read_text(encoding="utf-8"):
        components.append("Vosk Android runtime")
    if "porcupine" in APP_BUILD.read_text(encoding="utf-8").lower():
        components.append("Picovoice Porcupine runtime")
    if "tensorflow-lite" in APP_BUILD.read_text(encoding="utf-8"):
        components.append("TensorFlow Lite runtime")
    return components


def markdown(dependencies: list[str], native: list[str]) -> str:
    rows = []
    for coordinate in dependencies:
        parts = coordinate.split(":")
        label = ":".join(parts[:2]) if len(parts) >= 2 else coordinate
        version = parts[2] if len(parts) >= 3 else "BOM-managed"
        rows.append(f"| `{label}` | `{version}` | Declared Gradle dependency | Review upstream license, notices and redistribution terms before distribution |")
    native_rows = "\n".join(
        f"| {component} | Native/runtime component | Confirm source, license notice, model or access-key terms before redistribution |"
        for component in native
    ) or "| None detected | — | — |"
    dependency_rows = "\n".join(rows) or "| None detected | — | — | — |"
    return f"""# AIRI Core Dependency Inventory

> **Generated from declared Gradle/catalog sources.** This is an engineering inventory, not a legal opinion, an SBOM, or a complete resolved transitive graph. A qualified reviewer must verify current license, notice, export, model, and distribution obligations before commercial release.

## Direct Android dependencies

| Component | Declared version | Source | Commercial review disposition |
|---|---:|---|---|
{dependency_rows}

## Native and runtime components

| Component | Type | Commercial review disposition |
|---|---|---|
{native_rows}

## Reproduction

```bash
python3 scripts/supply_chain_inventory.py --output docs/commercial/DEPENDENCY_INVENTORY.md
./gradlew :app:dependencies --configuration releaseRuntimeClasspath
```

The Gradle command is required before a transaction or release to capture the complete resolved, transitive dependency graph for the exact build environment.
"""


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=ROOT / "docs/commercial/DEPENDENCY_INVENTORY.md")
    args = parser.parse_args()

    dependencies, unknown_aliases, literals = declared_dependencies()
    source = APP_BUILD.read_text(encoding="utf-8")
    dynamic = sorted(set(DYNAMIC_VERSION.findall(source)))
    if unknown_aliases:
        print(f"[FAIL] Unresolved version-catalog aliases: {', '.join(unknown_aliases)}")
    else:
        print("[PASS] All declared catalog aliases resolve")
    if dynamic:
        print(f"[FAIL] Dynamic dependency versions: {', '.join(dynamic)}")
    else:
        print("[PASS] No dynamic dependency version is declared")
    output = args.output if args.output.is_absolute() else ROOT / args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(markdown(dependencies, native_components()), encoding="utf-8")
    print(f"[PASS] Wrote direct dependency inventory: {output.relative_to(ROOT)} ({len(dependencies)} entries)")
    return 1 if unknown_aliases or dynamic else 0


if __name__ == "__main__":
    sys.exit(main())
