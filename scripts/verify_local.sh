#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
REQUIRE_ANDROID=0
if [[ "${1:-}" == "--require-android" ]]; then
  REQUIRE_ANDROID=1
fi

run() {
  echo
  echo "=== $* ==="
  "$@"
}

run git diff --check
run python3 scripts/connector_lifecycle_static_scan.py
run python3 scripts/runtime_state_simulation.py
run python3 scripts/telemetry_privacy_scan.py
run python3 scripts/airi_localization_health.py --strict
run python3 tools/verify_core_changes.py
run python3 tools/airi_runtime_simulation.py

python3 - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET
root = Path('app/src/main/res')
def keys(path):
    return {node.attrib['name'] for node in ET.parse(path).getroot().findall('string')}
default = keys(root / 'values/strings.xml')
for locale in ('values-ar', 'values-es', 'values-zh'):
    missing = sorted(default - keys(root / locale / 'strings.xml'))
    if missing:
        raise SystemExit(f'{locale} missing {len(missing)} keys: {missing}')
print('resource_parity=PASS locales=values-ar,values-es,values-zh')
PY

if [[ -z "${ANDROID_SDK_ROOT:-}" && -z "${ANDROID_HOME:-}" && ! -f local.properties ]]; then
  if (( REQUIRE_ANDROID )); then
    echo "Android SDK is required but not configured (set ANDROID_SDK_ROOT or create local.properties)." >&2
    exit 2
  fi
  echo "android_build=SKIP reason=SDK_not_configured"
  exit 0
fi

run ./gradlew --no-daemon --max-workers=1 :core-domain:desktopTest :core-domain:compileDebugKotlinAndroid --stacktrace
run ./gradlew --no-daemon --max-workers=1 :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --stacktrace
