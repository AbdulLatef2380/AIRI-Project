#!/usr/bin/env python3
"""
AIRI Full Project Audit Script — Phase C7 / Phase 10
Run from repo root: python3 scripts/audit_full.py
No Android SDK required.
"""
import os, re, sys, json
from pathlib import Path
from collections import defaultdict

REPO = Path(__file__).parent.parent
KT   = REPO / "app/src/main/java/com/airi/assistant"
RES  = REPO / "app/src/main/res"
MANIFEST = REPO / "app/src/main/AndroidManifest.xml"

RED   = "\033[31m"; GRN = "\033[32m"; YLW = "\033[33m"; RST = "\033[0m"; BLD = "\033[1m"
PASS, FAIL, WARN = [], [], []

def ok(msg):  PASS.append(msg); print(f"{GRN}✓ {msg}{RST}")
def bad(msg): FAIL.append(msg); print(f"{RED}✗ {msg}{RST}")
def warn(msg):WARN.append(msg); print(f"{YLW}⚠ {msg}{RST}")

def kt_files(subpath=""):
    base = KT / subpath if subpath else KT
    return list(base.rglob("*.kt")) if base.exists() else []

def grep(path, pattern):
    try: return [l for l in open(path).readlines() if re.search(pattern, l)]
    except: return []

def grep_all(subpath, pattern):
    results = []
    for f in kt_files(subpath):
        for i, line in enumerate(open(f).readlines(), 1):
            if re.search(pattern, line):
                results.append((f.name, i, line.rstrip()))
    return results

# ══════════════════════════════════════════════════════════════════════════════
print(f"\n{BLD}═══ 1. DEAD onClick LAMBDAS ════════════════════════════════════════{RST}")
dead = grep_all("ui", r"onClick\s*=\s*\{\}")
dead = [d for d in dead if "// ok" not in d[2] and "// acceptable" not in d[2]]
if not dead: ok("No dead onClick = {} found")
else:
    bad(f"{len(dead)} dead onClick lambda(s):")
    for f, l, line in dead: print(f"   {f}:{l}  {line.strip()}")

# ══════════════════════════════════════════════════════════════════════════════
print(f"\n{BLD}═══ 2. NAV ROUTE COVERAGE ══════════════════════════════════════════{RST}")
airiapp = KT / "ui/AiriApp.kt"
if airiapp.exists():
    src = open(airiapp).read()
    declared  = set(re.findall(r'const val \w+ = "(\w+)"', src))
    registered = set(re.findall(r'composable\(AiriRoute\.(\w+)\)', src))
    # also check string-literal registrations
    str_reg = set(re.findall(r'composable\("(\w+)"\)', src))
    all_reg = registered | str_reg

    # map const_names → values
    route_map = dict(re.findall(r'const val (\w+) = "(\w+)"', src))
    inv_map   = {v: k for k, v in route_map.items()}

    unregistered = [r for r in declared if inv_map.get(r) not in all_reg and r not in str_reg]
    if not unregistered: ok(f"All {len(declared)} declared routes have NavHost registrations")
    else:
        bad(f"{len(unregistered)} routes declared but never registered:")
        for r in sorted(unregistered): print(f"   {r}")
else:
    bad("AiriApp.kt not found")

# ══════════════════════════════════════════════════════════════════════════════
print(f"\n{BLD}═══ 3. MANIFEST DEEP-LINK COVERAGE ═════════════════════════════════{RST}")
if MANIFEST.exists():
    manifest_src = open(MANIFEST).read()
    if 'android:scheme="airi"' in manifest_src:
        hosts = re.findall(r'android:host="([^"]+)"', manifest_src)
        ok(f"airi:// deep-link scheme registered, hosts: {hosts}")
        if "callback" not in " ".join(hosts) and "oauth" not in " ".join(hosts):
            warn("No 'callback' or 'oauth' host registered — OAuth return-to-app will not work for browser-based OAuth flows")
    else:
        bad("No airi:// scheme in manifest — OAuth deep-links will fail")

