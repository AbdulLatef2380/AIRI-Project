# AIRI — Changelog

## Phase 1: Feature Restoration

### ChatScreen (2786 lines restored from 540)
- **Restored** `AiriChatTopBar` — full top bar with model pill (status dot indicator), credits badge, history button, overflow menu
- **Restored** `AiriChatInputBar` — complete input bar with attachment picker, voice recording, live voice mode, smart reply chips, plan mode toggle
- **Restored** `UserBubble` — long-press menu (copy, edit, share, delete), attachment previews, timestamps
- **Restored** `AiBubble` — share, speak, feedback buttons, agent trace expand, streaming cursor
- **Restored** `AiStreamingBubble` — blinking cursor, animated content
- **Restored** `AiriHistoryPanel` — slide-in session list with timestamps and preview
- **Restored** `AiriModelPickerSheet` — local + cloud model selection with status indicators
- **Restored** Generation Settings dialog
- **Restored** Agent Plan bottom sheet with task status, step cards
- **Restored** Offline / integrity / context-reset banner system
- **Restored** `AdvancedChatInputBar` → now correctly calls `AiriChatInputBar` (was calling non-existent stub)
- **Improved** Empty state: animated cosmic orb (layered radial gradient, breathing scale + alpha) + 4 suggestion prompt chips
- **Improved** Bubble shapes: using `AIRIShapes.userBubble` / `AIRIShapes.aiBubble` tokens
- **Improved** Model pill: live status dot (green=local ready, blue=cloud, yellow=loading, violet=generating)
- **Improved** Credits badge: icon-first layout, `AIRIShapes.pill`
- **Improved** AI avatar: layered radial gradient orb, ExtraBold "A" letterform
- **Improved** Scroll-to-bottom FAB: gradient background, spring bounce animation
- **Improved** History panel header: refined close button affordance
- **Fixed** Import cleanup: removed unused `BIOMETRIC_WEAK` import; switched to `import com.airi.assistant.ui.theme.*`

---

## Phase 2: UI/UX + Performance + Human Quality

### Task 1 — Terminal (167 → 482 lines)
- Premium dark theme (`TermBg #090C18`, `TermPrompt CosmicAccent violet`)
- Live session status indicator dot (green when running)
- Command history panel — tap to re-use, shows last 20 commands with chevron hint
- Inline search with match highlighting and match count badge
- Up/Down arrow navigation through history (both hardware keys and on-screen buttons)
- Copy entire output to clipboard
- Syntax colouring: user input in cyan, errors in red, warnings in amber, output in off-white
- Search match highlighting with CosmicAccent background
- Hardware keyboard support: Enter to send, ↑/↓ to navigate history
- `BasicTextField` with custom cursor brush for true terminal feel
- Auto-scroll to latest line (skipped during active search)
- `commandHistory` exposed as `StateFlow<List<String>>` from `TerminalRuntime`

### Task 2 — Sandbox (303 → 405 lines)
- Matching dark terminal aesthetic (`SandboxTermBg #090C18`)
- Tab bar with per-session status dots (green=active, grey=idle)
- Session age display in header strip
- Polling log refresh (250 ms interval) for live output without Flow rewiring
- Cancel execution button during running state
- Restart session action in top bar
- Empty state with brand-consistent orb icon
- `BasicTextField` command input matching terminal style

### Task 3 — Secret Manager (187 → 336 lines)
- Info banner with lock icon explaining secure storage
- Per-provider card: icon, masked preview (`••••••••xxxx`), configured/not-configured badge
- Separate Edit and Delete actions with confirmation dialog for delete
- Copy key to clipboard action
- Snackbar feedback for save/delete/copy operations
- Delete confirmation dialog with SemanticError styling
- Edit dialog: show/hide toggle, proper password keyboard type

### Task 4 — Profile (206 → 366 lines)
- AIRI letterform orb avatar with initials fallback (first 2 chars of display name)
- Edit indicator overlay on avatar
- Account information section: sign-in provider (mapped to human name), member-since date
- Session section: Sign Out and Delete Account actions
- Delete account confirmation dialog (explains Firebase vs local data)
- `updateProfile()` writes to both `SharedPreferences` and Firebase user profile
- Snackbar confirmation on save
- All strings use `stringResource`

### Task 5 — Memory (195 → 306 lines)
- Inline search bar with animated show/hide (no search icon in top bar when not active)
- Animated empty state: pulsing radial halo around Psychology icon
- "No matches" state for empty search results
- Summary bar showing episodic memory count with CosmicAccent chip
- Message cards: role avatar (U/A), timestamp, 4-line preview with ellipsis
- Clear confirmation dialog with delete icon and SemanticError action button

