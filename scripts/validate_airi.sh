#!/usr/bin/env bash
# ════════════════════════════════════════════════════════════════════════════════
# AIRI Project Validation Script — Phase C7
# Run from repo root: bash scripts/validate_airi.sh
# Requires: bash, python3, grep. Does NOT require Android SDK.
# ════════════════════════════════════════════════════════════════════════════════
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KT_DIR="$REPO_ROOT/app/src/main/java"
PASS=0; FAIL=0; WARN=0

green() { printf "\033[32m✓ %s\033[0m\n" "$*"; ((PASS++)); }
red()   { printf "\033[31m✗ %s\033[0m\n" "$*"; ((FAIL++)); }
warn()  { printf "\033[33m⚠ %s\033[0m\n" "$*"; ((WARN++)); }

# ── 1. Dead onClick = {} ──────────────────────────────────────────────────────
echo ""
echo "═══ 1. Dead onClick lambdas ════════════════════════════════════════════"
dead=$(grep -rn "onClick = {}" "$KT_DIR" --include="*.kt" 2>/dev/null | grep -v "// ok")
if [ -z "$dead" ]; then green "No orphaned onClick = {} found"
else red "Dead onClick lambdas:"; echo "$dead"; fi

# ── 2. Duplicate composable names (cross-file, non-private) ──────────────────
echo ""
echo "═══ 2. Duplicate public composable names ══════════════════════════════"
dups=$(grep -rn "^fun " "$KT_DIR" --include="*.kt" | \
       awk -F'fun ' '{print $2}' | awk -F'(' '{print $1}' | sort | uniq -d)
if [ -z "$dups" ]; then green "No duplicate public composable names"
else warn "Duplicate public names (check for conflicts): $dups"; fi

# ── 3. NavHost route coverage ────────────────────────────────────────────────
echo ""
echo "═══ 3. NavHost route coverage ═════════════════════════════════════════"
python3 << 'PYEOF'
import re, sys, os
airiapp = os.path.join(os.environ.get('KT_DIR','app/src/main/java'),
    'com/airi/assistant/ui/AiriApp.kt')
try:
    src = open(airiapp).read()
except FileNotFoundError:
    print("\033[31m✗ AiriApp.kt not found\033[0m"); sys.exit(0)
declared = set(re.findall(r'const val \w+ = "(\w+)"', src))
registered = set(re.findall(r'composable\("([^"]+)"', src))
missing = declared - registered
if not missing:
    print("\033[32m✓ All declared routes registered in NavHost\033[0m")
else:
    print("\033[31m✗ Routes declared but NOT in NavHost:\033[0m")
    for r in sorted(missing): print(f"  {r}")
    sys.exit(1)
PYEOF

# ── 4. Cloud stream timeout guard ────────────────────────────────────────────
echo ""
echo "═══ 4. Cloud stream timeout guard ════════════════════════════════════"
if grep -q "withTimeout(90_000L)" \
   "$KT_DIR/com/airi/assistant/ui/viewmodel/ChatViewModel.kt" 2>/dev/null; then
    green "withTimeout(90_000L) guard present on cloud generateStream"
else red "withTimeout guard MISSING — chat will stall on slow cloud responses"; fi

# ── 5. StarBackground removed ────────────────────────────────────────────────
echo ""
echo "═══ 5. StarBackground removed ═════════════════════════════════════════"
if grep -q "StarBackground()" \
   "$KT_DIR/com/airi/assistant/ui/AiriApp.kt" 2>/dev/null; then
    red "StarBackground() still present in AiriApp"
else green "StarBackground removed from AiriApp"; fi

# ── 6. Duplicate back arrow in Settings ──────────────────────────────────────
echo ""
echo "═══ 6. Settings back arrow deduplicated ═══════════════════════════════"
back_count=$(grep -c "Icons.*ArrowBack\|Icons.Default.ArrowBack\|Icons.Filled.ArrowBack" \
    "$KT_DIR/com/airi/assistant/ui/screens/SettingsScreen.kt" 2>/dev/null || echo 99)
if [ "$back_count" -le 1 ]; then green "Settings has only 1 ArrowBack ($back_count)"
else red "Settings has $back_count ArrowBack refs — duplicate back button present"; fi

# ── 7. AIRI Mail and Cloud Browser removed ───────────────────────────────────
echo ""
echo "═══ 7. Dead Settings entries removed ══════════════════════════════════"
if grep -q "بريد Airi\|متصفح السحابة" \
   "$KT_DIR/com/airi/assistant/ui/screens/SettingsScreen.kt" 2>/dev/null; then
    red "Dead entries (AIRI Mail / Cloud Browser) still in Settings"
else green "AIRI Mail and Cloud Browser removed from Settings"; fi