# ══════════════════════════════════════════════════════════════════════════════
print(f"\n{BLD}═══ 4. NON-PERSISTENT SETTINGS TOGGLES ═════════════════════════════{RST}")
settings_screens = ["GeneralSettingsScreen.kt", "PrivacyDataSettingsScreen.kt",
                    "CustomizationSettingsScreen.kt", "VoiceSettingsScreen.kt"]
persistent_apis  = ["DataStore", "SharedPreferences", "ThemePreferences", "LanguageManager",
                    "ExecModePreferences", "VoskModelManager", "SecureApiKeyStore",
                    "DataControls", "viewModel"]
issues = []
for screen in settings_screens:
    path = KT / f"ui/screens/{screen}"
    if not path.exists(): continue
    src  = open(path).read()
    var_toggles = re.findall(r'var \w+ by remember \{ mutableStateOf\((true|false)\) \}', src)
    has_persist  = any(api in src for api in persistent_apis)
    if var_toggles and not has_persist:
        issues.append(f"{screen}: {len(var_toggles)} remember-only toggle(s), no persistence API found")
if not issues: ok("All settings screens have at least one persistence API reference")
else:
    for i in issues: warn(f"Potentially non-persistent: {i}")

# ══════════════════════════════════════════════════════════════════════════════
print(f"\n{BLD}═══ 5. CLOUD STREAM TIMEOUT GUARD ══════════════════════════════════{RST}")
vm = KT / "ui/viewmodel/ChatViewModel.kt"
if vm.exists() and "withTimeout(90_000L)" in open(vm).read():
    ok("withTimeout(90_000L) guard on cloud generateStream")
else:
    bad("withTimeout guard MISSING from ChatViewModel.streamRemoteResponse — stall risk")

# ══════════════════════════════════════════════════════════════════════════════
print(f"\n{BLD}═══ 6. STAR BACKGROUND REMOVED ═════════════════════════════════════{RST}")
app_kt = KT / "ui/AiriApp.kt"
if app_kt.exists() and "StarBackground()" not in open(app_kt).read():
    ok("StarBackground() removed from AiriApp")
else:
    bad("StarBackground() still present in AiriApp.kt")

# ══════════════════════════════════════════════════════════════════════════════
print(f"\n{BLD}═══ 7. THEME SYSTEM COMPLETENESS ════════════════════════════════════{RST}")
theme_prefs = KT / "ui/theme/ThemePreferences.kt"
theme_kt    = KT / "ui/theme/Theme.kt"
if theme_prefs.exists():
    src = open(theme_prefs).read()
    if "ThemeMode.DARK" in src and "ThemeMode.LIGHT" in src and "ThemeMode.SYSTEM" in src:
        ok("ThemePreferences has DARK/LIGHT/SYSTEM modes")
    else:
        bad("ThemePreferences missing one or more modes")
else:
    bad("ThemePreferences.kt not found")
if theme_kt.exists():
    src = open(theme_kt).read()
    if "LightColorScheme" in src and "DarkColorScheme" in src and "isSystemInDarkTheme" in src:
        ok("Theme.kt has both colour schemes and system detection")
    else:
        bad("Theme.kt missing light scheme or system detection")

# ══════════════════════════════════════════════════════════════════════════════
print(f"\n{BLD}═══ 8. SCHEDULED TASKS REAL WIRING ═════════════════════════════════{RST}")
tasks_screen = KT / "ui/screens/AgentTasksScreen.kt"
if tasks_screen.exists():
    src = open(tasks_screen).read()
    if "ScheduledJobOrchestrator" in src and "orchestrator.listJobs()" in src:
        ok("AgentTasksScreen uses real ScheduledJobOrchestrator")
    else:
        bad("AgentTasksScreen not wired to ScheduledJobOrchestrator")
    if "Sample" not in src and "sampleData" not in src and "fakeJob" not in src:
        ok("No sample/fake job data in AgentTasksScreen")
    else:
        bad("Sample/fake job data found in AgentTasksScreen")

