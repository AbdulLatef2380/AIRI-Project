#!/usr/bin/env python3
"""
AIRI Build Verification Script
===============================
Performs static analysis equivalent to a partial compiler pass.
Does NOT modify any production code.

Outputs 5 reports to reports/verification/
"""

import os, re, sys, json, collections, time
from pathlib import Path
from dataclasses import dataclass, field
from typing import Optional

# ── Config ─────────────────────────────────────────────────────────────────────

ROOT       = Path(__file__).parent.parent
APP_SRC    = ROOT / "app" / "src" / "main" / "java" / "com" / "airi" / "assistant"
REPORTS    = ROOT / "reports" / "verification"
GRADLE_DIR = ROOT / "gradle"

# ── Data models ───────────────────────────────────────────────────────────────

@dataclass
class Symbol:
    kind: str           # class|interface|object|enum|sealed|data|typealias|fun|companion
    name: str
    fqn: str            # fully qualified name
    file: str
    line: int
    package: str
    is_nested: bool = False
    parent: str = ""    # parent class FQN for nested symbols

@dataclass
class Import:
    raw: str            # raw import string
    file: str
    line: int
    is_wildcard: bool = False

@dataclass
class Issue:
    severity: str       # HIGH / MEDIUM / LOW
    category: str
    file: str
    line: int
    message: str
    symbol: str = ""

# ── Phase 1: Symbol index ─────────────────────────────────────────────────────

SYMBOL_PATTERNS = [
    (r'^\s*(?:public\s+)?(?:data\s+)?class\s+(\w+)',          'class'),
    (r'^\s*(?:public\s+)?(?:abstract\s+)?class\s+(\w+)',      'class'),
    (r'^\s*(?:public\s+)?(?:open\s+)?class\s+(\w+)',          'class'),
    (r'^\s*(?:public\s+)?interface\s+(\w+)',                   'interface'),
    (r'^\s*(?:public\s+)?(?:companion\s+)?object\s+(\w+)',    'object'),
    (r'^\s*(?:public\s+)?companion\s+object',                  'companion'),
    (r'^\s*(?:public\s+)?enum\s+class\s+(\w+)',               'enum'),
    (r'^\s*(?:public\s+)?sealed\s+class\s+(\w+)',             'sealed'),
    (r'^\s*typealias\s+(\w+)',                                  'typealias'),
    (r'^\s*(?:public\s+)?fun\s+(\w+)\s*[(<]',                 'fun'),
    (r'^\s*(?:public\s+)?fun\s+\w+[.]\w+\s*[(<]',            'extension'),
]

def get_package(lines):
    for line in lines[:30]:
        m = re.match(r'^package\s+([\w.]+)', line)
        if m:
            return m.group(1)
    return "unknown"

def index_symbols(src_root: Path):
    symbols = {}        # fqn → Symbol
    duplicates = []     # list of (fqn, file1, file2)
    by_simple_name = collections.defaultdict(list)  # name → [Symbol]

    for kt_file in src_root.rglob("*.kt"):
        rel = str(kt_file.relative_to(src_root))
        try:
            lines = kt_file.read_text(errors='replace').splitlines()
        except Exception:
            continue

        pkg = get_package(lines)
        indent_stack = []  # track nesting
        class_stack  = []  # stack of enclosing class names

        for lineno, raw_line in enumerate(lines, 1):
            stripped = raw_line.strip()
            if stripped.startswith('//') or stripped.startswith('*'):
                continue

            # Track brace depth for nesting
            opens  = raw_line.count('{')
            closes = raw_line.count('}')

            for pat, kind in SYMBOL_PATTERNS:
                m = re.match(pat, raw_line)
                if not m:
                    continue
                name = m.group(1) if m.lastindex and m.lastindex >= 1 else "companion"
                if not name or not re.match(r'^[A-Za-z_]\w*$', name):
                    continue

                parent = class_stack[-1] if class_stack else ""
                is_nested = bool(parent)
                if is_nested:
                    fqn = f"{pkg}.{parent}.{name}" if parent else f"{pkg}.{name}"
                else:
                    fqn = f"{pkg}.{name}"

                sym = Symbol(
                    kind=kind, name=name, fqn=fqn, file=rel,
                    line=lineno, package=pkg,
                    is_nested=is_nested, parent=parent
                )
                if fqn in symbols:
                    duplicates.append((fqn, symbols[fqn].file, rel))
                else:
                    symbols[fqn] = sym
                    by_simple_name[name].append(sym)
                break  # only match first pattern per line

            # Update class stack (very simplified — no multi-line class headers)
            if opens > 0:
                for pat, kind in SYMBOL_PATTERNS:
                    m = re.match(pat, raw_line)
                    if m and m.lastindex and m.lastindex >= 1:
                        class_stack.append(m.group(1))
                        break
                else:
                    class_stack.append("")
            for _ in range(closes):
                if class_stack:
                    class_stack.pop()

    return symbols, duplicates, by_simple_name