### Task 6 — Localization
- **Fixed** `VoiceLiveOverlay.kt`: replaced hardcoded Arabic strings (`"جارٍ الاستماع…"`, `"AIRI يتحدث"`, `"جارٍ المعالجة…"`) with `stringResource(R.string.voice_listening_label)` etc.
- **Added** 60+ new string resources covering Terminal, Sandbox, SecretManager, Profile, Memory, About, Voice
- **Fixed** `VoiceSettingsScreen.kt`: Porcupine, Access Key, Vosk, Cloud Realtime strings
- **Renamed** "AI Library" → "AI Settings" in `strings.xml`, `values-es/strings.xml`, `values-ar/strings.xml`, `values-zh/strings.xml`
- **Fixed** `SecretManagerScreen.kt`: hardcoded "Secret Manager", "API Key", "Save", "Cancel" → `stringResource`

### Task 7 — Remove AI Design Fingerprints
- Replaced `Icons.Outlined.SmartToy` (generic robot) with:
  - `Icons.Outlined.AutoAwesome` in bottom nav Chat tab
  - `Icons.Outlined.Memory` in model picker and model list items
  - `Icons.Outlined.Psychology` in onboarding step
- `AboutScreen`: replaced robot icon with AIRI letterform "A" orb
- `WelcomeScreen`: replaced plain text `AIRI` with letterform orb (radial gradient, border)
- Removed AI-generated comment patterns (`// Task X:`, `// Phase N:`, `// [T33]:`) from 5 files
- Removed `"Task 3: Route through AuthService"` comment from `ProfileScreen`

### Task 8 — Navigation Smoothness
- Added `NavHost` transitions: `fadeIn + slideInHorizontally(it/12)` on enter, `fadeOut` on exit, reverse on pop
- All transitions use `FastOutSlowInEasing` at 180–220 ms — sub-frame latency on 60 Hz
- No recomposition changes needed: existing `launchSingleTop = true` + `restoreState = true` preserved

### Task 9 — Main Screen
- Removed centred plain-text "AIRI" from `WelcomeScreen`
- Replaced with letterform orb (same visual language as `AboutScreen`)
- `ChatScreen` empty state: no "AIRI" heading text — uses animated orb + subtitle only

### Task 10 — About Screen (92 → 225 lines)
- Replaced robot icon with AIRI "A" letterform orb (22dp radius, radial gradient, accent border)
- Version + build number in dedicated chip row
- Info cards redesigned: icon in 36dp accent-tinted box + title + body
- Navigation links: Technical Details, Licenses, Privacy
- `copyright_notice` string used for footer

### Task 11 — Security
- Gated `ChatViewModel` performance `Log.d` behind `BuildConfig.DEBUG`
- Verified all `Log.d` calls that reference model params are already DEBUG-gated
- `FirebaseAuth.getInstance()` calls: none remain in UI layer (routed through `AuthService`)
- `exported="true"` activities: only `MainActivity` (launcher) + `AiriAccessibilityService` (system-permission-protected) — both correct
- `SecureApiKeyStore` used for all API key storage — no plaintext preferences

### Task 12 — Human Code Quality
- Renamed `historyBuffer` (private `ArrayDeque`) in `TerminalRuntime` to avoid shadowing the new public `commandHistory: StateFlow`
- Added `_commandHistoryFlow: MutableStateFlow<List<String>>` with public `commandHistory` accessor
- Removed generated comment scaffolding from 5 files
- `DesignSystem.kt` tokens used consistently (no ad-hoc `RoundedCornerShape(20.dp)` in new code — always `AIRIShapes.pill`)
- All new screens follow: `Scaffold → LazyColumn/Column → Surface card` pattern
- No `TODOs`, no placeholder data, no `// TODO` blocks in new code

---

## Color System Improvements
- Added `CosmicAccentAlt` (#8B78F0) for gradient endpoints
- Added `SurfaceBase`, `SurfaceRaised`, `SurfaceFloating`, `SurfaceHighlight`, `SurfaceCard` ladder
- Added `GlassWhite`, `GlassWhiteBorder` glass tokens
- Added `NavIconInactive`, `NavIconActive` navigation tokens
- Added `LightBackground` / `LightSurface` / `LightOnBackground` for light theme
- All tokens documented with hue, usage, and relationship comments
