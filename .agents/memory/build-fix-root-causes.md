---
name: Build Fix Root Causes
description: Durable rules from the GitHub Actions compilation fix session — enum gotchas, scope rules, import patterns, SQLCipher classpath.
---

## Key rules

### ConnectorType enum has no WEBHOOK value
`ConnectorType` is `API | APP | LOCAL | MCP | SYSTEM`. Webhooks belong under `API` per the enum's own comment. Any connector using webhook transport must declare `type = ConnectorType.API`.

**Why:** The WEBHOOK value was never added; N8nConnector assumed it existed.

**How to apply:** Before adding a new connector, check `Connector.kt` for the current enum members.

---

### ConnectorAuthManager credential deletion method
`ConnectorAuthManager` stores credentials under the key scheme `"cred_$credKey"`. The deletion method is `clearCredential(connectorId, credKey)` — added in this session. It mirrors `storeCredential` exactly: `prefs.edit().remove(key(connectorId, "cred_$credKey")).apply()`.

**Why:** The method was called by N8nConnector.disconnect() but never defined.

---

### SQLCipher requires explicit imports — FQN alone is unreliable
`net.zetetic.database.sqlcipher.*` classes must be imported explicitly in any file that also imports `androidx.sqlite` classes. Using FQN inline (`net.zetetic.database.sqlcipher.SupportFactory(...)`) silently fails resolution in some Kotlin/KAPT configurations due to classpath shadowing between SQLCipher 4.5.4 and `androidx.sqlite:sqlite-ktx:2.4.0`.

**Why:** Encountered during GitHub Actions CI fail; not reproducible in IDE but reproducible on clean Gradle builds.

**How to apply:** Always add `import net.zetetic.database.sqlcipher.SupportFactory` (or alias the class) in any file using SQLCipher.

---

### Agent Plan ModalBottomSheet must live in ChatScreen, not AiriHistoryPanel
The five state variables it needs (`isPlanModeActive`, `agentPlanViewModel`, `isPanelVisible`, `showPanel`, `planSheetState`) are all defined in `ChatScreen`'s body (lines ~219–230). `AiriHistoryPanel` does not receive these as parameters. Placing the ModalBottomSheet inside any sub-composable that lacks these parameters causes 5 unresolved reference errors.

**Why:** AP-C03/C04 implementation placed the overlay in the wrong composable scope.

**How to apply:** Full-screen overlays (ModalBottomSheet, Dialog) should always live in the composable where their state is defined, not in child composable functions.

---

### AdvancedChatInputBar wrapper must hoist ALL AiriChatInputBar parameters
`AdvancedChatInputBar` in `AdvancedInputBar.kt` wraps `AiriChatInputBar`. Every parameter added to `AiriChatInputBar` must also be added to `AdvancedChatInputBar` (with a default) and threaded through. The wrapper is the public API; callers use `AdvancedChatInputBar`, not `AiriChatInputBar`.

**Why:** AP-C09 added `onStageFile` to `AiriChatInputBar` without hoisting it.

---

### Artifact preview reads full content from filePath, not previewSnippet
`ArtifactManager.Artifact.previewSnippet` is intentionally truncated (~512 chars). `ArtifactPreviewScreen` renders whatever string it receives. The correct approach is to read the full file from `artifact.filePath` on the IO dispatcher, with fallback to `previewSnippet.orEmpty()` if the file is unreadable.

**Why:** `selected.content` was referenced but never existed on `Artifact`; `previewSnippet` was a workaround that would have shown truncated artifacts.

**How to apply:** `scope.launch(Dispatchers.IO) { val content = File(artifact.filePath).readText(); withContext(Main) { navigate(..., content) } }`
