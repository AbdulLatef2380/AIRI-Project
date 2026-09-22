#!/usr/bin/env bash
set -euo pipefail

readonly GRADLE_ARGS=(
  --no-daemon
  --max-workers=1
  '-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=384m -XX:+UseSerialGC'
  :app:connectedDebugAndroidTest
  --stacktrace
)
readonly TEST_TIMEOUT_SECONDS=900

dump_diagnostics() {
  echo "===== instrumentation diagnostics =====" >&2
  adb devices -l >&2 || true
  adb shell getprop >&2 || true
  adb shell ps -A >&2 || true
  adb logcat -d -t 400 >&2 || true
}

wait_for_package_manager() {
  local attempt
  for attempt in $(seq 1 60); do
    if adb shell cmd package list packages >/dev/null 2>&1; then
      return 0
    fi
    sleep 3
  done
  echo "Android Package Manager did not become ready within 180 seconds." >&2
  return 1
}

run_tests() {
  timeout --signal=TERM --kill-after=30s "${TEST_TIMEOUT_SECONDS}s" \
    ./gradlew "${GRADLE_ARGS[@]}"
}

./gradlew --stop || true
adb wait-for-device
test "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1"
test "$(adb shell getprop ro.build.version.sdk | tr -d '\r')" = "29"
wait_for_package_manager

if run_tests; then
  exit 0
fi

echo "Instrumentation attempt failed or timed out; collecting diagnostics." >&2
dump_diagnostics
echo "Reconnecting ADB for one bounded environment retry." >&2
adb reconnect
adb wait-for-device
wait_for_package_manager
if run_tests; then
  exit 0
fi
echo "Instrumentation retry failed or timed out; collecting final diagnostics." >&2
dump_diagnostics
exit 1