# ── Phase 2: Import resolution ────────────────────────────────────────────────

def collect_imports(src_root: Path):
    all_imports = []
    for kt_file in src_root.rglob("*.kt"):
        rel = str(kt_file.relative_to(src_root))
        try:
            lines = kt_file.read_text(errors='replace').splitlines()
        except Exception:
            continue
        for lineno, line in enumerate(lines, 1):
            stripped = line.strip()
            if stripped.startswith('//') or stripped.startswith('*'):
                continue
            m = re.match(r'^import\s+([\w.*]+)', stripped)
            if m:
                raw = m.group(1)
                if raw.startswith('com.airi.assistant'):
                    all_imports.append(Import(
                        raw=raw, file=rel, line=lineno,
                        is_wildcard=raw.endswith('.*')
                    ))
    return all_imports

def resolve_imports(imports, symbols, by_simple_name):
    """
    Returns list of Issue for unresolved imports.
    Handles: exact FQN, wildcard (package.*), nested classes (A.B).
    """
    issues = []

    # Build package set
    packages = set(s.package for s in symbols.values())
    # Also add sub-packages implied by symbols
    all_known_packages = set()
    for p in packages:
        parts = p.split('.')
        for i in range(len(parts)):
            all_known_packages.add('.'.join(parts[:i+1]))

    # Add build-generated pseudo-packages
    KNOWN_GENERATED = {
        'com.airi.assistant.R',
        'com.airi.assistant.BuildConfig',
        'com.airi.assistant.databinding',
    }

    for imp in imports:
        raw = imp.raw

        # Skip build-generated
        if any(raw.startswith(g) for g in KNOWN_GENERATED):
            continue
        # Skip wildcard imports if package exists
        if imp.is_wildcard:
            pkg = raw[:-2]  # strip .*
            if pkg in all_known_packages or any(p.startswith(pkg) for p in packages):
                continue
            else:
                issues.append(Issue(
                    severity='MEDIUM', category='UNRESOLVED_WILDCARD_IMPORT',
                    file=imp.file, line=imp.line,
                    message=f"Wildcard import package not found: {pkg}",
                    symbol=raw
                ))
            continue

        # Exact match
        if raw in symbols:
            continue

        # Nested class: com.a.b.Outer.Inner — try splitting
        parts = raw.split('.')
        # Try progressively shorter FQNs (for nested)
        found = False
        for split_at in range(len(parts) - 1, 1, -1):
            outer_fqn = '.'.join(parts[:split_at])
            inner_name = '.'.join(parts[split_at:])
            if outer_fqn in symbols:
                found = True
                break
            # Check by simple name
            outer_name = parts[split_at - 1]
            if outer_name in by_simple_name:
                found = True
                break

        if not found:
            # Check simple name directly
            simple = parts[-1]
            if simple in by_simple_name:
                continue  # resolved by simple name match

            issues.append(Issue(
                severity='HIGH', category='UNRESOLVED_IMPORT',
                file=imp.file, line=imp.line,
                message=f"Cannot resolve import: {raw}",
                symbol=raw
            ))

    return issues

# ── Phase 3: Call graph ────────────────────────────────────────────────────────

