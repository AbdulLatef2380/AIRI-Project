#!/usr/bin/env bash
set -Eeuo pipefail

readonly TRACE_DIR="${ADB_TRACE_DIR:-ci-instrumentation-adb-trace}"
readonly EXPECTED_API_LEVEL="${EXPECTED_API_LEVEL:-34}"
readonly EXPECTED_LOCALE="${EXPECTED_LOCALE:-en-US}"
readonly TEST_TIMEOUT_SECONDS="${TEST_TIMEOUT_SECONDS:-900}"
mkdir -p "$TRACE_DIR"

LOGCAT_PID=""
MONITOR_PID=""
exec > >(tee -a "$TRACE_DIR/trace-runner.log") 2>&1

timestamp() {
  date -u '+%Y-%m-%dT%H:%M:%SZ'
}

capture_snapshot() {
  local label="$1"
  local output="$TRACE_DIR/snapshot-${label}.txt"
  {
    echo "===== Snapshot: $label ====="
    echo "captured_at=$(timestamp)"
    echo "===== adb version ====="
    timeout 15s adb version || true
    echo "===== adb devices -l ====="
    timeout 15s adb devices -l || true
    echo "===== adb get-state ====="
    timeout 15s adb get-state || true
    echo "===== emulator properties ====="
    timeout 15s adb shell getprop ro.build.version.sdk || true
    timeout 15s adb shell getprop sys.boot_completed || true
    timeout 15s adb shell getprop persist.sys.locale || true
    timeout 15s adb shell getprop persist.sys.locales || true
    echo "===== LocaleManager current locale ====="
    timeout 15s adb shell cmd locale get --user 0 || true
    echo "===== Settings system_locales ====="
    timeout 15s adb shell settings get system system_locales || true
    echo "===== /proc/meminfo ====="
    timeout 15s adb shell cat /proc/meminfo || true
    echo "===== process list ====="
    timeout 20s adb shell ps -A || true
    echo "===== activity processes ====="
    timeout 20s adb shell dumpsys activity processes || true
    echo "===== active instrumentation ====="
    timeout 20s adb shell dumpsys activity instrumentation || true
    echo "===== AIRI package ====="
    timeout 20s adb shell dumpsys package com.airi.assistant || true
    echo "===== AIRI test package ====="
    timeout 20s adb shell dumpsys package com.airi.assistant.test || true
  } > "$output" 2>&1
  echo "Saved $output"
}

extract_first_locale() {
  printf '%s' "$1" \
    | tr -d '\r' \
    | grep -Eo '[A-Za-z]{2,3}[-_][A-Za-z0-9]{2,8}' \
    | head -n 1 \
    | tr '_' '-' || true
}

assert_expected_locale() {
  local locale_manager_output settings_output property_output
  local manager_locale settings_locale properties_locale effective_locale effective_source
  locale_manager_output="$(timeout 15s adb shell cmd locale get --user 0 2>&1 || true)"
  settings_output="$(timeout 15s adb shell settings get system system_locales 2>&1 || true)"
  property_output="$(timeout 15s adb shell getprop persist.sys.locale 2>&1 || true)
$(timeout 15s adb shell getprop persist.sys.locales 2>&1 || true)"
  manager_locale="$(extract_first_locale "$locale_manager_output")"
  settings_locale="$(extract_first_locale "$settings_output")"
  properties_locale="$(extract_first_locale "$property_output")"

  if [[ -n "$manager_locale" ]]; then
    effective_locale="$manager_locale"
    effective_source='LocaleManager'
  elif [[ -n "$settings_locale" ]]; then
    effective_locale="$settings_locale"
    effective_source='Settings system_locales'
  else
    effective_locale="$properties_locale"
    effective_source='persist.sys.locale(s)'
  fi

  {
    printf 'expected_locale=%s\n' "$EXPECTED_LOCALE"
    printf 'effective_locale=%s\n' "$effective_locale"
    printf 'effective_source=%s\n' "$effective_source"
    printf 'LocaleManager output:\n%s\n' "$locale_manager_output"
    printf 'Settings system_locales output:\n%s\n' "$settings_output"
    printf 'locale properties:\n%s\n' "$property_output"
  } > "$TRACE_DIR/locale-verification.txt"

  if [[ "$effective_locale" != "$EXPECTED_LOCALE" ]]; then
    echo "Expected effective locale '$EXPECTED_LOCALE', got '$effective_locale' from $effective_source; see $TRACE_DIR/locale-verification.txt" >&2
    return 1
  fi
  echo "Verified effective emulator locale: $effective_locale (source: $effective_source)"
}

