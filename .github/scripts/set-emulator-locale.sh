#!/usr/bin/env bash
set -euo pipefail

readonly requested_locale="${1:?usage: set-emulator-locale.sh <locale>}"
case "$requested_locale" in
  en-US|ar-SA) ;;
  *)
    echo "Unsupported CI locale: $requested_locale" >&2
    exit 2
    ;;
esac

# LocaleManager's shell command is the supported route on newer Android
# releases. API 29 predates that command, so use the shell-owned Settings
# provider as the compatibility route rather than the restricted setprop
# fallback. Both commands operate on the test user only.
if adb shell cmd locale set --user 0 "$requested_locale" >/dev/null 2>&1; then
  echo "Configured emulator locale with LocaleManager: $requested_locale"
elif adb shell settings put system system_locales "$requested_locale" >/dev/null 2>&1; then
  echo "Configured emulator locale with Settings compatibility route: $requested_locale"
else
  echo "Unable to configure emulator locale: $requested_locale" >&2
  adb shell cmd locale help >&2 || true
  adb shell settings get system system_locales >&2 || true
  exit 1
fi

adb shell am broadcast -a android.intent.action.LOCALE_CHANGED >/dev/null 2>&1 || true
