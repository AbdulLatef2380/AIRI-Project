# Gate 5 — Memory Hardening Verification

## Scope

This gate establishes portable **memory policy contracts** in `core-domain/commonMain`. It does not move Room, embeddings, attachment files, or Android persistence to Desktop. Those components remain platform adapters and must earn their own runtime evidence before any platform support claim is made.

| Deliverable | Location | Status |
|---|---|---|
| Scope-aware immutable memory contract | `core-domain/.../memory/MemoryEntry.kt` | `SOURCE_VERIFIED` |
| Deterministic retrieval ranking and token budget | `core-domain/.../memory/MemoryRankingPolicy.kt` | `BUILD_VERIFIED` |
| Retention, expiry, and deletion eligibility policy | `core-domain/.../memory/MemoryRetentionPolicy.kt` | `BUILD_VERIFIED` |
| Shared policy test matrix | `core-domain/.../memory/MemoryPoliciesTest.kt` | `BUILD_VERIFIED` |
| Existing selective admission policy | `core-domain/.../memory/MemoryAdmissionPolicy.kt` | `BUILD_VERIFIED` |
| Android application regression | `:app:testDebugUnitTest` | `BUILD_VERIFIED` |

## Contract Guarantees

The shared contracts make owner identity and optional session scope required at memory creation. `MemoryRankingPolicy` filters owner and requested session **before** ranking; it also excludes inactive entries and never returns entries exceeding the requested count or token budget. Ties use creation time and then stable ID, yielding deterministic output.

`MemoryRetentionPolicy` classifies a memory as `ACTIVE`, `EXPIRED`, or `DELETE_ELIGIBLE`. A deletion eligibility timestamp takes precedence over expiry. This is a policy guarantee only: platform storage adapters remain responsible for physical deletion or cryptographic erasure of database rows, embedding indexes, files, sync queues, and caches.

## Shared Test Matrix

| Test | Expected result |
|---|---|
| Owner isolation | An entry from another owner is never returned |
| Session isolation | A different session is excluded from a session-scoped request |
| Retrieval budget | The selected entries fit within the token budget; an oversized candidate is skipped |
| Stable ordering | Equal scores resolve by a stable ID tie-breaker |
| Exact expiry | An entry expires exactly at its expiry timestamp and is not retrieved |
| Deletion eligibility | A deletion-eligible entry is classified as such even when expired |

## Evidence

The following commands completed on 21 August 2026 in the controlled Linux build environment.

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=/home/ubuntu/android-sdk

./gradlew --no-daemon --max-workers=1 \
  '-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=384m -XX:+UseSerialGC' \
  '-Dkotlin.daemon.jvmargs=-Xmx768m' \
  :core-domain:desktopTest :core-domain:compileDebugKotlinAndroid
# BUILD SUCCESSFUL — 11 actionable tasks

python3 tools/verify_core_changes.py
# 41/41 checks passed
python3 tools/security_scan.py
# PASS — 8/8 checks passed
python3 scripts/airi_cross_platform_health.py
# PASS — 0 error(s)
python3 scripts/airi_toolchain_health.py
# PASS — 10 common Kotlin source files; no forbidden platform APIs

./gradlew --no-daemon --max-workers=1 \
  '-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC' \
  '-Dkotlin.daemon.jvmargs=-Xmx1024m' \
  '-Pkotlin.compiler.execution.strategy=in-process' \
  :app:testDebugUnitTest
# BUILD SUCCESSFUL — 43 actionable tasks
```

## Explicit Non-Claims and Follow-up Work

| Area | Current classification | Reason |
|---|---|---|
| Android Room memory storage | `BUILD_VERIFIED` | Existing adapter behavior is retained; the shared contracts do not replace it in this gate |
| Android physical account deletion | `SOURCE_VERIFIED` | The full Room wipe exists, but the current caller still needs the documented disk-layer deletion orchestration |
| Desktop memory persistence | `PLANNED` | No Desktop persistence adapter or runtime evidence exists yet |
| Desktop semantic retrieval | `PLANNED` | JNI and Android embedding service are intentionally not portable |
| Cross-device user identity | `PLANNED` | Existing Android memory storage is session-scoped; a cross-device owner adapter is a separate design and migration task |

The next storage adapter work must map Android entities at the repository boundary, preserve existing Room migrations, and prove deletion through the database, attachment filesystem, embeddings, derived summaries, caches, and queued synchronization records. No runtime support claim may be made before that evidence exists.
