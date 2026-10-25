#!/usr/bin/env bash
set -Eeuo pipefail

readonly requested_locale="${1:?usage: run-instrumentation-traced.sh <locale>}"
readonly trace_dir="${INSTRUMENTATION_TRACE_DIR:-ci-instrumentation-runtime}"
mkdir -p "$trace_dir"
logcat_pid=""

capture_snapshot() {
  local label="$1"
  local output="$trace_dir/snapshot-${label}.txt"
  {
    echo "captured_at=$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
    echo "requested_locale=$requested_locale"
    echo '===== adb devices -l ====='
    timeout 15s adb devices -l || true
    echo '===== build properties ====='
    timeout 15s adb shell getprop ro.build.version.sdk || true
    timeout 15s adb shell getprop sys.boot_completed || true
    timeout 15s adb shell getprop ro.build.fingerprint || true
    echo '===== locale state ====='
    timeout 15s adb shell cmd locale get --user 0 || true
    timeout 15s adb shell settings get system system_locales || true
    timeout 15s adb shell getprop persist.sys.locale || true
    timeout 15s adb shell getprop persist.sys.locales || true
    echo '===== memory ====='
    timeout 15s adb shell cat /proc/meminfo || true
    echo '===== processes ====='
    timeout 20s adb shell ps -A || true
    echo '===== activity processes ====='
    timeout 25s adb shell dumpsys activity processes || true
    echo '===== memory summary ====='
    timeout 25s adb shell dumpsys meminfo || true
    echo '===== instrumentation ====='
    timeout 20s adb shell dumpsys activity instrumentation || true
    echo '===== packages ====='
    timeout 20s adb shell cmd package list packages || true
  } > "$output" 2>&1
  echo "Saved $output"
}

finish() {
  local exit_code=$?
  trap - EXIT INT TERM
  set +e
  echo "Traced instrumentation exiting with status $exit_code at $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  capture_snapshot final
  timeout 30s adb logcat -b all -d -v threadtime -t 20000 > "$trace_dir/logcat-final.txt" 2>&1 || true
  timeout 20s adb logcat -b crash -d -v threadtime > "$trace_dir/logcat-crash-buffer.txt" 2>&1 || true
  timeout 25s adb shell dumpsys dropbox --print data_app_crash > "$trace_dir/dropbox-data-app-crash.txt" 2>&1 || true
  timeout 25s adb shell dumpsys dropbox --print data_app_native_crash > "$trace_dir/dropbox-data-app-native-crash.txt" 2>&1 || true
  timeout 25s adb shell dumpsys dropbox --print system_server_crash > "$trace_dir/dropbox-system-server-crash.txt" 2>&1 || true
  if [[ -n "$logcat_pid" ]]; then
    kill "$logcat_pid" 2>/dev/null || true
    wait "$logcat_pid" 2>/dev/null || true
  fi
  echo "Trace evidence saved in $trace_dir"
  exit "$exit_code"
}

trap finish EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

printf 'Starting diagnostic capture for API=%s locale=%s at %s\n' \
  "${EXPECTED_API_LEVEL:-unknown}" "$requested_locale" "$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
capture_snapshot before-locale
adb logcat -b all -v threadtime > "$trace_dir/logcat-live.txt" 2>&1 &
logcat_pid=$!

bash .github/scripts/set-emulator-locale.sh "$requested_locale"
capture_snapshot after-locale
bash .github/scripts/run-instrumentation.sh
