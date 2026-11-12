#!/usr/bin/env python3
"""Create a deterministic provenance manifest for AIRI's vendored native runtime."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path

EXCLUDED_DIRS = {".git", ".gradle", "build", "outputs", "captures"}
NATIVE_ROOTS = (Path("app/src/main/cpp/llama"), Path("app/src/main/cpp/CMakeLists.txt"), Path("app/src/main/cpp/LlamaBridge.cpp"))

def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()

def files_under(path: Path):
    if path.is_file():
        yield path
        return
    for candidate in sorted(path.rglob("*")):
        if candidate.is_file() and not any(part in EXCLUDED_DIRS for part in candidate.parts):
            yield candidate

def git_value(root: Path, *args: str) -> str | None:
    try:
        return subprocess.check_output(["git", "-C", str(root), *args], text=True).strip()
    except (OSError, subprocess.CalledProcessError):
        return None

def parse_build_config(root: Path) -> dict:
    gradle = (root / "app/build.gradle.kts").read_text(encoding="utf-8")
    cmake = (root / "app/src/main/cpp/CMakeLists.txt").read_text(encoding="utf-8")
    ndk = re.search(r'ndkVersion\s*=\s*"([^"]+)"', gradle)
    cmake_version = re.search(r'version\s*=\s*"([^"]+)"', gradle)
    cmake_min = re.search(r'cmake_minimum_required\(VERSION\s+([^\)]+)\)', cmake)
    declared_lib = bool(re.search(r'add_library\(\s*airi_native\s+SHARED', cmake, re.S))
    return {
        "ndk_version": ndk.group(1) if ndk else None,
        "gradle_cmake_version": cmake_version.group(1) if cmake_version else None,
        "cmake_minimum_version": cmake_min.group(1).strip() if cmake_min else None,
        "shared_library": "airi_native",
        "shared_library_declared": declared_lib,
        "release_abis": ["arm64-v8a"],
        "debug_abis": ["x86_64"],
    }

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--output", type=Path, default=None)
    args = parser.parse_args()
    root = args.root.resolve()
    entries = []
    for native_root in NATIVE_ROOTS:
        absolute = root / native_root
        if not absolute.exists():
            raise SystemExit(f"missing native root: {native_root}")
        for path in files_under(absolute):
            entries.append({"path": path.relative_to(root).as_posix(), "sha256": sha256_file(path), "bytes": path.stat().st_size})
    tree = hashlib.sha256()
    for entry in entries:
        tree.update(f"{entry['path']}\0{entry['sha256']}\0{entry['bytes']}\n".encode())
    config = parse_build_config(root)
    manifest = {
        "schema": 1,
        "generated_by": "tools/native_snapshot_manifest.py",
        "repository": git_value(root, "remote", "get-url", "origin"),
        "commit": git_value(root, "rev-parse", "HEAD"),
        "native_tree_sha256": tree.hexdigest(),
        "native_file_count": len(entries),
        "toolchain": config,
        "jni_bridge": {"source": "app/src/main/cpp/LlamaBridge.cpp", "library": "airi_native"},
        "files": entries,
    }
    output = args.output or (root / "docs/native/NATIVE_SNAPSHOT_MANIFEST.json")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"output": str(output), "commit": manifest["commit"], "tree": manifest["native_tree_sha256"], "files": len(entries)}, ensure_ascii=False))
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
