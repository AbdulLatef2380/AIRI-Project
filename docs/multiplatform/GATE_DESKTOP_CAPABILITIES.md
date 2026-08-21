# Gate 6 — Desktop Models, Skills, and Attachments

## Scope

This gate replaces the Desktop foundation's fabricated deterministic reply with a **capability-gated** Desktop path. It introduces shared, pure registries for models and skills; a truthful no-ready-model state; a native Desktop file-selection entry point; and private attachment staging. It does not add a Desktop inference engine, remote-provider credentials, or an executable Desktop skill adapter.

| Area | Deliverable | Classification |
|---|---|---|
| Model registry | Platform-neutral descriptors, availability, deterministic selection, default resolution | `BUILD_VERIFIED` |
| Skill registry | Platform-neutral descriptors, Desktop platform gate, matching and selection policy | `BUILD_VERIFIED` |
| Desktop model adapter | Android-native and unconfigured remote models shown unavailable with reasons | `BUILD_VERIFIED` |
| Desktop skill adapter | Android skills represented as unavailable until a Desktop adapter exists | `BUILD_VERIFIED` |
| Attachment staging | Explicit native picker entry point, bounded validation, private copy, metadata-only persistence | `BUILD_VERIFIED` |
| Attachment cleanup | Clear-history deletes AIRI-managed copied attachments | `BUILD_VERIFIED` |
| Linux Desktop package | DEB built; process and titled window stayed alive in software-rendered X11 runtime | `RUNTIME_VERIFIED` |
| Linux file-picker interaction | Native file selection and full UI interaction after this change | `EXTERNAL_VERIFICATION_REQUIRED` |
| Windows file-picker interaction | Requires a real interactive Windows desktop | `EXTERNAL_VERIFICATION_REQUIRED` |
| Desktop model inference | No compatible execution adapter or provider configuration is included | `PLANNED` |
| Desktop skill execution | No compatible executable skill adapter is included | `PLANNED` |

## Behavioral Changes

`DesktopAgent` no longer claims to have processed a request through AIRI Core. When no ready Desktop model exists, it records the user request and returns a clear configuration state with a one-step plan to configure a Desktop model. The current Android-native model is explicitly unavailable on Desktop, and the remote provider is explicitly marked as requiring a Desktop adapter and credentials. A model or skill selection that is unknown, unavailable, or unsupported is rejected without silently changing the selection.

Attachment selection is explicit. The Desktop adapter validates the item count and size through the shared `AttachmentPolicy`, normalizes the visible file name, generates an AIRI-owned stored file name, copies accepted content under `~/.airi-desktop/attachments`, and persists only local metadata. It never persists the original external source path. Clearing Desktop history deletes the stored copies referenced by messages and staged attachments.

## Test Matrix

| Test | Required result |
|---|---|
| Unavailable Android-native model on Desktop | Selection is rejected and no model is selected |
| Unknown model | Selection returns a typed rejection |
| Android-only skill on Desktop | Selection is rejected by the platform gate |
| Unavailable Desktop skill | Selection remains blocked with a reason |
| Deterministic ordering | Model and skill registries have stable order and default resolution |
| Request without model | No fabricated assistant answer; persisted state declares model configuration is required |
| Accepted attachment | Source is copied to AIRI-managed storage and source path is not recorded in metadata |
| Clear history | AIRI-managed attachment copy is removed |

## Evidence

The following commands completed on 21 August 2026 in the Linux build environment.

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=/home/ubuntu/android-sdk

./gradlew --no-daemon --max-workers=1 \
  '-Dorg.gradle.jvmargs=-Xmx1280m -XX:MaxMetaspaceSize=448m -XX:+UseSerialGC' \
  '-Dkotlin.daemon.jvmargs=-Xmx896m' \
  :core-domain:desktopTest :core-domain:compileDebugKotlinAndroid :app-desktop:test
# BUILD SUCCESSFUL — 19 actionable tasks

./gradlew --no-daemon --max-workers=1 \
  '-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC' \
  '-Dkotlin.daemon.jvmargs=-Xmx1024m' \
  :app-desktop:test :app-desktop:packageDeb
# BUILD SUCCESSFUL — DEB written to app-desktop/build/compose/binaries/main/deb/airi_1.0.0-1_amd64.deb

python3 tools/verify_core_changes.py
# 41/41 checks passed
python3 tools/security_scan.py
# PASS — 8/8 checks passed
python3 scripts/airi_cross_platform_health.py
# PASS — 0 errors
python3 scripts/airi_toolchain_health.py
# PASS — 12 common Kotlin source files; no forbidden platform APIs

./gradlew --no-daemon --max-workers=1 \
  '-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC' \
  '-Dkotlin.daemon.jvmargs=-Xmx1024m' \
  '-Pkotlin.compiler.execution.strategy=in-process' \
  :app:testDebugUnitTest
# BUILD SUCCESSFUL — 43 actionable tasks
```

The packaged DEB was extracted and launched in an isolated X11 software-rendering environment. The AIRI process remained alive for eight seconds and an `AIRI Desktop` window was discovered (`WINDOW_ID=2097159`). The first accelerated Xvfb attempt failed to create a GL context; the software-rendered attempt was successful. This runtime finding supports the Linux package and window lifecycle only. It does not substitute for user-driven native file-picker selection or Windows interactive UI validation.

## Follow-up Requirements

A Desktop model adapter must be implemented and observed generating a real response before any Desktop inference claim becomes `RUNTIME_VERIFIED`. A Desktop skill adapter must preserve permission and consent semantics before changing skill availability. Native file picking requires a real Linux and Windows interaction sequence covering selection, rejection, display, send, session deletion, and full-data cleanup.
