# AIRI Cross-Platform Milestone 2 — Progress Delivery

## Revision

**Branch:** `cp-foundation`

**Revision:** `8fe42375` — `Desktop: Add local preferences`
**Protected reference:** `architecture-refactor` was not modified.

## Delivered Gates

| Gate | Commit | Delivered scope | Verification |
|---|---|---|---|
| Agent runtime hardening | `82c74c32` | Shared lifecycle generations, cancellation, terminal outcomes, and Android gate bridge | Shared tests and Android/Windows/audits passed |
| Memory hardening | `3ffb919f` | Shared memory entry, ranking, retention, expiry, deletion eligibility, owner/session isolation, token budget | Shared tests, Android regression, Windows CI, and audits passed |
| Desktop models, skills, and attachments | `9317fbeb` | Capability-gated model and skill registries, private attachment staging/cleanup, truthful no-model status | Desktop tests/package, Android CI, Windows CI, and audits passed |
| Desktop productization | `03864deb` | Desktop tokens, minimum window, responsive layout, disabled capability states, tested Ctrl+N/Ctrl+K/Esc policy | Desktop tests/package, Linux process/window runtime, Android CI, Windows CI, and audits passed |
| Desktop local preferences | `8fe42375` | Non-sensitive stored hint preference and truthful auth/voice boundaries | Desktop tests, Android CI, Windows CI, and audits passed |

## Latest Remote Evidence

The final revision passed all configured workflows:

| Workflow | Result |
|---|---|
| [AIRI Android CI](https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/32457847074) | `success` |
| [AIRI Desktop Windows](https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/32457847091) | `success` |
| [AIRI Deep Audit](https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/32457847085) | `success` |
| [AIRI Architecture Audit](https://github.com/AbdulLatef2380/AIRI-Project/actions/runs/32457847166) | `success` |

## Product State

Desktop no longer presents a fabricated deterministic assistant response. In the absence of a compatible Desktop model adapter, it persists the local request and shows a precise model-configuration state. Model and skill menus remain capability-gated; unavailable options are disabled with reasons. Attachment files are selected explicitly, size-validated, copied under AIRI-managed storage, represented without external source paths, and removed by local-history cleanup.

The Desktop workspace now has its own design tokens, responsive layout, window minimum, composer focus behavior, and keyboard command policy. Non-sensitive preference persistence is implemented. Authentication, token vaults, OAuth callbacks, microphone capture, STT, TTS, model inference, and executable skills remain explicitly unavailable or planned until OS-specific adapters and runtime acceptance evidence exist.

## Acceptance Boundaries

| Capability | Linux | Windows |
|---|---|---|
| Package and application process | `RUNTIME_VERIFIED` for DEB process/window lifecycle | `PROCESS_VERIFIED` for MSI install/launch/cleanup |
| Interactive Desktop UI | `EXTERNAL_VERIFICATION_REQUIRED` for the updated visual/layout/shortcut sequence | `EXTERNAL_VERIFICATION_REQUIRED` on real Windows hardware |
| Model inference | `PLANNED` | `PLANNED` |
| Executable skills | `PLANNED` | `PLANNED` |
| File-picker interaction | `EXTERNAL_VERIFICATION_REQUIRED` | `EXTERNAL_VERIFICATION_REQUIRED` |
| OAuth and secure tokens | `PLANNED` | `PLANNED` |
| Voice | `PLANNED` | `PLANNED` |

## External Acceptance Checklist

On a Linux desktop and a Windows desktop, launch the packaged application, resize it above and below the compact breakpoint, use Ctrl+N, Ctrl+K, Esc, Enter, Shift+Enter, Send, model/skill disabled-state menus, attachment picker cancel/selection/rejection, clear history, close, restart, and validate local preference persistence. Do not classify model, auth, voice, or skill execution as supported until adapters complete their own runtime acceptance.

## Supporting Gate Documents

- `GATE_MEMORY_HARDENING.md`
- `GATE_DESKTOP_CAPABILITIES.md`
- `GATE_DESKTOP_PRODUCTIZATION.md`
- `GATE_DESKTOP_PLATFORM_SERVICES.md`
- `GATE_DESKTOP_LINUX.md`
- `GATE_DESKTOP_WINDOWS.md`
