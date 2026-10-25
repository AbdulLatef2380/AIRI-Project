#!/usr/bin/env bash
set -Eeuo pipefail

readonly api_level="${1:?usage: run-instrumentation-dispatch.sh <api-level> <locale>}"
readonly locale="${2:?usage: run-instrumentation-dispatch.sh <api-level> <locale>}"

if [[ "$api_level" == "34" && "$locale" == "ar-SA" ]]; then
  export INSTRUMENTATION_TRACE_DIR="ci-instrumentation-${api_level}-${locale}/runtime"
  exec bash .github/scripts/run-instrumentation-traced.sh "$locale"
fi

bash .github/scripts/set-emulator-locale.sh "$locale"
exec bash .github/scripts/run-instrumentation.sh
