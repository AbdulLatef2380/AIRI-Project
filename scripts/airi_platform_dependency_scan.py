#!/usr/bin/env python3
"""Classify AIRI source files by their platform dependencies.

The scan is intentionally conservative: a COMMON_CANDIDATE has no detected
Android, native, or JVM-only platform dependency. It is an extraction
candidate, not evidence that the code already compiles in Kotlin commonMain.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter, defaultdict
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable

ANDROID_PATTERNS = {
    "android.*": re.compile(r"\b(?:import\s+)?android\.[A-Za-z0-9_.]+"),
    "androidx.*": re.compile(r"\b(?:import\s+)?androidx\.[A-Za-z0-9_.]+"),
    "Context": re.compile(r"\bContext\b"),
    "Activity": re.compile(r"\b(?:Activity|ComponentActivity|FragmentActivity)\b"),
    "Service": re.compile(r"\b(?:Service|LifecycleService|JobIntentService)\b"),
    "BroadcastReceiver": re.compile(r"\bBroadcastReceiver\b"),
    "Intent": re.compile(r"\b(?:Intent|PendingIntent|IntentFilter)\b"),
    "WorkManager": re.compile(r"\b(?:WorkManager|CoroutineWorker|WorkerParameters|WorkRequest)\b"),
    "Room": re.compile(r"\b(?:RoomDatabase|@Database|@Dao|@Entity|Room\.databaseBuilder)\b"),
    "DataStore": re.compile(r"\b(?:DataStore|PreferencesDataStore)\b"),
    "Android lifecycle": re.compile(r"\b(?:LifecycleOwner|ViewModel|viewModelScope|lifecycleScope|repeatOnLifecycle)\b"),
    "Android permissions": re.compile(r"\b(?:Manifest\.permission|requestPermissions|checkSelfPermission|PermissionChecker)\b"),
    "Android filesystem": re.compile(r"\b(?:ContentResolver|contentResolver|DocumentFile|MediaStore|FileProvider|Uri)\b"),
    "Android notifications": re.compile(r"\b(?:NotificationManager|NotificationChannel|NotificationCompat)\b"),
    "Android audio": re.compile(r"\b(?:AudioRecord|AudioTrack|AudioManager|TextToSpeech|SpeechRecognizer)\b"),
    "Android camera": re.compile(r"\b(?:CameraX|CameraProvider|ImageCapture|PreviewView)\b"),
    "Android media": re.compile(r"\b(?:MediaPlayer|MediaRecorder|ExoPlayer|MediaSession)\b"),
    "Android Compose": re.compile(r"\b(?:setContent|AndroidView|LocalContext|rememberLauncherForActivityResult)\b"),
}

NATIVE_PATTERNS = {
    "JNI declaration": re.compile(r"\bexternal\s+fun\b"),
    "native library loading": re.compile(r"\bSystem\.loadLibrary\b|\blibairi_native\b"),
    "JNI/C++ source": re.compile(r"\bJNIEXPORT\b|\bJNIEnv\b|\bllama\.cpp\b"),
    "CMake build": re.compile(r"\badd_library\b|\btarget_link_libraries\b|\bCMakeLists\.txt\b"),
}

JVM_PLATFORM_PATTERNS = {
    "JVM file APIs": re.compile(r"\b(?:java\.io\.|java\.nio\.file\.|kotlin\.io\.path\.|Files\.)"),
    "JVM process APIs": re.compile(r"\b(?:ProcessBuilder|Runtime\.getRuntime|System\.getenv|System\.getProperty)\b"),
    "desktop AWT/Swing": re.compile(r"\b(?:java\.awt\.|javax\.swing\.)"),
}

SOURCE_ROOTS = (
    "app/src/main/java",
    "app/src/main/kotlin",
    "app/src/test/java",
    "app/src/test/kotlin",
    "app/src/androidTest/java",
    "app/src/androidTest/kotlin",
    "app/src/main/cpp",
)


@dataclass(frozen=True)
class FileFinding:
    path: str
    language: str
    classifications: list[str]
    android_dependencies: list[str]
    native_dependencies: list[str]
    platform_boundaries: list[str]


def relative_sources(repo: Path) -> Iterable[Path]:
    source_roots = [repo / root for root in SOURCE_ROOTS]
    source_roots.extend(sorted(repo.glob("core-*/src")))
    for source_root in source_roots:
        if not source_root.exists():
            continue
        for suffix in ("*.kt", "*.java", "*.c", "*.cc", "*.cpp", "*.h", "CMakeLists.txt"):
            yield from source_root.rglob(suffix)


def matched_names(content: str, patterns: dict[str, re.Pattern[str]]) -> list[str]:
    return [name for name, pattern in patterns.items() if pattern.search(content)]


def classify(path: Path, repo: Path) -> FileFinding:
    content = path.read_text(encoding="utf-8", errors="replace")
    relative_path = path.relative_to(repo).as_posix()
    android = matched_names(content, ANDROID_PATTERNS)
    native = matched_names(content, NATIVE_PATTERNS)
    boundaries = matched_names(content, JVM_PLATFORM_PATTERNS)

    if "/cpp/" in f"/{relative_path}" or path.name == "CMakeLists.txt":
        if "JNI/C++ source" not in native:
            native.append("JNI/C++ source")

    classifications: list[str] = []
    if native:
        classifications.append("NATIVE_RUNTIME_REQUIRED")
    if android:
        classifications.append("ANDROID_ONLY")
    if boundaries and not android:
        classifications.append("PLATFORM_ABSTRACTION_REQUIRED")
    if not android and not native and not boundaries:
        classifications.extend(("COMMON_CANDIDATE", "DESKTOP_CANDIDATE", "WEB_CANDIDATE"))

    return FileFinding(
        path=relative_path,
        language=path.suffix.lstrip(".") or path.name,
        classifications=classifications or ["PLATFORM_ABSTRACTION_REQUIRED"],
        android_dependencies=android,
        native_dependencies=native,
        platform_boundaries=boundaries,
    )


def write_markdown(findings: list[FileFinding], output: Path) -> None:
    category_counts = Counter(category for finding in findings for category in finding.classifications)
    android_counts = Counter(name for finding in findings for name in finding.android_dependencies)
    native_counts = Counter(name for finding in findings for name in finding.native_dependencies)
    boundary_counts = Counter(name for finding in findings for name in finding.platform_boundaries)
    classified = defaultdict(list)
    for finding in findings:
        for category in finding.classifications:
            classified[category].append(finding)

    lines = [
        "# Platform Dependency Scan",
        "",
        "This report is generated by `scripts/airi_platform_dependency_scan.py`. It classifies source files conservatively to identify Android leakage and safe extraction candidates. A `COMMON_CANDIDATE` is **not** evidence that the file is already portable; it must still compile in `commonMain` and pass tests.",
        "",
        "## Classification Summary",
        "",
        "| Classification | Files | Meaning |",
        "| --- | ---: | --- |",
    ]
    meanings = {
        "COMMON_CANDIDATE": "No detected Android, native, or JVM-only platform API.",
        "ANDROID_ONLY": "Direct Android or AndroidX dependency detected.",
        "DESKTOP_CANDIDATE": "Common candidate whose source shape does not exclude Desktop.",
        "WEB_CANDIDATE": "Common candidate whose source shape does not exclude Web.",
        "PLATFORM_ABSTRACTION_REQUIRED": "JVM/platform API detected without an Android dependency; isolate behind a real boundary before sharing.",
        "NATIVE_RUNTIME_REQUIRED": "JNI, CMake, C/C++, or native library dependency detected.",
    }
    for category in sorted(meanings):
        lines.append(f"| `{category}` | {category_counts[category]} | {meanings[category]} |")

    def dependency_table(title: str, counts: Counter[str]) -> None:
        if not counts:
            return
        lines.extend(("", f"## {title}", "", "| Dependency signal | Files |", "| --- | ---: |"))
        for name, count in counts.most_common():
            lines.append(f"| {name} | {count} |")

    dependency_table("Detected Android Dependencies", android_counts)
    dependency_table("Detected Native Runtime Dependencies", native_counts)
    dependency_table("Detected JVM Platform Boundaries", boundary_counts)

    for category in (
        "COMMON_CANDIDATE",
        "PLATFORM_ABSTRACTION_REQUIRED",
        "NATIVE_RUNTIME_REQUIRED",
        "ANDROID_ONLY",
    ):
        rows = classified.get(category, [])
        lines.extend(("", f"## {category} Files", "", "| Path | Android dependencies | Native dependencies | Platform boundary |", "| --- | --- | --- | --- |"))
        for finding in sorted(rows, key=lambda item: item.path):
            android = ", ".join(finding.android_dependencies) or "—"
            native = ", ".join(finding.native_dependencies) or "—"
            boundaries = ", ".join(finding.platform_boundaries) or "—"
            lines.append(f"| `{finding.path}` | {android} | {native} | {boundaries} |")

    lines.extend((
        "",
        "## Review Rules",
        "",
        "A future common source set must contain no `android.*`, `androidx.*`, Android lifecycle, Room, WorkManager, JNI, or NDK references. Platform interfaces are permitted only when a concrete Android, Desktop, or Web boundary exists. Each extracted module must supply compilation and test evidence before its status becomes `BUILDS` or `RUNTIME_VERIFIED`.",
        "",
    ))
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text("\n".join(lines), encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--json", dest="json_path", type=Path, default=None)
    parser.add_argument("--markdown", dest="markdown_path", type=Path, default=None)
    args = parser.parse_args()

    repo = args.repo.resolve()
    if not (repo / ".git").exists():
        print(f"Repository not found: {repo}", file=sys.stderr)
        return 2

    findings = [classify(path, repo) for path in sorted(set(relative_sources(repo)))]
    json_path = args.json_path or repo / "reports/multiplatform/platform_dependency_scan.json"
    markdown_path = args.markdown_path or repo / "docs/multiplatform/PLATFORM_DEPENDENCY_SCAN.md"
    json_path.parent.mkdir(parents=True, exist_ok=True)
    json_path.write_text(
        json.dumps(
            {
                "schema_version": 1,
                "repository": repo.name,
                "files_scanned": len(findings),
                "findings": [asdict(finding) for finding in findings],
            },
            indent=2,
            sort_keys=True,
        )
        + "\n",
        encoding="utf-8",
    )
    write_markdown(findings, markdown_path)

    totals = Counter(category for finding in findings for category in finding.classifications)
    print(f"Scanned {len(findings)} source files.")
    for category in sorted(totals):
        print(f"{category}: {totals[category]}")
    print(f"JSON: {json_path.relative_to(repo)}")
    print(f"Markdown: {markdown_path.relative_to(repo)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