def build_call_graph(src_root: Path, symbols: dict, by_simple_name: dict):
    """
    Build simplified call graph: for each file, find references to known symbols.
    Returns: callers_of[fqn] = set of files/fqns, callee_of[file] = set of fqns
    """
    callers_of  = collections.defaultdict(set)   # fqn → {calling_file}
    callees_of  = collections.defaultdict(set)   # file → {referenced_fqn}
    dead_symbols = []

    for kt_file in src_root.rglob("*.kt"):
        rel = str(kt_file.relative_to(src_root))
        try:
            content = kt_file.read_text(errors='replace')
        except Exception:
            continue
        pkg = get_package(content.splitlines()[:30])

        for sym in symbols.values():
            # Skip self-references
            if sym.file == rel:
                continue
            # Skip comments  
            # Check if simple name appears in file content
            if re.search(r'\b' + re.escape(sym.name) + r'\b', content):
                # Verify it's not all in comments
                for line in content.splitlines():
                    stripped = line.strip()
                    if stripped.startswith('//') or stripped.startswith('*'):
                        continue
                    if re.search(r'\b' + re.escape(sym.name) + r'\b', line):
                        callers_of[sym.fqn].add(rel)
                        callees_of[rel].add(sym.fqn)
                        break

    # Find dead symbols (0 callers, not in their own file)
    for fqn, sym in symbols.items():
        if sym.kind in ('fun',) and sym.name.startswith('_'):
            continue  # skip private-convention
        if fqn not in callers_of:
            dead_symbols.append(sym)

    return callers_of, callees_of, dead_symbols

# ── Phase 4: Android-specific checks ─────────────────────────────────────────

def check_android_manifest(root: Path, symbols: dict):
    issues = []
    manifest = root / "app" / "src" / "main" / "AndroidManifest.xml"
    if not manifest.exists():
        issues.append(Issue('HIGH', 'MISSING_MANIFEST', 'AndroidManifest.xml', 0,
                            "AndroidManifest.xml not found"))
        return issues

    content = manifest.read_text(errors='replace')
    src_root = root / "app" / "src" / "main" / "java" / "com" / "airi" / "assistant"

    # Find all android:name references
    names = re.findall(r'android:name="([^"]+)"', content)
    for name in names:
        if name.startswith('.'):
            # Relative name
            cls = "com.airi.assistant" + name
        elif '.' not in name:
            continue
        else:
            cls = name

        if not cls.startswith('com.airi.assistant'):
            continue

        # Convert to file path
        rel_path = cls.replace('com.airi.assistant.', '').replace('.', '/') + '.kt'
        full_path = src_root / rel_path
        simple_name = cls.split('.')[-1]

        if not full_path.exists():
            # Check by simple name
            found = any(sym.name == simple_name for sym in symbols.values())
            if not found:
                issues.append(Issue(
                    severity='HIGH', category='MANIFEST_MISSING_COMPONENT',
                    file='AndroidManifest.xml', line=0,
                    message=f"Manifest references class not found: {cls}",
                    symbol=cls
                ))

    return issues

def check_room(src_root: Path, symbols: dict):
    issues = []
    entity_pattern  = re.compile(r'@Entity\b')
    dao_pattern     = re.compile(r'@Dao\b')
    db_pattern      = re.compile(r'@Database\b')
    entities_in_db  = re.compile(r'entities\s*=\s*\[([^\]]+)\]')

    entities = set()
    daos     = set()

    for kt_file in src_root.rglob("*.kt"):
        try:
            content = kt_file.read_text(errors='replace')
        except Exception:
            continue
        if entity_pattern.search(content):
            for m in re.finditer(r'(?:data\s+)?class\s+(\w+)', content):
                entities.add(m.group(1))
        if dao_pattern.search(content):
            for m in re.finditer(r'(?:interface|abstract class)\s+(\w+)', content):
                daos.add(m.group(1))
        if db_pattern.search(content):
            for em in entities_in_db.finditer(content):
                for entity_ref in re.finditer(r'(\w+)::class', em.group(1)):
                    name = entity_ref.group(1)
                    if name not in entities:
                        issues.append(Issue(
                            severity='HIGH', category='ROOM_MISSING_ENTITY',
                            file=str(kt_file.relative_to(src_root)), line=0,
                            message=f"@Database references entity not annotated with @Entity: {name}",
                            symbol=name
                        ))
    return issues

# ── Phase 5: Gradle verification ─────────────────────────────────────────────

