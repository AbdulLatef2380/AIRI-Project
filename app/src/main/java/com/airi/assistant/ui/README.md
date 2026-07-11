# ui — Jetpack Compose UI Layer

All screens, shared composables, ViewModels, theme, and navigation.

## Navigation

`AiriApp.kt` owns all navigation using `NavHost`. Routes are defined as string constants in `AiriRoute`. All 7 new routes from Phase 2 are registered:
- `WELCOME` — onboarding (when no model/key configured)
- `PLANNING_DASHBOARD` — active plan visualization
- `PROTOTYPE_BUILDER` — workspace in prototype mode
- `WIREFRAME_BUILDER` — workspace in wireframe mode
- `GIT_REPOSITORY` — GitHub repository browser
- `SECURITY_SCANNER` — API key health scanner
- `SECRET_MANAGER` — encrypted key management

## Theme

`AIRITheme` wraps `MaterialTheme` with four color schemes:
- **Light**: White surfaces, purple accent
- **Dark**: Dark-gray surfaces, purple accent
- **AMOLED**: Pure-black surfaces, purple accent
- **System**: Follows `isSystemInDarkTheme()`

All screens use `MaterialTheme.colorScheme.*` — no hardcoded hex colors remain in production UI code (exceptions: semantic status colors, terminal always-dark, code preview always-dark).

`AiriTheme` object provides shorthand properties (`AiriTheme.onBackground`, etc.) that delegate to `MaterialTheme.colorScheme`.

## Screens (50+)

Key screens:

| Screen | Purpose |
|--------|---------|
| `ChatScreen` | Primary conversation UI |
| `SettingsScreen` | Settings root with nav to all sub-settings |
| `VoiceSettingsScreen` | Voice provider selection + Vosk model management |
| `SecretManagerScreen` | API key CRUD via SecureApiKeyStore |
| `SecurityScannerScreen` | API key health scan via SecretHealthChecker |
| `PlanningDashboardScreen` | Live agent plan visualization |
| `ObservabilityScreen` | Events / Live Hub / Graph / Traces / Network tabs |
| `GitRepositoryScreen` | Browse repos, branches, commits; create PRs |
| `WorkspaceScreen` | Artifact/code workspace |
| `SkillCreationWizardScreen` | 4-step skill builder |
| `WelcomeScreen` | First-run onboarding |

## ViewModels

- `ChatViewModel` — primary ViewModel; binds to `LiveVoiceService`; drives all chat state
- `AgentPlanViewModel` — exposes agent plan steps for `PlanningDashboardScreen`

## Localization

All user-visible strings use `stringResource(R.string.*)`. The `values/strings.xml` (English) and `values-ar/strings.xml` (Arabic) are kept in sync. RTL layout is automatic via Android's built-in RTL support (no custom layout mirrors needed).

## Status

- All screens: **Functional**
- Theme switching: **Instant, no restart**
- Localization: **Complete** (English + Arabic)
- No emoji in production UI
- No hardcoded strings
