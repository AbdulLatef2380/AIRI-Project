# Gate 8 — Desktop Platform Services

## Scope

This gate adds an owned Desktop preferences adapter for non-sensitive UI state and confirms the existing local conversation recovery boundary. It does not add OAuth callbacks, token vault storage, microphone capture, STT, TTS, or a cross-device profile system.

| Area | Classification | Boundary |
|---|---|---|
| Non-sensitive Desktop preferences | `BUILD_VERIFIED` | `showCapabilityHints` is stored in an AIRI-owned properties file and defaults safely on missing or malformed data |
| Session history recovery | `BUILD_VERIFIED` | Existing conversation store reloads valid local records and skips malformed records |
| Local history clear | `BUILD_VERIFIED` | Clears only the Desktop-owned session log and AIRI-managed attachment copies |
| Desktop OAuth | `PLANNED` | No secure vault, callback receiver, or approved identity-provider adapter exists |
| Desktop voice | `PLANNED` | No microphone permission, capture, STT, TTS, or cancellation adapter exists |
| Linux settings interaction | `EXTERNAL_VERIFICATION_REQUIRED` | Requires manual toggle, restart, and visible persistence verification in the updated window |
| Windows settings interaction | `EXTERNAL_VERIFICATION_REQUIRED` | Requires an interactive Windows host and the same manual acceptance scenario |

## Behavioral Contract

Desktop preferences contain no credentials, model paths, OAuth tokens, or microphone permissions. The current setting only controls local display of capability shortcut hints. Reading a missing or invalid properties file returns the safe default rather than failing application startup. The toggle in the Desktop header saves the new value immediately through the owned adapter.

The conversation store remains the only recovery source. It restores only records it can decode and does not claim to recover model sessions, auth state, voice state, attachments outside its own storage root, or remote work. Clearing history is therefore a local-history cleanup, not account deletion or a privacy request fulfillment flow.

## Verification

```bash
./gradlew --no-daemon --max-workers=1 \
  '-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC' \
  '-Dkotlin.daemon.jvmargs=-Xmx1024m' \
  :app-desktop:test
# BUILD SUCCESSFUL — includes DesktopPreferencesTest
```

The preference test suite proves safe default loading and persistence across a fresh store instance. Existing Desktop agent tests continue to prove local history reload and attachment cleanup. Interactive setting persistence after process restart remains an external acceptance scenario because the current environment cannot inspect the rendered updated UI.

## Follow-up

Add authentication only alongside an OS-specific secure storage and OAuth callback design. Add voice only alongside a Desktop capture and synthesis adapter with explicit permission, stop, error, and device-loss paths. Neither capability may be exposed as an enabled Desktop action before its runtime acceptance evidence exists.