def check_gradle(root: Path):
    issues = []

    toml = root / "gradle" / "libs.versions.toml"
    if not toml.exists():
        issues.append(Issue('HIGH', 'GRADLE_MISSING_TOML', 'gradle/libs.versions.toml', 0,
                            "Version catalog not found"))
        return issues

    content = toml.read_text(errors='replace')

    # Extract versions
    versions = {}
    for m in re.finditer(r'^(\w+[\w-]*)\s*=\s*"([^"]+)"', content, re.MULTILINE):
        versions[m.group(1)] = m.group(2)

    # Extract version references
    version_refs = {}
    for m in re.finditer(r'version\.ref\s*=\s*"([^"]+)"', content):
        version_refs[m.group(1)] = True

    for ref in version_refs:
        if ref not in versions:
            issues.append(Issue(
                severity='HIGH', category='GRADLE_MISSING_VERSION_REF',
                file='gradle/libs.versions.toml', line=0,
                message=f"version.ref '{ref}' not defined in [versions]",
                symbol=ref
            ))

    # Check key version compatibility
    agp     = versions.get('agp', '')
    kotlin  = versions.get('kotlin', '')
    compose = versions.get('composeCompiler', '')

    if agp and kotlin:
        agp_major = int(agp.split('.')[0]) if agp else 0
        k_major   = int(kotlin.split('.')[0]) if kotlin else 0
        k_minor   = int(kotlin.split('.')[1]) if len(kotlin.split('.')) > 1 else 0
        if k_major == 1 and k_minor < 9:
            issues.append(Issue(
                severity='MEDIUM', category='GRADLE_VERSION_COMPAT',
                file='gradle/libs.versions.toml', line=0,
                message=f"Kotlin {kotlin} may be incompatible with AGP {agp}. Kotlin ≥1.9.0 recommended.",
                symbol='kotlin'
            ))

    # Check settings.gradle module inclusions
    settings = root / "settings.gradle.kts"
    if not settings.exists():
        settings = root / "settings.gradle"
    if settings.exists():
        s_content = settings.read_text(errors='replace')
        included = re.findall(r'include\("([^"]+)"\)', s_content)
        for mod in included:
            mod_path = root / mod.strip(':').replace(':', '/')
            if not mod_path.exists():
                issues.append(Issue(
                    severity='HIGH', category='GRADLE_MISSING_MODULE',
                    file=settings.name, line=0,
                    message=f"settings.gradle includes module '{mod}' but directory not found",
                    symbol=mod
                ))

    return issues

# ── Phase 6: Specific deleted-symbol verification ─────────────────────────────

DELETED_SYMBOLS = [
    "UnifiedCognitiveLoop", "PlanGenerator", "TypedPlanGraph",
    "ActionPlan", "ActionPlanExtensions", "AgentService",
    "AgentExecutionPipeline", "createDAGPlanFromLLM",
    "executeGraph", "processPercept", "PlanQualityScorer",
    "ExecutionReflector", "ReflectionReport", "PerceptionFusion",
    "BrainInput", "RecoveryManager", "SharedCognitiveBus",
    "AgentCapabilityGraph", "AgentTaskDelegator", "DurableTaskManager",
    "DurableTask", "ExecutionWatchdog", "SmartActionEngine",
    "InteractionTracker", "UILearningEngine", "CodingAgent",
    "MediaGenerationAgent", "DocumentProcessorAgent", "LocalBrowserOperator",
    "AdaptiveGraphEngine", "AgentExecutor", "BrainManager",
    "IntentEngine", "GoalExecutor", "TaskOrchestrator",
    "PlanAdaptationHints", "CognitiveResult", "StepResult",
    "GraphExecutionResult", "streamRemoteResponse", "handleToolIfNeeded",
    "buildSubAgentContext",
]

def check_deleted_symbols(src_root: Path):
    issues = []
    for sym in DELETED_SYMBOLS:
        for kt_file in src_root.rglob("*.kt"):
            rel = str(kt_file.relative_to(src_root))
            try:
                lines = kt_file.read_text(errors='replace').splitlines()
            except Exception:
                continue
            for lineno, line in enumerate(lines, 1):
                stripped = line.strip()
                # Skip comments
                if stripped.startswith('//') or stripped.startswith('*') or stripped.startswith('/*'):
                    continue
                # Skip import-commented-out lines
                if '// import' in line or '// AgentService' in line:
                    continue
                if re.search(r'\b' + re.escape(sym) + r'\b', line):
                    # Is it a definition in a new file (legitimate)?
                    is_definition = re.search(
                        r'(?:class|interface|object|fun|typealias|enum)\s+' + re.escape(sym),
                        line
                    )
                    if is_definition:
                        continue  # Legitimate redefinition
                    issues.append(Issue(
                        severity='HIGH', category='DELETED_SYMBOL_REFERENCE',
                        file=rel, line=lineno,
                        message=f"Reference to deleted symbol '{sym}' in live code",
                        symbol=sym
                    ))
    return issues

