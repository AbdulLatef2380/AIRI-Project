#!/usr/bin/env python3
from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA_HOME = "/usr/lib/jvm/java-17-openjdk-amd64"
ANDROID_HOME = "/home/ubuntu/android-sdk"
GRADLE_ARGS = [
    "./gradlew",
    "--no-daemon",
    "--max-workers=1",
    "-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=384m -XX:+UseSerialGC",
]


def run(label: str, command: list[str], env: dict[str, str]) -> bool:
    print(f"\n== {label} ==")
    result = subprocess.run(command, cwd=ROOT, env=env, check=False)
    if result.returncode:
        print(f"FAILED: {label}")
        return False
    print(f"PASSED: {label}")
    return True


def main() -> int:
    env = os.environ.copy()
    env["JAVA_HOME"] = env.get("JAVA_HOME", JAVA_HOME)
    env["ANDROID_HOME"] = env.get("ANDROID_HOME", ANDROID_HOME)
    checks = [
        ("Toolchain health", [sys.executable, "scripts/airi_toolchain_health.py"]),
        ("Shared core", GRADLE_ARGS + [":core-domain:desktopTest", ":core-domain:compileDebugKotlinAndroid"]),
        ("Android unit tests", GRADLE_ARGS + [":app:testDebugUnitTest"]),
        ("Android lint", GRADLE_ARGS + [":app:lintDebug"]),
        ("Android debug build", GRADLE_ARGS + [":app:assembleDebug"]),
        ("AndroidTest APK", GRADLE_ARGS + [":app:assembleDebugAndroidTest"]),
        ("Source regression", [sys.executable, "tools/verify_core_changes.py"]),
        ("Security scan", [sys.executable, "tools/security_scan.py"]),
        ("Core health", [sys.executable, "scripts/airi_core_health.py"]),
        ("Platform health", [sys.executable, "scripts/airi_cross_platform_health.py"]),
    ]
    for label, command in checks:
        if not run(label, command, env):
            return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
