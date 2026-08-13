# UI package

This package owns AIRI's Compose screens, navigation, theming, localization resources, and interaction state.

## Chat input

The active composer supports `/` for enabled, connected skills and `@` for current-session saved knowledge. A selection is represented internally as a directive, revalidated by the ViewModel, and removed from the visible user prompt before execution. The stop control cancels the current generation owner rather than merely changing the icon state.

## RTL and localization

New input text uses logical `TextAlign.Start`, allowing Android layout direction to control Arabic and left-to-right presentation. The shortcut knowledge label is present in English and Arabic resources. Other locales fall back to the default resource until translated.

## Limits

The project contains historical hard-coded text and styling outside the focused paths. Screen-level visual, accessibility, dark-mode, and RTL validation must be performed on devices and with Compose tests before release.