# ── Report generation ─────────────────────────────────────────────────────────

def write_report(path: Path, title: str, sections: list[tuple[str, str]]):
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, 'w') as f:
        f.write(f"# {title}\n")
        f.write(f"*Generated: {time.strftime('%Y-%m-%d %H:%M:%S')}*\n\n")
        for heading, content in sections:
            f.write(f"## {heading}\n\n")
            f.write(content)
            f.write("\n\n")

def issues_to_table(issues: list[Issue]) -> str:
    if not issues:
        return "No issues found.\n"
    rows = ["| Severity | Category | File | Line | Symbol | Message |",
            "|---|---|---|---|---|---|"]
    for i in sorted(issues, key=lambda x: ('LOW','MEDIUM','HIGH').index(x.severity) * -1):
        rows.append(f"| {i.severity} | {i.category} | `{i.file}` | {i.line} | `{i.symbol}` | {i.message} |")
    return '\n'.join(rows) + '\n'

def severity_count(issues, sev):
    return sum(1 for i in issues if i.severity == sev)

# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    print("AIRI Build Verification")
    print("=" * 60)
    print(f"Source root: {APP_SRC}")
    print(f"Reports dir: {REPORTS}")
    print()

    # Phase 1: Symbol index
    print("[1/6] Building symbol index...")
    symbols, duplicates, by_simple_name = index_symbols(APP_SRC)
    print(f"      Indexed {len(symbols)} symbols across {sum(1 for _ in APP_SRC.rglob('*.kt'))} files")

    # Phase 2: Import resolution
    print("[2/6] Collecting and resolving imports...")
    imports = collect_imports(APP_SRC)
    import_issues = resolve_imports(imports, symbols, by_simple_name)
    print(f"      Scanned {len(imports)} AIRI imports, {len(import_issues)} unresolved")

    # Phase 3: Call graph
    print("[3/6] Building call graph (this may take a moment)...")
    callers_of, callees_of, dead_syms = build_call_graph(APP_SRC, symbols, by_simple_name)
    print(f"      {len(dead_syms)} potentially dead symbols found")

    # Phase 4: Android checks
    print("[4/6] Running Android-specific checks...")
    manifest_issues = check_android_manifest(ROOT, symbols)
    room_issues     = check_room(APP_SRC, symbols)
    print(f"      Manifest: {len(manifest_issues)} issues, Room: {len(room_issues)} issues")

    # Phase 5: Gradle checks
    print("[5/6] Checking Gradle configuration...")
    gradle_issues = check_gradle(ROOT)
    print(f"      Gradle: {len(gradle_issues)} issues")

    # Phase 6: Deleted symbol references
    print("[6/6] Checking for references to deleted symbols...")
    deleted_issues = check_deleted_symbols(APP_SRC)
    print(f"      {len(deleted_issues)} live references to deleted symbols")

    print()
    print("Generating reports...")
    REPORTS.mkdir(parents=True, exist_ok=True)

    # ── REPORT 1: Compile-risk ────────────────────────────────────────────────
    high_risk = [i for i in import_issues if i.severity == 'HIGH']
    high_risk += deleted_issues
    high_risk += manifest_issues

    dup_section = ""
    if duplicates:
        dup_section = "| FQN | File 1 | File 2 |\n|---|---|---|\n"
        for fqn, f1, f2 in duplicates:
            dup_section += f"| `{fqn}` | `{f1}` | `{f2}` |\n"
    else:
        dup_section = "No duplicate symbol definitions found.\n"

    write_report(REPORTS / "report1_compile_risk.md",
        "REPORT 1 — Compile-Risk Report",
        [
            ("Summary", f"- HIGH risk issues: **{len(high_risk)}**\n"
                        f"- Unresolved imports (HIGH): **{len([i for i in import_issues if i.severity=='HIGH'])}**\n"
                        f"- Deleted symbol references in live code: **{len(deleted_issues)}**\n"
                        f"- Manifest component mismatches: **{len(manifest_issues)}**\n"
                        f"- Duplicate symbol definitions: **{len(duplicates)}**\n"),
            ("Deleted Symbol References in Live Code", issues_to_table(deleted_issues)),
            ("Unresolved Imports", issues_to_table([i for i in import_issues if i.severity=='HIGH'])),
            ("Manifest Component Issues", issues_to_table(manifest_issues)),
            ("Duplicate Symbol Definitions", dup_section),
            ("Medium/Low Import Warnings", issues_to_table([i for i in import_issues if i.severity!='HIGH'])),
        ]
    )

    # ── REPORT 2: Dependency graph ────────────────────────────────────────────
    # Top referenced symbols
    top_referenced = sorted(callers_of.items(), key=lambda x: len(x[1]), reverse=True)[:30]
    top_callers_str = "| FQN | Caller Count |\n|---|---|\n"
    for fqn, callers in top_referenced:
        top_callers_str += f"| `{fqn}` | {len(callers)} |\n"

    # Package dependency matrix
    pkg_deps = collections.defaultdict(set)
    for kt_file in APP_SRC.rglob("*.kt"):
        rel = str(kt_file.relative_to(APP_SRC))
        try:
            lines = kt_file.read_text(errors='replace').splitlines()
        except Exception:
            continue
        pkg = get_package(lines)
        for line in lines:
            m = re.match(r'^import (com\.airi\.assistant\.([\w.]+))', line.strip())
            if m:
                imported_pkg = '.'.join(m.group(1).split('.')[:-1])
                if imported_pkg != pkg and imported_pkg.startswith('com.airi.assistant'):
                    pkg_deps[pkg].add(imported_pkg)

    pkg_dep_str = "| Package | Depends On |\n|---|---|\n"
    for pkg in sorted(pkg_deps.keys()):
        deps = ", ".join(f"`{d}`" for d in sorted(pkg_deps[pkg]))
        pkg_dep_str += f"| `{pkg}` | {deps} |\n"

    write_report(REPORTS / "report2_dependency_graph.md",
        "REPORT 2 — Dependency Graph Report",
        [
            ("Symbol Index Summary", f"- Total symbols indexed: **{len(symbols)}**\n"
                                     f"- Classes: **{sum(1 for s in symbols.values() if s.kind=='class')}**\n"
                                     f"- Interfaces: **{sum(1 for s in symbols.values() if s.kind=='interface')}**\n"
                                     f"- Objects: **{sum(1 for s in symbols.values() if s.kind=='object')}**\n"
                                     f"- Enums: **{sum(1 for s in symbols.values() if s.kind=='enum')}**\n"
                                     f"- Sealed classes: **{sum(1 for s in symbols.values() if s.kind=='sealed')}**\n"
                                     f"- Top-level functions: **{sum(1 for s in symbols.values() if s.kind=='fun')}**\n"),
            ("Top 30 Most Referenced Symbols", top_callers_str),
            ("Package Dependency Matrix", pkg_dep_str),
            ("Gradle Issues", issues_to_table(gradle_issues)),
            ("Room Entity/DAO Issues", issues_to_table(room_issues)),
        ]
    )

    # ── REPORT 3: Dead code ───────────────────────────────────────────────────
    # Filter to meaningful dead code (skip private helpers, test files)
    significant_dead = [
        s for s in dead_syms
        if s.kind in ('class', 'interface', 'object', 'sealed', 'enum')
        and not s.name.startswith('_')
        and 'Test' not in s.name
        and 'Mock' not in s.name
        and 'Fake' not in s.name
    ]

    dead_str = "| FQN | Kind | File | Line |\n|---|---|---|---|\n"
    for s in sorted(significant_dead, key=lambda x: x.package)[:100]:
        dead_str += f"| `{s.fqn}` | {s.kind} | `{s.file}` | {s.line} |\n"
    if not significant_dead:
        dead_str = "No significant dead code detected by simple reachability.\n"

    write_report(REPORTS / "report3_dead_code.md",
        "REPORT 3 — Dead Code Report",
        [
            ("Summary", f"- Total symbols with 0 detected external callers: **{len(significant_dead)}**\n"
                        f"  *(Note: call graph is approximate — false positives expected)*\n"),
            ("Zero-Caller Classes/Interfaces/Objects (top 100)", dead_str),
            ("Methodology Note",
             "The call graph uses simple name matching, not type-aware resolution. "
             "A symbol with 0 detected callers may still be:\n"
             "- Called via reflection\n"
             "- Registered in XML/Manifest\n"
             "- An Android framework entry point (Activity, Service, Worker)\n"
             "- Overriding an interface method\n"
             "Use this list as a starting point, not a definitive dead-code list.\n")
        ]
    )

    # ── REPORT 4: Runtime crash risks ────────────────────────────────────────
    runtime_issues = []

    # Check for force-unwrap patterns (!!) in production code
    for kt_file in APP_SRC.rglob("*.kt"):
        rel = str(kt_file.relative_to(APP_SRC))
        if 'test' in rel.lower() or 'Test' in rel:
            continue
        try:
            lines = kt_file.read_text(errors='replace').splitlines()
        except Exception:
            continue
        for lineno, line in enumerate(lines, 1):
            stripped = line.strip()
            if stripped.startswith('//') or stripped.startswith('*'):
                continue
            # Force-unwrap on non-obvious safe types
            if '!!' in line and not re.search(r'BuildConfig\.|Looper\.|TAG\b', line):
                runtime_issues.append(Issue(
                    'LOW', 'FORCE_UNWRAP', rel, lineno,
                    f"Force-unwrap (!!) may cause NullPointerException",
                    line.strip()[:80]
                ))
            # Thread-unsafe state access patterns
            if '@Volatile' not in line and re.search(r'_\w+\.value\s*=\s*', line):
                pass  # too noisy without type info

    # Check for missing coroutine exception handling
    for kt_file in APP_SRC.rglob("*.kt"):
        rel = str(kt_file.relative_to(APP_SRC))
        try:
            content = kt_file.read_text(errors='replace')
        except Exception:
            continue
        launches = len(re.findall(r'launch\s*{', content))
        catches  = len(re.findall(r'catch\s*\(', content))
        if launches > 3 and catches == 0:
            runtime_issues.append(Issue(
                'MEDIUM', 'UNCAUGHT_COROUTINE_EXCEPTION',
                str(kt_file.relative_to(APP_SRC)), 0,
                f"File has {launches} coroutine launches but 0 catch blocks",
                kt_file.name
            ))

    write_report(REPORTS / "report4_runtime_crash_risk.md",
        "REPORT 4 — Potential Runtime Crash Report",
        [
            ("Summary", f"- Force-unwrap (!!) instances: **{severity_count(runtime_issues,'LOW')}**\n"
                        f"- Uncaught coroutine launches: **{severity_count(runtime_issues,'MEDIUM')}**\n"),
            ("Force-Unwrap Instances (sample, first 50)", issues_to_table(runtime_issues[:50])),
        ]
    )

    # ── REPORT 5: High-confidence broken references ───────────────────────────
    # These are issues where we are HIGH confidence a compile error will occur
    hc_issues = []

    # 1. Any deleted symbol referenced in non-comment live code
    hc_issues += deleted_issues

    # 2. Unresolved imports where the class was confirmed deleted
    for i in import_issues:
        if i.severity == 'HIGH':
            simple = i.symbol.split('.')[-1]
            if simple in DELETED_SYMBOLS:
                hc_issues.append(Issue(
                    'HIGH', 'BROKEN_IMPORT_DELETED_CLASS',
                    i.file, i.line,
                    f"Import references a class confirmed deleted: {i.symbol}",
                    i.symbol
                ))

    # 3. TRUE duplicate class definitions — same FQN, same class kind, different files
    #    Excludes: companion, method names (start/stop/release), nested methods
    true_dups = [
        (fqn, f1, f2) for (fqn, f1, f2) in duplicates
        if (
            not fqn.endswith('.companion') and
            not any(fqn.endswith('.' + m) for m in
                ['start','stop','release','cancel','run','execute','process',
                 'init','create','get','set','update','remove','clear','reset',
                 'connect','disconnect','destroy','pause','resume','close','open',
                 'load','unload','reload','apply','applyLocale','emit','send','receive',
                 'requestListen','requestStop','beginSpeaking','speakChunk',
                 'interruptSpeaking','getAllModels','unload','setKey','recordNonFatal',
                 'getPaywallMessage','summarize']) and
            # Only flag if both symbols are top-level class kinds (not nested methods)
            fqn.count('.') <= 4  # com.airi.assistant.package.ClassName — depth 4
        )
    ]

    for fqn, f1, f2 in true_dups:
        hc_issues.append(Issue(
            'HIGH', 'DUPLICATE_CLASS_DEFINITION', f2, 0,
            f"Duplicate top-level class '{fqn}' (also in {f1})",
            fqn
        ))

    # 4. Manifest components not found
    hc_issues += [i for i in manifest_issues if i.severity == 'HIGH']

    write_report(REPORTS / "report5_high_confidence_breakages.md",
        "REPORT 5 — High-Confidence Broken References",
        [
            ("Summary", f"**{len(hc_issues)} high-confidence compile errors detected**\n\n"
                        f"These are issues where the static analyzer has HIGH confidence "
                        f"that the Kotlin compiler will produce an error.\n"),
            ("High-Confidence Broken References", issues_to_table(hc_issues) if hc_issues
             else "**No high-confidence compile errors detected.**\n\nAll deleted symbols appear to have been properly cleaned up.\n"),
            ("Analysis Methodology",
             "A reference is marked HIGH confidence if:\n"
             "1. The referenced symbol name matches a symbol confirmed deleted from the codebase AND\n"
             "2. The reference appears in a non-comment line AND\n"
             "3. The line is not itself a definition (i.e., not creating a new class with the same name)\n")
        ]
    )

    # ── Write machine-readable JSON summary ───────────────────────────────────
    summary = {
        "timestamp": time.strftime('%Y-%m-%d %H:%M:%S'),
        "symbols_indexed": len(symbols),
        "imports_scanned": len(imports),
        "duplicates": len(duplicates),
        "unresolved_imports_high": len([i for i in import_issues if i.severity=='HIGH']),
        "unresolved_imports_medium": len([i for i in import_issues if i.severity=='MEDIUM']),
        "deleted_symbol_live_refs": len(deleted_issues),
        "manifest_issues": len(manifest_issues),
        "room_issues": len(room_issues),
        "gradle_issues": len(gradle_issues),
        "dead_symbols_approx": len(significant_dead),
        "force_unwraps": severity_count(runtime_issues, 'LOW'),
        "high_confidence_breakages": len(hc_issues),
        "verdict": "CLEAN" if len(hc_issues) == 0 else "COMPILE_ERRORS_LIKELY",
    }
    (REPORTS / "summary.json").write_text(json.dumps(summary, indent=2))

    # ── Terminal summary ──────────────────────────────────────────────────────
    print()
    print("=" * 60)
    print("VERIFICATION COMPLETE")
    print("=" * 60)
    print(f"  Symbols indexed:              {len(symbols)}")
    print(f"  Imports scanned:              {len(imports)}")
    print(f"  Duplicate definitions:        {len(duplicates)}")
    print(f"  Unresolved imports (HIGH):    {len([i for i in import_issues if i.severity=='HIGH'])}")
    print(f"  Unresolved imports (MEDIUM):  {len([i for i in import_issues if i.severity=='MEDIUM'])}")
    print(f"  Deleted symbol live refs:     {len(deleted_issues)}")
    print(f"  Manifest issues:              {len(manifest_issues)}")
    print(f"  Gradle issues:                {len(gradle_issues)}")
    print(f"  Dead symbols (approx):        {len(significant_dead)}")
    print(f"  Force-unwraps:                {severity_count(runtime_issues, 'LOW')}")
    print()
    print(f"  HIGH-CONFIDENCE BREAKAGES:    {len(hc_issues)}")
    print()
    print(f"  VERDICT: {'✅ CLEAN — no compile errors detected' if len(hc_issues)==0 else '❌ COMPILE ERRORS LIKELY — see report5'}")
    print()
    print(f"  Reports written to: {REPORTS}/")
    print()

if __name__ == "__main__":
    main()
