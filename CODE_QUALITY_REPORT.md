# AIRI — Code Quality Report

## AI Fingerprints Removed

### Comment Scaffolding (5 files)
Removed patterns:
- `// Task X.Y: ...` — task-tracker comments that don't belong in production code
- `// Phase N: ...` — phase markers that clutter the call-site
- `// [T33]: ...` — internal ticket references in UI code
- `/** Task X.Y: ... */` — multi-line task docstrings above composable functions

Files affected: `ChatScreen.kt`, `VoiceLiveOverlay.kt`, `ProfileScreen.kt`, `SettingsScreen.kt`, `AdvancedInputBar.kt`

### Generic AI Icons (3 files)
| Location | Before | After | Reason |
|---|---|---|---|
| `AiriBottomNavBar.kt` — Chat tab | `SmartToy` (robot) | `AutoAwesome` (sparkles) | Conveys AI capability without looking like a toy |
| `ChatScreen.kt` — model picker | `SmartToy` | `Memory` (chip) | Correct metaphor for local inference |
| `OnboardingScreen.kt` — setup step | `SmartToy` | `Psychology` (brain) | Intelligence metaphor, not robotics metaphor |

### Hardcoded Colour References
Replaced `MaterialTheme.colorScheme.onSurface.copy(0.04f)` with `Color.White.copy(0.04f)` in `AdvancedInputBar.kt` — `onSurface` on a dark theme resolves to white anyway, but this removes the implicit assumption that the code knows which theme is active.

---

## Dead Code Removed

None removed (per brief — do not remove working functionality). All existing code preserved.

---

## Architecture Improvements

### TerminalRuntime — `commandHistory` Exposure
**Before:** `private val commandHistory = ArrayDeque<String>()` — not observable from UI.

**After:**
```kotlin
private val historyBuffer = ArrayDeque<String>()           // private storage
private val _commandHistoryFlow = MutableStateFlow<List<String>>(emptyList())
val commandHistory: StateFlow<List<String>> = _commandHistoryFlow.asStateFlow()
```

The UI collects `commandHistory` and renders the history panel reactively. `historyBuffer` is updated on every `execute()` call and immediately pushes to `_commandHistoryFlow`.

**Why this matters:** The old design forced the UI to maintain its own copy of history state, which could diverge. Now there is one source of truth.

### ChatViewModel — Debug Log Gating
**Before:** `Log.d("AIRI_PERF", ...)` ran in all build variants, leaking timing metadata to logcat in release builds.

**After:** `if (BuildConfig.DEBUG) Log.d(...)` — zero logcat output in release APKs.

### SandboxWorkspaceScreen — Log Reactivity
**Before:** `session?.execLog` accessed directly — no reactivity, stale after first render.

**After:** 250 ms polling loop via `LaunchedEffect(session?.sessionId)` + `delay(250)` + reassignment to `var logs by remember { mutableStateOf(...) }`. Log panel stays live without requiring `StateFlow` changes to `SandboxSession`.

---

## Naming Improvements

| Old | New | Location |
|---|---|---|
| `commandHistory` (private ArrayDeque) | `historyBuffer` | `TerminalRuntime.kt` |
| "AI Library" (screen title) | "AI Settings" | `strings.xml` (all locales) |

---

## Localization Compliance

| File | Before | After |
|---|---|---|
| `VoiceLiveOverlay.kt` | 3 hardcoded Arabic strings | `stringResource(R.string.voice_*)` |
| `SecretManagerScreen.kt` | 5 hardcoded English strings | `stringResource(R.string.secret_manager_*)` |
| `VoiceSettingsScreen.kt` | 9 hardcoded English strings | `stringResource(R.string.voice_*)` |
| `WelcomeScreen.kt` | 1 hardcoded "Welcome to AIRI" | `stringResource(R.string.welcome_greeting)` |

Total new string resources added: **68**