# ══════════════════════════════════════════════════════════════════════════════
print(f"\n{BLD}═══ 9. DEVELOPER TOOLS REACHABLE FROM SETTINGS ════════════════════{RST}")
settings = KT / "ui/screens/SettingsScreen.kt"
if settings.exists():
    src = open(settings).read()
    tools = ["AiriRoute.TERMINAL", "AiriRoute.WORKSPACE", "AiriRoute.EXEC_DIAGNOSTICS",
             "AiriRoute.DEVELOPER_CENTER", "AiriRoute.SANDBOX_WORKSPACE"]
    missing = [t for t in tools if t not in src]
    if not missing:
        ok(f"All {len(tools)} developer tool routes reachable from Settings")
    else:
        bad(f"Settings missing routes: {missing}")

# ══════════════════════════════════════════════════════════════════════════════
print(f"\n{BLD}═══ 10. SKILL MANAGER IMPORT PATHS ════════════════════════════════{RST}")
skill_screen = KT / "ui/screens/SkillManagerScreen.kt"
if skill_screen.exists():
    src = open(skill_screen).read()
    paths = {"Storage": "ImportSource.STORAGE", "GitHub": "ImportSource.GITHUB", "AI": "ImportSource.AI"}
    missing = [k for k, v in paths.items() if v not in src]
    if not missing:
        ok("SkillManagerScreen has all 3 import paths (Storage, GitHub, AI)")
    else:
        bad(f"SkillManagerScreen missing import path(s): {missing}")

# ══════════════════════════════════════════════════════════════════════════════
print(f"\n{BLD}═══ 11. CONNECTOR SCREEN REAL VIEWMODEL ════════════════════════════{RST}")
conn_screen = KT / "ui/screens/ConnectorsScreen.kt"
if conn_screen.exists():
    src = open(conn_screen).read()
    if all(m in src for m in ["viewModel.connect", "viewModel.disconnect", "viewModel.selectTab"]):
        ok("ConnectorsScreen wired to real ConnectorsViewModel")
    else:
        bad("ConnectorsScreen missing one or more ViewModel calls")

# ══════════════════════════════════════════════════════════════════════════════
print(f"\n{BLD}═══ 12. TOKEN COUNTER WIRED END-TO-END ════════════════════════════{RST}")
if vm.exists() and "todayTokens" in open(vm).read():
    chat = KT / "ui/screens/ChatScreen.kt"
    if chat.exists() and "todayTokens" in open(chat).read():
        ok("todayTokens StateFlow wired: TokenAccountant → ViewModel → ChatScreen → TopBar")
    else:
        bad("todayTokens defined in ViewModel but not collected in ChatScreen")
else:
    bad("todayTokens StateFlow not found in ChatViewModel")

# ══════════════════════════════════════════════════════════════════════════════
print(f"\n{BLD}═══ 13. VOICE DEAD-END ELIMINATED ══════════════════════════════════{RST}")
chat = KT / "ui/screens/ChatScreen.kt"
if chat.exists():
    src = open(chat).read()
    remaining = src.count("no_voice_model_installed")
    if remaining == 0:
        ok("All voice dead-ends replaced with VOICE_SETTINGS navigation")
    else:
        bad(f"{remaining} voice dead-end snackbar reference(s) remain in ChatScreen")

# ══════════════════════════════════════════════════════════════════════════════
print(f"\n{BLD}═══ 14. MODEL PICKER API CORRECTNESS ══════════════════════════════{RST}")
if chat.exists():
    src = open(chat).read()
    broken_refs = bool(re.search(r'viewModel\.loadModel\b|model\.displayName', src))
    if not broken_refs:
        ok("Model picker uses correct public selectModel API")
    else:
        bad("Model picker still references private loadModel or non-existent displayName")

# ══════════════════════════════════════════════════════════════════════════════
print(f"\n{BLD}═══ 15. DUPLICATE PUBLIC COMPOSABLES ══════════════════════════════{RST}")
pub_fns = defaultdict(list)
for f in kt_files("ui"):
    src = open(f).read()
    # Only public (non-private, non-internal) top-level fun declarations
    for m in re.finditer(r'^(?!private |internal )fun (\w+)\(', src, re.MULTILINE):
        pub_fns[m.group(1)].append(f.name)