monitor_adb() {
  while true; do
    echo "===== ADB monitor $(timestamp) ====="
    ADB_TRACE=all timeout 12s adb devices -l || true
    timeout 12s adb get-state || true
    timeout 15s adb shell 'echo "sdk=$(getprop ro.build.version.sdk) boot=$(getprop sys.boot_completed) locale=$(getprop persist.sys.locale) locales=$(getprop persist.sys.locales)"; head -n 5 /proc/meminfo; ps -A' || true
    sleep 25
  done
}

finish() {
  local exit_code=$?
  trap - EXIT INT TERM
  set +e
  echo "Instrumentation trace exiting with status $exit_code at $(timestamp)"
  capture_snapshot final
  timeout 30s adb logcat -b all -d -v threadtime -t 10000 > "$TRACE_DIR/logcat-final.txt" 2>&1 || true
  timeout 20s adb logcat -b crash -d -v threadtime > "$TRACE_DIR/logcat-crash-buffer.txt" 2>&1 || true
  timeout 20s adb shell dumpsys activity instrumentation > "$TRACE_DIR/dumpsys-instrumentation-final.txt" 2>&1 || true
  timeout 20s adb shell dumpsys dropbox --print data_app_crash > "$TRACE_DIR/dropbox-data-app-crash.txt" 2>&1 || true
  timeout 20s adb shell dumpsys dropbox --print data_app_native_crash > "$TRACE_DIR/dropbox-data-app-native-crash.txt" 2>&1 || true
  timeout 20s adb shell dumpsys dropbox --print system_server_crash > "$TRACE_DIR/dropbox-system-server-crash.txt" 2>&1 || true
  ADB_TRACE=all timeout 20s adb devices -l > "$TRACE_DIR/adb-client-final-trace.txt" 2>&1 || true
  if [[ "$exit_code" -ne 0 && "${COLLECT_BUGREPORT_ON_FAILURE:-false}" == "true" ]]; then
    echo "Collecting the explicitly requested, time-bounded emulator bugreport before teardown."
    timeout --signal=TERM --kill-after=10s 120s adb bugreport "$TRACE_DIR/bugreport.zip" > "$TRACE_DIR/bugreport-command.log" 2>&1 || true
  fi
  if [[ -n "$MONITOR_PID" ]]; then
    kill "$MONITOR_PID" 2>/dev/null || true
    wait "$MONITOR_PID" 2>/dev/null || true
  fi
  if [[ -n "$LOGCAT_PID" ]]; then
    kill "$LOGCAT_PID" 2>/dev/null || true
    wait "$LOGCAT_PID" 2>/dev/null || true
  fi
  echo "Trace artifacts written under $TRACE_DIR"
  exit "$exit_code"
}

trap finish EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

printf 'Starting focused Android instrumentation diagnostics at %s\n' "$(timestamp)"
printf 'Expected API=%s locale=%s test_timeout=%ss\n' "$EXPECTED_API_LEVEL" "$EXPECTED_LOCALE" "$TEST_TIMEOUT_SECONDS"

capture_snapshot before-locale
adb logcat -b all -v threadtime > "$TRACE_DIR/logcat-live.txt" 2>&1 &
LOGCAT_PID=$!
monitor_adb > "$TRACE_DIR/adb-monitor.log" 2>&1 &
MONITOR_PID=$!

ADB_LOCALE_TRACE_DIR="$TRACE_DIR" bash .github/scripts/set-emulator-locale.sh "$EXPECTED_LOCALE"
capture_snapshot after-locale
assert_expected_locale

actual_api="$(timeout 15s adb shell getprop ro.build.version.sdk | tr -d '\r')"
if [[ "$actual_api" != "$EXPECTED_API_LEVEL" ]]; then
  echo "Expected Android API $EXPECTED_API_LEVEL but emulator reports '$actual_api'." >&2
  exit 2
fi

set +e
timeout --verbose --signal=TERM --kill-after=30s "${TEST_TIMEOUT_SECONDS}s" \
  ./gradlew --no-daemon --max-workers=1 \
    '-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=384m -XX:+UseSerialGC' \
    '-Dkotlin.daemon.jvmargs=-Xmx1536m' \
    :app:connectedDebugAndroidTest --console=plain --info --stacktrace \
    2>&1 | tee "$TRACE_DIR/gradle-instrumentation.log"
pipeline_status=("${PIPESTATUS[@]}")
set -e

test_exit_code="${pipeline_status[0]}"
printf 'connectedDebugAndroidTest exit code=%s; tee exit code=%s\n' "$test_exit_code" "${pipeline_status[1]:-unknown}"
if [[ "$test_exit_code" -eq 124 || "$test_exit_code" -eq 137 ]]; then
  echo "Instrumentation command reached its ${TEST_TIMEOUT_SECONDS}s timeout." >&2
fi
exit "$test_exit_code"
