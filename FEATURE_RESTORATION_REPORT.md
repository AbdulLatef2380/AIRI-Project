# AIRI — Feature Restoration Report

All features restored from `AIRI-Project-main` (previously) into `AIRI-Project-architecture-refactor` (right-now).

---

## ChatScreen Features

| Feature | Source File | Lines in Previously | Status |
|---|---|---|---|
| `AiriChatTopBar` | `ChatScreen.kt` | 80–162 | ✅ Restored + improved |
| Model pill with status dot | `ChatScreen.kt` | 110–150 | ✅ Restored + improved |
| Credits/points badge | `ChatScreen.kt` | 80–110 | ✅ Restored + improved |
| History button (clock icon) | `ChatScreen.kt` | 155–162 | ✅ Restored |
| Overflow menu | `ChatScreen.kt` | 163–230 | ✅ Restored |
| `AiriHistoryPanel` | `ChatScreen.kt` | 230–400 | ✅ Restored + improved header |
| `AiriModelPickerSheet` | `ChatScreen.kt` | 400–520 | ✅ Restored |
| `ChatMessageList` | `ChatScreen.kt` | 520–680 | ✅ Restored |
| `UserBubble` (full) | `ChatScreen.kt` | 680–850 | ✅ Restored |
| `AiBubble` (full) | `ChatScreen.kt` | 850–1100 | ✅ Restored |
| `AiStreamingBubble` | `ChatScreen.kt` | 1100–1200 | ✅ Restored |
| `AiriChatInputBar` | `ChatScreen.kt` | 1200–1900 | ✅ Restored |
| Attachment button (+) | `ChatScreen.kt` | 1200–1320 | ✅ Restored |
| Image picker launcher | `ChatScreen.kt` | ~250–290 | ✅ Restored |
| Camera launcher | `ChatScreen.kt` | ~290–320 | ✅ Restored |
| File picker launcher | `ChatScreen.kt` | ~320–355 | ✅ Restored |
| Voice recording button | `ChatScreen.kt` | 1620–1680 | ✅ Restored |
| Voice waveform bars | `ChatScreen.kt` | 1750–1800 | ✅ Restored |
| Live Chat / live voice mode | `ChatScreen.kt` | 1680–1730 | ✅ Restored |
| TTS/speak next response | `ChatScreen.kt` | ~480–510 | ✅ Restored |
| Smart reply chips | `ChatScreen.kt` | 1730–1760 | ✅ Restored |
| Plan mode toggle chip | `AdvancedInputBar.kt` | all | ✅ Restored |
| `GenerationSettingsDialog` | `ChatScreen.kt` | 1950–2100 | ✅ Restored |
| `AgentPlanContent` panel | `ChatScreen.kt` | 2100–2400 | ✅ Restored |
| Offline banner | `ChatScreen.kt` | ~430–460 | ✅ Restored |
| System integrity banner | `ChatScreen.kt` | ~460–480 | ✅ Restored |
| Context reset banner | `ChatScreen.kt` | ~480–490 | ✅ Restored |
| `ScrollToBottomFab` | `ChatScreen.kt` | 2550–2600 | ✅ Restored + improved |
| Confirmation dialog (agent) | `ChatScreen.kt` | 2600–2650 | ✅ Restored |
| Attachment preview strip | `ChatScreen.kt` | 1350–1450 | ✅ Restored |

---

## AdvancedInputBar

| Feature | Previous Implementation | Right-Now Bug | Fix Applied |
|---|---|---|---|
| Calls `AiriChatInputBar` | Yes — correct | Called `ChatInputBar` (non-existent stub) | Fixed: now calls `AiriChatInputBar` with all 18 params |
| Plan mode active indicator | Yes | Hardcoded `MaterialTheme.colorScheme` | Fixed: uses `DividerColor`, `CosmicAccent` tokens |
| Tool chip colour animation | Yes | Hardcoded `MaterialTheme.colorScheme` | Fixed: uses tokens |

---

## Source Attribution

Every restored feature came exclusively from `AIRI-Project-main/app/src/main/java/com/airi/assistant/ui/screens/ChatScreen.kt` (the "previously" project). No code from `AIRI-Project-architecture-refactor` was discarded — the right-now project's non-UI improvements (architectural fixes, import cleanup) were merged in.

The right-now project's specific fixes that were preserved:
- Removed unused `import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK`
- Switched from individual theme imports to `import com.airi.assistant.ui.theme.*`
- `contentWindowInsets = WindowInsets.statusBars` (correct for edge-to-edge layout)