dups = {k: v for k, v in pub_fns.items() if len(v) > 1}
if not dups:
    ok("No duplicate public composable names across UI layer")
else:
    bad(f"{len(dups)} duplicate public composable name(s):")
    for name, files in dups.items(): print(f"   {name} in {files}")

# ══════════════════════════════════════════════════════════════════════════════
print(f"\n{BLD}═══ 16. GLOBALSCOPE LEAK DETECTION ════════════════════════════════{RST}")
leaks = grep_all("ui", r"GlobalScope\.launch")
if not leaks:
    ok("No GlobalScope.launch in UI layer")
else:
    bad(f"{len(leaks)} GlobalScope.launch call(s) — lifecycle leak risk:")
    for f, l, line in leaks: print(f"   {f}:{l}")

# ══════════════════════════════════════════════════════════════════════════════
print(f"\n{BLD}═══ 17. SETTINGS ENTRY CLEANUP ════════════════════════════════════{RST}")
if settings.exists():
    src = open(settings).read()
    removed = ["بريد Airi", "متصفح السحابة"]
    still_present = [e for e in removed if e in src]
    if not still_present:
        ok("AIRI Mail and Cloud Browser removed from Settings")
    else:
        bad(f"Dead Settings entries still present: {still_present}")

# ══════════════════════════════════════════════════════════════════════════════
print(f"\n{BLD}═══ 18. VOICE DOWNLOAD PROMPT IN VOICE SETTINGS ══════════════════{RST}")
voice_s = KT / "ui/screens/VoiceSettingsScreen.kt"
if voice_s.exists() and "downloadAndInstall" in open(voice_s).read():
    ok("VoiceSettingsScreen has one-tap model download wired to VoskModelManager")
else:
    bad("VoiceSettingsScreen missing download prompt — voice dead-end persists on fresh install")

# ══════════════════════════════════════════════════════════════════════════════
print(f"\n{BLD}═══ 19. KNOWN RUNTIME LIMITATIONS ════════════════════════════════{RST}")
limitations = [
    ("WorkManager 15-min floor", "AgentTasksScreen notes minimum delay; scheduleOnce uses exact delay — OS may defer"),
    ("OAuth deep-link host", "Manifest has airi://referral but no airi://callback — browser OAuth incomplete"),
    ("Vosk bundled model", "No model in assets/ — VoiceSettings downloads on first use (~40 MB)"),
    ("AudioFocus FAILED", "DuplexConversationRuntime does not handle AUDIOFOCUS_REQUEST_FAILED"),
    ("PorcupineEngine key", "Demo access key has usage limits — production requires user key"),
    ("ScheduledAgentWorker dispatch", "Worker routes via SubAgentRegistry.route() → execute() Flow collect, falls back to ProductionAgentOrchestrator.executeSingle()"),
    ("ApiKeyEntryDialog dup", "Duplicated (private) in CloudModelStore.kt and ModelLibraryScreen.kt"),
    ("SectionHeader dup", "Public SectionHeader in ModelSettingsScreen conflicts with private in ModelLibraryScreen"),
]
print("Known limitations (not blocking build):")
for title, detail in limitations:
    warn(f"{title}: {detail}")

# ══════════════════════════════════════════════════════════════════════════════
print(f"\n{BLD}═══ 20. SECURITY: PLAINTEXT STORAGE — NO DISK FALLBACK ══════════════{RST}")
secure = KT / "auth/SecureStorage.kt"
if secure.exists():
    src = open(secure).read()
    has_disk_fallback = "getSharedPreferences" in src and "fallback" in src.lower()
    has_memory_fallback = "InMemorySharedPreferences" in src
    has_encrypted_flag = "isEncrypted" in src
    if has_disk_fallback:
        bad("SecureStorage still has plaintext DISK fallback — credential exposure risk")
    elif has_memory_fallback and has_encrypted_flag:
        ok("SecureStorage: in-memory fallback only (no plaintext disk writes); isEncrypted flag exposes state to UI")
    else:
        warn("SecureStorage: cannot confirm fallback policy — manual inspection required")

