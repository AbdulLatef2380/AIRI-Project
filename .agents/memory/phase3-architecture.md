---
name: Phase 3 Architecture Decisions
description: Phase 3 feature map, navigation wiring pattern, VoicePreferencesStore design, AdvancedChatInputBar wrapper strategy.
---

## Phase 3 features delivered

Five features: Voice Personalization, Permissions Screen, Advanced Input Bar, Credits Screen, Update System.

## VoicePreferencesStore pattern

- Singleton `object` in `com.airi.assistant.voice` package.
- Reads/writes `airi_voice_prefs` SharedPreferences.
- Exposes `apply(context, tts: TextToSpeech)` — called from `IncrementalTtsEngine.init()` right after `SUCCESS`.
- `IncrementalTtsEngine` is in same package so no import needed (but redundant import is harmless).

**Why:** Decouples TTS settings from UserPreferences (which has no Room column for TTS pitch) without touching the persistence layer.

## AdvancedChatInputBar wrapper strategy

- `AdvancedChatInputBar` is in `ui/screens/AdvancedInputBar.kt` — same package as `ChatScreen.kt`.
- Wraps `AiriChatInputBar` (2500-line ChatScreen function) rather than replacing it — avoids rewriting 559 lines.
- Adds an `InputActionToolbar` Row above the existing bar: Plan Mode, Tool Picker, Skill Picker, Quick chips.
- ChatScreen.kt change: one-word rename `AiriChatInputBar(` → `AdvancedChatInputBar(`. All new params default to no-ops.

**Why:** The existing AiriChatInputBar is correct and complex. Wrapping it preserves all existing behavior while adding the toolbar.

## Navigation wiring pattern (AiriApp.kt)

New routes are added as constants in `AiriRoute` object, then registered as `composable(AiriRoute.FOO) { ... }` inside the `NavHost` block just before the closing brace. All new screens only need `onBack = { navController.popBackStack() }`.

VoiceSettingsScreen gained `onNavigateToPersonalization: () -> Unit = {}` — default empty lambda keeps all existing call sites compiling.

## ServiceLocator tokenAccountant

`TokenAccountant` was not in ServiceLocator. Added as a lazy property alongside `creditMeteringEngine` using fully-qualified type to avoid adding an import line. CreditsScreen accesses it via `ServiceLocator.tokenAccountant`.

## CreditsScreen

- Uses `CreditMeteringEngine.snapshot()` for credit data (synchronized, safe to call from Compose).
- Uses `TokenAccountant.stats: StateFlow<Map<CloudProvider, ProviderStats>>` for token data via `collectAsState()`.
- `TokenAccountant.ProviderStats.estimatedCostUsd(provider: CloudProvider)` is real — verified in source.

## PermissionsScreen

- Uses `ContextCompat.checkSelfPermission` directly (no Accompanist dependency assumed).
- "Open App Settings" shortcut: `Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))`.
- `OutlinedButton.border` uses `BorderStroke(1.dp, SolidColor(...))` — NOT `ButtonDefaults.outlinedButtonBorder.copy(brush=...)` which doesn't compile (BorderStroke is not a data class).
