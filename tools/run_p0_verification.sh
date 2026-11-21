#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-arm64}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-/opt/android-sdk}"
export ANDROID_HOME="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"
export PATH="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"

if [[ ! -f local.properties ]]; then
  printf 'sdk.dir=%s\n' "$ANDROID_SDK_ROOT" > local.properties
fi

REPORT_DIR="${P0_REPORT_DIR:-$ROOT/build/p0-verification-$(date -u +%Y%m%dT%H%M%SZ)}"
mkdir -p "$REPORT_DIR"
FAILED_STEPS=()

GRADLE=(./gradlew --no-daemon --max-workers=1)
GRADLE_OPTS=(-Dorg.gradle.jvmargs=-Xmx6g\ -XX:MaxMetaspaceSize=1g\ -XX:+UseSerialGC -Dkotlin.daemon.jvm.options=-Xmx4g)

run_step() {
  local name="$1"; shift
  printf '\n== %s ==\n' "$name" | tee "$REPORT_DIR/$name.log"
  set +e
  "${GRADLE[@]}" "${GRADLE_OPTS[@]}" "$@" 2>&1 | tee -a "$REPORT_DIR/$name.log"
  local status=${PIPESTATUS[0]}
  set -e
  if (( status != 0 )); then
    FAILED_STEPS+=("$name")
    printf 'step=%s status=FAILED exit=%s\n' "$name" "$status" | tee -a "$REPORT_DIR/summary.log"
  else
    printf 'step=%s status=PASS\n' "$name" | tee -a "$REPORT_DIR/summary.log"
  fi
  return 0
}

run_step compile :app:compileDebugKotlin
run_step unit :app:testDebugUnitTest
run_step p0_h1_acceptance \
  :app:testDebugUnitTest \
  --tests 'com.airi.assistant.agent.durable.P0H1CentralAcceptanceTest'
run_step vertical \
  :app:testDebugUnitTest \
  --tests 'com.airi.assistant.agent.loop.AgentLoopVerticalIntegrationTest' \
  --tests 'com.airi.assistant.domain.tool.ToolExecutionPipelineTest' \
  --tests 'com.airi.assistant.execution.cloud.OpenAISseParserTest' \
  --tests 'com.airi.assistant.execution.cloud.NativeProviderPayloadContractTest' \
  --tests 'com.airi.assistant.ai.skills.SkillInvocationAccessPolicyTest' \
  --tests 'com.airi.assistant.agent.sandbox.SandboxExecutorSecurityTest' \
  --tests 'com.airi.assistant.memory.rag.RagQueryPolicyTest'

if command -v adb >/dev/null 2>&1 && adb devices | awk 'NR > 1 && $2 == "device" { found=1 } END { exit(found ? 0 : 1) }'; then
  run_step connected :app:connectedDebugAndroidTest
else
  printf 'connected=BLOCKED no authorized Android device or emulator\n' | tee "$REPORT_DIR/connected.log"
fi

printf '\nreports=%s\n' "$REPORT_DIR"
if (( ${#FAILED_STEPS[@]} > 0 )); then
  printf 'failed_steps=%s\n' "${FAILED_STEPS[*]}" | tee -a "$REPORT_DIR/summary.log"
  exit 1
fi
printf 'failed_steps=none\n' | tee -a "$REPORT_DIR/summary.log"