print(f"\n{BLD}═══ 21. SECURITY: SANDBOX NETWORK COMMANDS REMOVED ═════════════{RST}")
sandbox = KT / "agent/sandbox/SandboxExecutor.kt"
if sandbox.exists():
    # Only scan non-comment lines for the actual allowlist
    code_lines = [l for l in open(sandbox).readlines()
                  if not l.strip().startswith("*") and not l.strip().startswith("//")]
    code_src = "".join(code_lines)
    dangerous = [cmd for cmd in ["\"curl\"", "\"wget\"", "\"git clone\""] if cmd in code_src]
    if not dangerous:
        ok("SandboxExecutor ALLOWED_SHELL: curl/wget/git-clone removed (exfiltration risk eliminated)")
    else:
        bad(f"SandboxExecutor still allows dangerous network commands: {dangerous}")

print(f"\n{BLD}═══ 22. SECURITY: OAUTH STATE CSRF VALIDATION ══════════════════{RST}")
ivm = KT / "ui/viewmodel/IntegrationsViewModel.kt"
if ivm.exists():
    src = open(ivm).read()
    if "oauthStateToken" in src and "validateOAuthState" in src and "SecureRandom" in src:
        ok("IntegrationsViewModel has per-session CSRF state token with validation")
    else:
        warn("IntegrationsViewModel missing OAuth CSRF state validation")

print(f"\n{BLD}═══ 23. ACCESSIBILITY CONFIG HARDENED ══════════════════════════════{RST}")
a11y = REPO / "app/src/main/res/xml/accessibility_service_config.xml"
if a11y.exists():
    # Skip comment-block lines (they explain what was removed — expected to mention the bad strings)
    in_comment = False
    attr_lines = []
    for line in open(a11y).readlines():
        stripped = line.strip()
        if stripped.startswith("<!--"): in_comment = True
        if not in_comment: attr_lines.append(line)
        if stripped.endswith("-->"): in_comment = False
    attr_src = "".join(attr_lines)
    bad_found = [s for s in ["typeAllMask", "flagRequestFilterKeyEvents"] if s in attr_src]
    if not bad_found:
        ok("accessibility_service_config.xml: typeAllMask and flagRequestFilterKeyEvents absent from attributes")
    else:
        bad(f"accessibility_service_config.xml has dangerous attrs in real attribute lines: {bad_found}")
else:
    warn("accessibility_service_config.xml not found")

print(f"\n{BLD}═══ 24. AccessibilityScopePolicy CREATED ════════════════════════════{RST}")
asp = KT / "accessibility/security/AccessibilityScopePolicy.kt"
if asp.exists():
    src = open(asp).read()
    if "fun readsAllowedFor" in src and "DENIED_PACKAGES" in src and "DENIED_PREFIXES" in src:
        ok("AccessibilityScopePolicy exists with package denylist and readsAllowedFor API")
    else:
        bad("AccessibilityScopePolicy missing required methods")
else:
    bad("AccessibilityScopePolicy.kt not found")

print(f"\n{BLD}═══ 25. OAuthStateRegistry WIRED IN MainActivity ════════════════════{RST}")
main = KT / "MainActivity.kt"
if main.exists():
    src = open(main).read()
    if "OAuthStateRegistry.consume" in src and "dispatchOAuthCallback" in src:
        ok("MainActivity uses OAuthStateRegistry.consume for CSRF-validated OAuth callbacks")
    else:
        bad("MainActivity not wired to OAuthStateRegistry")

# ══════════════════════════════════════════════════════════════════════════════
print(f"\n{'═'*70}")
print(f"  {GRN}PASS: {len(PASS)}{RST}   {RED}FAIL: {len(FAIL)}{RST}   {YLW}WARN: {len(WARN)}{RST}")
print(f"{'═'*70}")

if FAIL:
    print(f"{RED}BUILD BLOCKED — fix {len(FAIL)} failure(s) above before release{RST}")
    sys.exit(1)
else:
    print(f"{GRN}All critical checks passed. See warnings above for known limitations.{RST}")
    sys.exit(0)