# ── 8. Voice dead-end snackbar removed ───────────────────────────────────────
echo ""
echo "═══ 8. Voice dead-end navigation ══════════════════════════════════════"
remaining=$(grep -c "no_voice_model_installed" \
    "$KT_DIR/com/airi/assistant/ui/screens/ChatScreen.kt" 2>/dev/null || echo 0)
if [ "$remaining" -eq 0 ]; then green "All voice dead-ends replaced with VOICE_SETTINGS navigation"
else warn "$remaining voice dead-end snackbar(s) still present in ChatScreen"; fi

# ── 9. Token counter wired ────────────────────────────────────────────────────
echo ""
echo "═══ 9. Token counter wired ════════════════════════════════════════════"
if grep -q "todayTokens" "$KT_DIR/com/airi/assistant/ui/viewmodel/ChatViewModel.kt" \
   2>/dev/null && \
   grep -q "todayTokens" "$KT_DIR/com/airi/assistant/ui/screens/ChatScreen.kt" \
   2>/dev/null; then
    green "todayTokens StateFlow wired ViewModel → ChatScreen → TopBar"
else red "todayTokens not fully wired end-to-end"; fi

# ── 10. ThemePreferences exists ───────────────────────────────────────────────
echo ""
echo "═══ 10. ThemePreferences exists ════════════════════════════════════════"
if [ -f "$KT_DIR/com/airi/assistant/ui/theme/ThemePreferences.kt" ]; then
    green "ThemePreferences.kt present"
else red "ThemePreferences.kt MISSING"; fi

# ── 11. ConnectorsScreen wired to real ViewModel ─────────────────────────────
echo ""
echo "═══ 11. ConnectorsScreen real ViewModel ════════════════════════════════"
if grep -q "viewModel.connect\b\|viewModel.disconnect\b\|viewModel.selectTab\b" \
   "$KT_DIR/com/airi/assistant/ui/screens/ConnectorsScreen.kt" 2>/dev/null; then
    green "ConnectorsScreen wired to real ConnectorsViewModel"
else red "ConnectorsScreen not wired to real ViewModel"; fi

# ── 12. SkillManager 3 import paths ──────────────────────────────────────────
echo ""
echo "═══ 12. SkillManager import paths ══════════════════════════════════════"
if grep -q "ImportSource.STORAGE" \
   "$KT_DIR/com/airi/assistant/ui/screens/SkillManagerScreen.kt" 2>/dev/null && \
   grep -q "ImportSource.GITHUB" \
   "$KT_DIR/com/airi/assistant/ui/screens/SkillManagerScreen.kt" 2>/dev/null && \
   grep -q "ImportSource.AI" \
   "$KT_DIR/com/airi/assistant/ui/screens/SkillManagerScreen.kt" 2>/dev/null; then
    green "SkillManagerScreen has all 3 import paths"
else red "SkillManagerScreen missing one or more import paths"; fi

# ── 13. Model picker uses correct public API ──────────────────────────────────
echo ""
echo "═══ 13. Model picker API correctness ═══════════════════════════════════"
broken=$(grep -c "viewModel\.loadModel\b\|model\.displayName" \
    "$KT_DIR/com/airi/assistant/ui/screens/ChatScreen.kt" 2>/dev/null || echo 0)
if [ "$broken" -eq 0 ]; then green "Model picker uses correct public selectModel API"
else red "Model picker has $broken broken API reference(s) (loadModel/displayName)"; fi

# ── 14. No GlobalScope in UI layer ────────────────────────────────────────────
echo ""
echo "═══ 14. No GlobalScope in UI layer ════════════════════════════════════"
raw=$(grep -rn "GlobalScope\.launch" "$KT_DIR/com/airi/assistant/ui/" \
      --include="*.kt" 2>/dev/null | wc -l)
if [ "$raw" -eq 0 ]; then green "No GlobalScope launches in UI layer"
else warn "$raw GlobalScope.launch call(s) in UI — lifecycle leak risk"; fi

# ── 15. VoiceSettings has download prompt ─────────────────────────────────────
echo ""
echo "═══ 15. VoiceSettings download prompt ══════════════════════════════════"
if grep -q "downloadAndInstall" \
   "$KT_DIR/com/airi/assistant/ui/screens/VoiceSettingsScreen.kt" 2>/dev/null; then
    green "VoiceSettingsScreen has real one-tap download prompt"
else red "VoiceSettingsScreen missing download prompt — voice dead-end persists"; fi

# ── Summary ───────────────────────────────────────────────────────────────────
echo ""
echo "════════════════════════════════════════════════════════════════════════"
printf "  \033[32mPASS: %d\033[0m   \033[31mFAIL: %d\033[0m   \033[33mWARN: %d\033[0m\n" \
    "$PASS" "$FAIL" "$WARN"
echo "════════════════════════════════════════════════════════════════════════"
if [ "$FAIL" -gt 0 ]; then
    echo "BUILD BLOCKED — fix failures before release"
    exit 1
fi
echo "All critical checks passed"
exit 0
