#!/usr/bin/env bash
set -Eeuo pipefail
: "${NATIVE_SMOKE_MODEL:?Set NATIVE_SMOKE_MODEL to a GGUF path}"
: "${NATIVE_SMOKE_SHA256:?Set NATIVE_SMOKE_SHA256 to the expected SHA-256}"
readonly device_model=/data/local/tmp/airi-native-smoke.gguf
readonly app_model=/data/user/0/com.airi.assistant/files/airi-native-smoke.gguf
readonly evidence_dir="${NATIVE_SMOKE_EVIDENCE_DIR:-ci-native-smoke}"
mkdir -p "$evidence_dir"
sha256sum "$NATIVE_SMOKE_MODEL" | tee "$evidence_dir/model.sha256"
test "$(sha256sum "$NATIVE_SMOKE_MODEL" | awk '{print $1}')" = "$NATIVE_SMOKE_SHA256"
adb wait-for-device
adb push "$NATIVE_SMOKE_MODEL" "$device_model" | tee "$evidence_dir/adb-push.txt"
adb shell "sha256sum $device_model" | tee "$evidence_dir/device-model.sha256"
./gradlew --no-daemon --max-workers=1 :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace
adb install -r app/build/outputs/apk/debug/app-debug.apk | tee "$evidence_dir/install-app.txt"
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk | tee "$evidence_dir/install-test.txt"
adb shell run-as com.airi.assistant mkdir -p files
adb shell run-as com.airi.assistant cp "$device_model" files/airi-native-smoke.gguf
adb shell am instrument -w -e class com.airi.assistant.ai.LlamaNativeSmokeTest \
  -e airi.nativeSmokeModel "$app_model" \
  com.airi.assistant.test/androidx.test.runner.AndroidJUnitRunner | tee "$evidence_dir/instrumentation.txt"
adb logcat -d -t 2000 > "$evidence_dir/logcat.txt"
adb shell uiautomator dump /sdcard/airi-native-smoke-ui.xml >/dev/null 2>&1 || true
adb pull /sdcard/airi-native-smoke-ui.xml "$evidence_dir/ui.xml" >/dev/null 2>&1 || true
adb shell run-as com.airi.assistant rm -f files/airi-native-smoke.gguf || true
adb shell rm -f "$device_model" || true
