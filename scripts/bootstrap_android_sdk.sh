#!/usr/bin/env bash
set -euo pipefail

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/android-sdk}}"
CMDLINE_TOOLS_VERSION="11076708"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip"
TOOLS_DIR="$SDK_ROOT/cmdline-tools/latest"
SDKMANAGER="$TOOLS_DIR/bin/sdkmanager"

mkdir -p "$SDK_ROOT/cmdline-tools"
if [[ ! -x "$SDKMANAGER" ]]; then
  tmp_dir="$(mktemp -d)"
  trap 'rm -rf "$tmp_dir"' EXIT
  archive="$tmp_dir/commandlinetools.zip"
  echo "Downloading Android command-line tools ${CMDLINE_TOOLS_VERSION}..."
  curl --fail --location --retry 3 --retry-delay 2 --output "$archive" "$CMDLINE_TOOLS_URL"
  rm -rf "$tmp_dir/extracted" "$TOOLS_DIR"
  mkdir -p "$tmp_dir/extracted" "$TOOLS_DIR"
  unzip -q "$archive" -d "$tmp_dir/extracted"
  cp -a "$tmp_dir/extracted/cmdline-tools/." "$TOOLS_DIR/"
fi

export ANDROID_SDK_ROOT="$SDK_ROOT"
export ANDROID_HOME="$SDK_ROOT"
export PATH="$TOOLS_DIR/bin:$SDK_ROOT/platform-tools:$PATH"

printf 'y\n' | "$SDKMANAGER" --sdk_root="$SDK_ROOT" --licenses >/dev/null || true
"$SDKMANAGER" --sdk_root="$SDK_ROOT" --install \
  "platform-tools" \
  "platforms;android-36" \
  "build-tools;36.0.0" \
  "ndk;25.2.9519653" \
  "cmake;3.22.1"

if [[ -d "$(pwd)" && -f settings.gradle.kts ]]; then
  printf 'sdk.dir=%s\n' "$SDK_ROOT" > local.properties
fi

cat <<EOF
Android SDK ready:
  ANDROID_SDK_ROOT=$SDK_ROOT
  platform=android-36
  build-tools=36.0.0
  ndk=25.2.9519653
  cmake=3.22.1
EOF
