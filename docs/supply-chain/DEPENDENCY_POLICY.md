# AIRI dependency and supply-chain policy

**Status:** P0/P1 policy baseline, reviewed 2026-11-12.

## Scope

This policy covers Gradle/Android dependencies, Firebase Functions dependencies, the vendored llama.cpp/ggml native tree, and runtime assets. It does not modify signing, certificates, biometric security, or payment behavior.

## Rules

1. Android versions are declared in `gradle/libs.versions.toml`; new dependencies must use the catalog rather than a literal version in a module build file.
2. Dynamic dependency selectors (`+`, `latest.*`, ranges, and snapshots) are prohibited. `scripts/supply_chain_inventory.py` fails when it detects one in `app/build.gradle.kts`.
3. `gradle/verification-metadata.xml` is committed and verifies artifact metadata and SHA-256 checksums. Signature verification is currently **not enabled** because the existing metadata has no trusted-key baseline; enabling it requires an independently reviewed keyring and a clean resolution run.
4. The Functions project uses exact direct versions in `functions/package.json` and commits `functions/package-lock.json`. `npm ci --ignore-scripts` is the reproducible install command. The Node engine is 20; the current sandbox used Node 22 only to create/validate the lockfile and this is not a release validation.
5. `jitpack.io` and `alphacephei.com/maven/` remain present because the current build declares artifacts that may require them. Removing either repository requires a successful dependency graph resolution and build regression first. Content filtering is a follow-up P1 task, not an unverified edit.
6. All release builds must capture both `debugRuntimeClasspath` and `releaseRuntimeClasspath` resolution output, run the SBOM generator, and archive the exact commit and toolchain.
7. A component with unknown source, version, hash, or license is labelled `NOT_VERIFIED`/`NOASSERTION`; it must not be silently presented as cleared for commercial redistribution.

## Commands

```bash
# direct declared inventory and dynamic-version guard
python3 scripts/supply_chain_inventory.py

# native tree hashes and toolchain
python3 tools/native_snapshot_manifest.py

# SPDX-2.3 and CycloneDX-1.5 declared SBOM
python3 scripts/generate_sbom.py

# Functions reproducible install
npm --prefix functions ci --ignore-scripts

# Android resolution (requires Android SDK and a clean network/cache policy)
./gradlew :app:dependencies --configuration debugRuntimeClasspath --no-daemon --console=plain
./gradlew :app:dependencies --configuration releaseRuntimeClasspath --no-daemon --console=plain
```

## Current limitation

The 2026-11-12 sandbox did not have `ANDROID_HOME`/`ANDROID_SDK_ROOT`, so Gradle stopped during `:app` configuration before resolving either Android graph. The generated SBOM therefore marks the Android portion as a **declared direct catalog**, not a complete transitive resolution. This is an explicit limitation, not a pass claim.

## Sources

- [Gradle dependency verification](https://docs.gradle.org/current/userguide/dependency_verification.html)
- [Android dependency verification](https://developer.android.com/build/dependency-verification)
- [llama.cpp official repository](https://github.com/ggml-org/llama.cpp)
- [Vosk official model catalogue](https://alphacephei.com/vosk/models)
