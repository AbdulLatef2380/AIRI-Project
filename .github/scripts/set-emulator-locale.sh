#!/usr/bin/env bash
set -euo pipefail

readonly requested_locale="${1:?usage: set-emulator-locale.sh <locale>}"
readonly trace_dir="${ADB_LOCALE_TRACE_DIR:-}"
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
if [[ -n "$trace_dir" ]]; then
  mkdir -p "$trace_dir"
  locale_manager_log="$trace_dir/locale-manager-command.log"
  settings_log="$trace_dir/locale-settings-command.log"
  if adb shell cmd locale set --user 0 "$requested_locale" >"$locale_manager_log" 2>&1; then
    echo "Configured emulator locale with LocaleManager: $requested_locale"
    cat "$locale_manager_log"
  else
    locale_manager_status=$?
    echo "LocaleManager locale command failed with exit $locale_manager_status; output follows:"
    cat "$locale_manager_log"
    if adb shell settings put system system_locales "$requested_locale" >"$settings_log" 2>&1; then
      echo "Configured emulator locale with Settings compatibility route: $requested_locale"
      cat "$settings_log"
    else
      settings_status=$?
      echo "Settings locale command failed with exit $settings_status; output follows:" >&2
      cat "$settings_log" >&2
      adb shell cmd locale help >"$trace_dir/locale-manager-help.txt" 2>&1 || true
      adb shell settings get system system_locales >"$trace_dir/locale-settings-current.txt" 2>&1 || true
      cat "$trace_dir/locale-manager-help.txt" >&2 || true
      cat "$trace_dir/locale-settings-current.txt" >&2 || true
      exit 1
    fi
  fi
  adb shell am broadcast -a android.intent.action.LOCALE_CHANGED >"$trace_dir/locale-changed-broadcast.log" 2>&1 || true
  echo "Locale change broadcast output:"
  cat "$trace_dir/locale-changed-broadcast.log" || true
else
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
fi
