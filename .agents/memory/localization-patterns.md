---
name: Localization Patterns
description: How localization is structured in AIRI; root cause of mixed-language UI; key patterns for coroutines and non-composable lambdas.
---

## Root cause of mixed-language UI
SettingsScreen.kt and MarketplaceScreen.kt had labels hardcoded as raw Kotlin string literals instead of `stringResource()`. Some were hardcoded in Arabic (worked in AR, broke in EN/ZH/ES), others hardcoded in English (always showed English regardless of locale).

## String resource files
- `values/strings.xml` — English (default)
- `values-ar/strings.xml` — Arabic
- `values-zh/strings.xml` — Chinese
- `values-es/strings.xml` — Spanish

**Why:** All 4 files must have every key or aapt2 strict-defaults check will fail at build time.

## Coroutine / non-composable lambda pattern
`snackMessage = "..."` inside `scope.launch {}` cannot use `stringResource()` (not composable). Fix: capture `val context = LocalContext.current` at the top of the @Composable, then use `context.getString(R.string.xxx, arg)` inside the coroutine.

## Tab labels with dynamic count
`listOf("Explore", "Installed (${installed.size})", "Publish").forEachIndexed` — the lambda is not composable, so `stringResource()` cannot be called inside it. Fix: pre-compute labels before the `TabRow`:
```kotlin
val tabLabels = listOf(
    stringResource(R.string.marketplace_tab_explore),
    stringResource(R.string.marketplace_tab_installed, installed.size),
    stringResource(R.string.marketplace_tab_publish)
)
```
Then `tabLabels.forEachIndexed { i, t -> ... }`.

## Dynamic trailing in SettingsScreen language row
`trailing = "العربية"` was hardcoded. Replace with:
```kotlin
trailing = LanguageManager.getLanguageOption(LanguageManager.getCurrentLanguage(context)).displayName
```
This shows the flag + native name of the currently selected language.

## Key string name prefixes added
- `settings_*` — all SettingsScreen navigation labels (25 keys)
- `settings_general` — GeneralSettingsScreen top bar title
- `marketplace_*` — all MarketplaceScreen strings (22 keys)
