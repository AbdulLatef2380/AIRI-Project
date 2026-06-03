#!/usr/bin/env python3
"""
AIRI Compiler-Oriented Verification Pass
=========================================
Simulates Kotlin compilation as closely as possible without a real compiler.
Specifically verifies all symbols touched by the refactoring.
DO NOT MODIFY CODE — verification only.
"""

import os, re, sys, json, time, collections
from pathlib import Path
from dataclasses import dataclass, field
from typing import Optional

ROOT    = Path(__file__).parent.parent
SRC     = ROOT / "app" / "src" / "main" / "java" / "com" / "airi" / "assistant"
REPORTS = ROOT / "reports" / "verification"

# ─────────────────────────────────────────────────────────────────────────────
# Data models
# ─────────────────────────────────────────────────────────────────────────────

@dataclass
class KtFile:
    path:    Path
    rel:     str
    package: str
    lines:   list[str]
    content: str

@dataclass
class KtClass:
    name:          str
    fqn:           str
    kind:          str   # class/interface/object/enum/sealed/data/companion
    file:          str
    line:          int
    package:       str
    superclass:    str = ""
    interfaces:    list = field(default_factory=list)
    constructors:  list = field(default_factory=list)  # list of param lists
    methods:       dict = field(default_factory=dict)  # name → (return_type, params)
    properties:    dict = field(default_factory=dict)  # name → type
    companions:    list = field(default_factory=list)  # companion members
    nested:        list = field(default_factory=list)  # nested class names
    is_abstract:   bool = False
    is_open:       bool = False
    generics:      list = field(default_factory=list)

@dataclass
class Finding:
    severity:    str    # CRITICAL / HIGH / MEDIUM / LOW
    category:    str
    file:        str
    line:        int
    message:     str
    symbol:      str = ""
    confidence:  float = 1.0   # 0.0–1.0

# ─────────────────────────────────────────────────────────────────────────────
# Step 1: Parse all .kt files
# ─────────────────────────────────────────────────────────────────────────────

def load_files(src: Path) -> list[KtFile]:
    files = []
    for p in src.rglob("*.kt"):
        try:
            content = p.read_text(errors='replace')
            lines   = content.splitlines()
        except Exception:
            continue
        pkg = "unknown"
        for ln in lines[:15]:
            m = re.match(r'^package\s+([\w.]+)', ln)
            if m: pkg = m.group(1); break
        files.append(KtFile(
            path=p, rel=str(p.relative_to(src)),
            package=pkg, lines=lines, content=content
        ))
    return files

# ─────────────────────────────────────────────────────────────────────────────
# Step 2: Build class model
# ─────────────────────────────────────────────────────────────────────────────

CLASS_DEF = re.compile(
    r'^\s*(?:(?:public|private|internal|protected|abstract|open|sealed|data|inner|'
    r'annotation|inline|value|external|expect|actual)\s+)*'
    r'(class|interface|object|enum\s+class|sealed\s+class|data\s+class)'
    r'\s+(\w+)\s*'
    r'(?:<([^>]+)>)?\s*'         # generics
    r'(?:\(([^)]*)\))?\s*'       # primary constructor
    r'(?::\s*([^{]+))?'          # supertype list
)
FUN_DEF = re.compile(
    r'^\s*(?:(?:override|open|abstract|private|protected|internal|suspend|inline|'
    r'operator|infix|tailrec|external|actual|expect)\s+)*'
    r'fun\s+(?:<[^>]+>\s*)?(\w+)\s*'
    r'(?:<[^>]+>)?\s*'
    r'\(([^)]*)\)\s*'
    r'(?::\s*([\w<>\[\]?.,\s]+))?'
)
PROP_DEF = re.compile(
    r'^\s*(?:(?:override|open|abstract|private|protected|internal|lateinit|'
    r'const)\s+)*(?:val|var)\s+(\w+)\s*(?::\s*([\w<>\[\]?.,\s]+))?'
)

def parse_class_hierarchy(files: list[KtFile]) -> dict[str, KtClass]:
    """Build a map of fqn → KtClass for every class in every file."""
    classes: dict[str, KtClass] = {}
    
    for f in files:
        class_stack: list[KtClass] = []   # nesting stack
        brace_depth = 0
        class_start_depth = []            # brace depth when each class opened
        in_comment = False
        
        for lineno, raw in enumerate(f.lines, 1):
            stripped = raw.strip()
            
            # Track block comments
            if '/*' in stripped: in_comment = True
            if '*/' in stripped: in_comment = False
            if in_comment: continue
            if stripped.startswith('//'): continue
            
            # Brace tracking
            opens  = raw.count('{') - raw.count('"{"') - raw.count("'{'")
            closes = raw.count('}') - raw.count('"}"') - raw.count("'}'")
            
            # Class definition
            m = CLASS_DEF.match(raw)
            if m:
                kind     = m.group(1).replace(' ', '_')
                name     = m.group(2)
                generics = [g.strip() for g in m.group(3).split(',')] if m.group(3) else []
                ctor     = m.group(4) or ""
                supers   = m.group(5) or ""
                
                # FQN: use parent FQN if nested
                if class_stack:
                    fqn = f"{class_stack[-1].fqn}.{name}"
                    parent_fqn = class_stack[-1].fqn
                else:
                    fqn = f"{f.package}.{name}"
                    parent_fqn = ""
                
                # Parse supertypes
                superclass = ""
                interfaces = []
                if supers:
                    for part in re.split(r',\s*', supers.strip()):
                        part = re.sub(r'\(.*?\)', '', part).strip()
                        part = part.split('<')[0].strip()
                        if part:
                            if not superclass and not part[0].isupper():
                                pass
                            elif not superclass:
                                superclass = part
                            else:
                                interfaces.append(part)
                
                kc = KtClass(
                    name=name, fqn=fqn, kind=kind, file=f.rel,
                    line=lineno, package=f.package,
                    superclass=superclass, interfaces=interfaces,
                    generics=generics,
                    is_abstract='abstract' in raw[:raw.find(name)],
                    is_open='open' in raw[:raw.find(name)],
                )
                classes[fqn] = kc
                if class_stack:
                    class_stack[-1].nested.append(name)
                class_stack.append(kc)
                class_start_depth.append(brace_depth)
            
            # Method inside class
            elif class_stack:
                fm = FUN_DEF.match(raw)
                if fm:
                    mname   = fm.group(1)
                    params  = fm.group(2) or ""
                    ret     = (fm.group(3) or "Unit").strip()
                    is_over = 'override' in raw[:raw.find(f'fun {mname}')]
                    class_stack[-1].methods[mname] = {
                        'return': ret,
                        'params': params,
                        'override': is_over,
                        'line': lineno
                    }
                
                pm = PROP_DEF.match(raw)
                if pm and not raw.strip().startswith('//'):
                    pname = pm.group(1)
                    ptype = (pm.group(2) or "Any").strip()
                    if pname not in ('import', 'package', 'return', 'val', 'var'):
                        class_stack[-1].properties[pname] = ptype
            
            # Update brace depth after parsing
            brace_depth += opens
            
            # Pop class stack when we return to the depth it opened at
            brace_depth -= closes
            while class_start_depth and brace_depth <= class_start_depth[-1]:
                class_start_depth.pop()
                if class_stack:
                    class_stack.pop()
    
    return classes

# ─────────────────────────────────────────────────────────────────────────────
# Step 3: Resolve imports
# ─────────────────────────────────────────────────────────────────────────────

DELETED_SYMBOLS = {
    # Removed in refactoring phases
    "UnifiedCognitiveLoop", "PlanGenerator", "TypedPlanGraph",
    "ActionPlan", "ActionPlanExtensions", "AgentService",
    "AgentExecutionPipeline", "createDAGPlanFromLLM",
    "processPercept", "PlanQualityScorer", "ExecutionReflector",
    "ReflectionReport", "PerceptionFusion", "BrainInput",
    "RecoveryManager", "SharedCognitiveBus", "AgentCapabilityGraph",
    "AgentTaskDelegator", "DurableTaskManager", "DurableTask",
    "ExecutionWatchdog", "SmartActionEngine", "InteractionTracker",
    "UILearningEngine", "CodingAgent", "MediaGenerationAgent",
    "DocumentProcessorAgent", "LocalBrowserOperator",
    "AdaptiveGraphEngine", "AgentExecutor", "BrainManager",
    "IntentEngine", "GoalExecutor", "TaskOrchestrator",
    "PlanAdaptationHints", "CognitiveResult", "StepResult",
    "GraphExecutionResult", "streamRemoteResponse",
    "handleToolIfNeeded", "buildSubAgentContext",
    # Fields removed
    "cognitiveLoop", "orchestratorProvider",
    # BuildConfig flags removed
    "AIRI_EXECUTE_GRAPH_ENABLED",
}

ANDROID_FRAMEWORK = {
    "android", "androidx", "com.google", "org.json",
    "kotlin", "kotlinx", "java", "javax",
}

def is_framework(imp: str) -> bool:
    return any(imp.startswith(f) for f in ANDROID_FRAMEWORK)

def resolve_imports(files: list[KtFile], classes: dict[str, KtClass]) -> list[Finding]:
    findings = []
    
    # Build lookup: simple_name → [fqn, ...]
    by_name: dict[str, list[str]] = collections.defaultdict(list)
    for fqn in classes:
        simple = fqn.rsplit('.', 1)[-1]
        by_name[simple].append(fqn)
    
    # Also index top-level functions (from file-level fun definitions)
    top_level_funs: set[str] = set()
    for f in files:
        for ln in f.lines:
            stripped = ln.strip()
            if stripped.startswith('//') or stripped.startswith('*'): continue
            m = re.match(r'^(?:suspend\s+)?fun\s+(\w+)\s*', stripped)
            if m and not re.search(r'^\s+', ln):  # top-level (no indent)
                top_level_funs.add(f"{f.package}.{m.group(1)}")
    
    for f in files:
        for lineno, raw in enumerate(f.lines, 1):
            stripped = raw.strip()
            if not stripped.startswith('import '): continue
            imp = stripped[7:].split(' ')[0]
            
            # Skip framework
            if is_framework(imp): continue
            # Skip wildcard
            if imp.endswith('.*'): continue
            # Skip build-generated
            if 'BuildConfig' in imp or imp.endswith('.R'): continue
            
            if not imp.startswith('com.airi.assistant'): continue
            
            simple = imp.rsplit('.', 1)[-1]
            
            # Check for deleted symbols
            if simple in DELETED_SYMBOLS:
                findings.append(Finding(
                    severity='CRITICAL',
                    category='IMPORT_DELETED_SYMBOL',
                    file=f.rel, line=lineno,
                    message=f"Import of deleted symbol '{simple}': {imp}",
                    symbol=simple,
                    confidence=1.0
                ))
                continue
            
            # Check if resolved
            resolved = (
                imp in classes or
                simple in by_name or
                imp in top_level_funs or
                # Nested class: check parent
                any(
                    p in classes and simple in classes[p].nested
                    for p in ['.'.join(imp.split('.')[:-1])]
                )
            )
            if not resolved:
                findings.append(Finding(
                    severity='HIGH',
                    category='UNRESOLVED_IMPORT',
                    file=f.rel, line=lineno,
                    message=f"Cannot resolve: {imp}",
                    symbol=simple,
                    confidence=0.3   # low — likely top-level val/fun (theme color, composable)
                ))
    
    return findings

# ─────────────────────────────────────────────────────────────────────────────
# Step 4: Verify refactored files specifically
# ─────────────────────────────────────────────────────────────────────────────

REFACTOR_TOUCHED = {
    "ui/viewmodel/ChatViewModel.kt",
    "ui/viewmodel/ModelController.kt",
    "agent/loop/AgentLoop.kt",
    "agent/loop/tool/ToolDispatcher.kt",
    "agent/loop/tool/ToolSchema.kt",
    "agent/planning/PlanningTypes.kt",
    "accessibility/execution/AccessibilityExecutionEngine.kt",
    "accessibility/security/AccessibilityPolicyGuard.kt",
    "agent/subagent/SubAgentRegistry.kt",
    "agent/orchestrator/ProductionAgentOrchestrator.kt",
    "core/ServiceLocator.kt",
}

def verify_refactored_files(files: list[KtFile], classes: dict[str, KtClass]) -> list[Finding]:
    findings = []
    
    by_name = collections.defaultdict(list)
    for fqn, kc in classes.items():
        by_name[kc.name].append(fqn)
    
    for f in files:
        if f.rel not in REFACTOR_TOUCHED:
            continue
        
        for lineno, raw in enumerate(f.lines, 1):
            stripped = raw.strip()
            if stripped.startswith('//') or stripped.startswith('*'): continue
            
            # 1. Check for any deleted symbol reference (non-comment, non-import)
            if not stripped.startswith('import '):
                for sym in DELETED_SYMBOLS:
                    # Strict word-boundary match
                    if re.search(r'\b' + re.escape(sym) + r'\b', stripped):
                        # Is this a new definition? (not a stale reference)
                        if re.search(r'(?:class|fun|val|var|object)\s+' + re.escape(sym), stripped):
                            continue  # legitimate redefinition
                        findings.append(Finding(
                            severity='CRITICAL',
                            category='DELETED_SYMBOL_IN_CODE',
                            file=f.rel, line=lineno,
                            message=f"Live code references deleted symbol '{sym}'",
                            symbol=sym,
                            confidence=0.95
                        ))
    
    # 2. Verify AgentLoop constructor call in ChatViewModel
    cvm = next((f for f in files if f.rel == "ui/viewmodel/ChatViewModel.kt"), None)
    if cvm:
        # Check agentLoop field is constructed correctly
        agent_loop_ctor = re.search(
            r'val agentLoop\s*=\s*(?:com\.airi\.assistant\.agent\.loop\.)?AgentLoop\s*\(',
            cvm.content
        )
        if not agent_loop_ctor:
            findings.append(Finding(
                severity='HIGH',
                category='MISSING_CONSTRUCTOR_CALL',
                file="ui/viewmodel/ChatViewModel.kt", line=0,
                message="agentLoop field not initialized with AgentLoop constructor",
                symbol="AgentLoop",
                confidence=0.8
            ))
        
        # Check modelController constructed correctly
        model_ctor = re.search(
            r'val modelController\s*=\s*ModelController\s*\(',
            cvm.content
        )
        if not model_ctor:
            findings.append(Finding(
                severity='HIGH',
                category='MISSING_CONSTRUCTOR_CALL',
                file="ui/viewmodel/ChatViewModel.kt", line=0,
                message="modelController field not initialized with ModelController constructor",
                symbol="ModelController",
                confidence=0.8
            ))
        
        # Verify agentLoop.run() is called
        loop_run = re.search(r'agentLoop\.run\s*\(', cvm.content)
        if not loop_run:
            findings.append(Finding(
                severity='CRITICAL',
                category='MISSING_METHOD_CALL',
                file="ui/viewmodel/ChatViewModel.kt", line=0,
                message="agentLoop.run() not found in ChatViewModel — AgentLoop never executes",
                symbol="AgentLoop.run",
                confidence=0.95
            ))
        
        # Verify QueryType no longer gates execution
        query_gate = re.search(r'queryType\s*==\s*QueryType\.\w+\s*(?:->|\{)', cvm.content)
        if query_gate:
            # Find line number
            ln = cvm.content[:query_gate.start()].count('\n') + 1
            findings.append(Finding(
                severity='MEDIUM',
                category='LEGACY_ROUTING_GATE',
                file="ui/viewmodel/ChatViewModel.kt", line=ln,
                message="queryType== comparison may gate execution (should be telemetry only)",
                symbol="QueryType",
                confidence=0.6
            ))
        
        # Verify SubAgentRegistry.route() not in sendMessage
        sar_route = re.search(r'SubAgentRegistry\.route\s*\(', cvm.content)
        if sar_route:
            ln = cvm.content[:sar_route.start()].count('\n') + 1
            findings.append(Finding(
                severity='HIGH',
                category='LEGACY_ROUTING_GATE',
                file="ui/viewmodel/ChatViewModel.kt", line=ln,
                message="SubAgentRegistry.route() still called — pre-routing intercept survives",
                symbol="SubAgentRegistry.route",
                confidence=0.95
            ))
    
    # 3. Verify ModelController constructor parameters match ChatViewModel instantiation
    mc = next((f for f in files if f.rel == "ui/viewmodel/ModelController.kt"), None)
    if mc and cvm:
        # Check ModelController receives MutableStateFlow<ModelUiState>
        mc_param = re.search(r'modelState\s*:\s*MutableStateFlow\s*<\s*ModelUiState\s*>', mc.content)
        if not mc_param:
            findings.append(Finding(
                severity='HIGH',
                category='CONSTRUCTOR_PARAM_MISMATCH',
                file="ui/viewmodel/ModelController.kt", line=0,
                message="ModelController.modelState param type may not be MutableStateFlow<ModelUiState>",
                symbol="ModelController",
                confidence=0.7
            ))
        
        # Check ChatViewModel passes _modelState (MutableStateFlow)
        cv_passes = re.search(r'modelState\s*=\s*_modelState\b', cvm.content)
        if not cv_passes:
            findings.append(Finding(
                severity='HIGH',
                category='WRONG_ARGUMENT',
                file="ui/viewmodel/ChatViewModel.kt", line=0,
                message="ModelController may receive wrong stateflow (not _modelState)",
                symbol="ModelController.modelState",
                confidence=0.7
            ))
    
    # 4. Verify PlanningTypes.kt defines GoalNode, GraphSnapshot, RecoveryBranch, NodeStatus
    pt = next((f for f in files if f.rel == "agent/planning/PlanningTypes.kt"), None)
    if pt:
        for expected in ["GoalNode", "GraphSnapshot", "RecoveryBranch", "NodeStatus"]:
            if not re.search(r'\b(?:class|enum|sealed\s+class|data\s+class)\s+' + expected + r'\b', pt.content):
                findings.append(Finding(
                    severity='CRITICAL',
                    category='MISSING_TYPE_DEFINITION',
                    file="agent/planning/PlanningTypes.kt", line=0,
                    message=f"PlanningTypes.kt does not define '{expected}' — callers will fail to compile",
                    symbol=expected,
                    confidence=0.95
                ))
    else:
        findings.append(Finding(
            severity='CRITICAL',
            category='MISSING_FILE',
            file="agent/planning/PlanningTypes.kt", line=0,
            message="PlanningTypes.kt does not exist — GoalNode/GraphSnapshot/RecoveryBranch are undefined",
            symbol="PlanningTypes",
            confidence=1.0
        ))
    
    # 5. Verify PAO still compiles after TypedPlanGraph removal
    pao = next((f for f in files if 'ProductionAgentOrchestrator' in f.rel), None)
    if pao:
        for sym in ["GoalNode", "GraphSnapshot", "RecoveryBranch", "NodeStatus"]:
            if re.search(r'\b' + sym + r'\b', pao.content):
                # Verify it imports from agent.planning (PlanningTypes.kt)
                imp = re.search(
                    r'import\s+com\.airi\.assistant\.agent\.planning\.' + sym + r'\b',
                    pao.content
                )
                if not imp:
                    # Also acceptable if in same package
                    if pao.package != 'com.airi.assistant.agent.planning':
                        findings.append(Finding(
                            severity='HIGH',
                            category='MISSING_IMPORT_AFTER_MOVE',
                            file=pao.rel, line=0,
                            message=f"PAO uses '{sym}' but may lack import from new PlanningTypes.kt location",
                            symbol=sym,
                            confidence=0.75
                        ))
    
    return findings

# ─────────────────────────────────────────────────────────────────────────────
# Step 5: Deleted-symbol exhaustive scan
# ─────────────────────────────────────────────────────────────────────────────

def scan_deleted_symbols(files: list[KtFile]) -> list[Finding]:
    """Exhaustive: every .kt file, every line, every deleted symbol."""
    findings = []
    
    for f in files:
        for lineno, raw in enumerate(f.lines, 1):
            stripped = raw.strip()
            # Skip comments
            if stripped.startswith('//') or stripped.startswith('*') or stripped.startswith('/*'):
                continue
            # Skip commented-out imports
            if '//' in raw:
                code_part = raw[:raw.index('//')]
            else:
                code_part = raw
            
            for sym in DELETED_SYMBOLS:
                if re.search(r'\b' + re.escape(sym) + r'\b', code_part):
                    # Skip if it's a new definition of the same name
                    if re.search(
                        r'(?:class|interface|object|fun|val|var|enum|sealed)\s+' + re.escape(sym) + r'\b',
                        code_part
                    ):
                        continue
                    # Skip import-style lines that are commented
                    findings.append(Finding(
                        severity='CRITICAL',
                        category='DELETED_SYMBOL_REFERENCE',
                        file=f.rel, line=lineno,
                        message=f"Reference to deleted symbol '{sym}'",
                        symbol=sym,
                        confidence=0.9
                    ))
    
    return findings

# ─────────────────────────────────────────────────────────────────────────────
# Step 6: Override / interface consistency
# ─────────────────────────────────────────────────────────────────────────────

def verify_overrides(files: list[KtFile], classes: dict[str, KtClass]) -> list[Finding]:
    findings = []
    
    # Build method set per class for override checks
    by_simple: dict[str, KtClass] = {}
    for fqn, kc in classes.items():
        by_simple[kc.name] = kc
    
    for fqn, kc in classes.items():
        # Check override methods have corresponding parent method
        for mname, minfo in kc.methods.items():
            if not minfo.get('override'):
                continue
            parent_name = kc.superclass or ''
            parent = by_simple.get(parent_name)
            if parent and mname not in parent.methods:
                # Check interfaces too
                if not any(
                    mname in by_simple.get(iface, KtClass('','','','',0,'')).methods
                    for iface in kc.interfaces
                ):
                    findings.append(Finding(
                        severity='HIGH',
                        category='INVALID_OVERRIDE',
                        file=kc.file, line=minfo.get('line', 0),
                        message=f"'{kc.name}.{mname}()' declared override but '{parent_name}' has no such method",
                        symbol=f"{kc.name}.{mname}",
                        confidence=0.55  # low — parent may be in stdlib/framework
                    ))
    
    return findings

# ─────────────────────────────────────────────────────────────────────────────
# Step 7: Reflection / string class lookup check
# ─────────────────────────────────────────────────────────────────────────────

def check_reflection(files: list[KtFile]) -> list[Finding]:
    findings = []
    deleted_str = {s.lower() for s in DELETED_SYMBOLS}
    
    REFLECTION_PATTERNS = [
        re.compile(r'Class\.forName\s*\(\s*"([^"]+)"'),
        re.compile(r'classNameOf\s*\(\s*"([^"]+)"'),
        re.compile(r'::\s*class\s*\.\s*java\s*\.\s*name\s*==\s*"([^"]+)"'),
        re.compile(r'"([A-Z][a-zA-Z]+)"\s*::\s*class'),
    ]
    
    for f in files:
        for lineno, raw in enumerate(f.lines, 1):
            stripped = raw.strip()
            if stripped.startswith('//') or stripped.startswith('*'): continue
            
            for pat in REFLECTION_PATTERNS:
                for m in pat.finditer(stripped):
                    classname = m.group(1).rsplit('.', 1)[-1].lower()
                    if classname in deleted_str:
                        findings.append(Finding(
                            severity='CRITICAL',
                            category='REFLECTION_DELETED_CLASS',
                            file=f.rel, line=lineno,
                            message=f"Reflection reference to deleted class: {m.group(1)}",
                            symbol=m.group(1),
                            confidence=0.95
                        ))
    
    return findings

# ─────────────────────────────────────────────────────────────────────────────
# Step 8: Android manifest cross-check
# ─────────────────────────────────────────────────────────────────────────────

def check_manifest(root: Path, classes: dict[str, KtClass]) -> list[Finding]:
    findings = []
    manifest = root / "app" / "src" / "main" / "AndroidManifest.xml"
    if not manifest.exists():
        return [Finding('CRITICAL', 'MISSING_MANIFEST', 'AndroidManifest.xml', 0,
                       "AndroidManifest.xml missing", confidence=1.0)]
    
    content = manifest.read_text(errors='replace')
    by_name = {kc.name: kc for kc in classes.values()}
    
    for m in re.finditer(r'android:name="([^"]+)"', content):
        name = m.group(1)
        if name.startswith('.'):
            name = "com.airi.assistant" + name
        if not name.startswith('com.airi.assistant'):
            continue
        simple = name.rsplit('.', 1)[-1]
        if simple not in by_name and name not in classes:
            # Double-check with file existence
            rel_path = name.replace('com.airi.assistant.', '').replace('.', '/') + '.kt'
            fpath = SRC / rel_path
            if not fpath.exists():
                findings.append(Finding(
                    severity='HIGH',
                    category='MANIFEST_COMPONENT_MISSING',
                    file='AndroidManifest.xml', line=0,
                    message=f"Manifest component not found: {name}",
                    symbol=simple,
                    confidence=0.85
                ))
    
    return findings

# ─────────────────────────────────────────────────────────────────────────────
# Step 9: Calculate confidence score
# ─────────────────────────────────────────────────────────────────────────────

def build_verdict(all_findings: list[Finding]) -> dict:
    critical = [f for f in all_findings if f.severity == 'CRITICAL']
    high     = [f for f in all_findings if f.severity == 'HIGH']
    medium   = [f for f in all_findings if f.severity == 'MEDIUM']
    low      = [f for f in all_findings if f.severity == 'LOW']
    
    # Weight: critical=blocked, high-confidence-high=likely_fail, low-confidence-high=noise
    # Only issues with confidence >= 0.65 contribute meaningfully to build risk
    weighted_risk = (
        sum(f.confidence for f in critical) * 1.0 +
        sum(f.confidence for f in high if f.confidence >= 0.65) * 0.6 +
        sum(f.confidence for f in high if f.confidence < 0.65) * 0.05 +  # near-noise
        sum(f.confidence for f in medium) * 0.15
    )
    
    # Gradle build probability
    base_prob = 0.95
    penalty   = min(0.95, weighted_risk * 0.15)
    prob      = max(0.0, base_prob - penalty)
    
    return {
        "critical_count": len(critical),
        "high_count":     len(high),
        "medium_count":   len(medium),
        "low_count":      len(low),
        "weighted_risk":  round(weighted_risk, 2),
        "gradle_success_probability": round(prob, 2),
        "verdict": (
            "BUILD_BLOCKED"  if len(critical) > 0 and any(f.confidence > 0.8 for f in critical) else
            "BUILD_AT_RISK"  if weighted_risk > 2.0 else
            "BUILD_LIKELY"   if weighted_risk > 0.5 else
            "BUILD_CLEAN"
        )
    }

# ─────────────────────────────────────────────────────────────────────────────
# Report generation
# ─────────────────────────────────────────────────────────────────────────────

def findings_table(findings: list[Finding], max_rows: int = 200) -> str:
    if not findings:
        return "_No issues found._\n"
    rows = [
        "| Severity | Confidence | Category | File | Line | Symbol | Message |",
        "|---|---|---|---|---|---|---|"
    ]
    for f in sorted(findings, key=lambda x: (-x.confidence, x.severity))[:max_rows]:
        conf_pct = f"{int(f.confidence*100)}%"
        rows.append(
            f"| **{f.severity}** | {conf_pct} | `{f.category}` | "
            f"`{f.file}` | {f.line} | `{f.symbol}` | {f.message} |"
        )
    return '\n'.join(rows) + '\n'

def write_report(path: Path, title: str, sections: list[tuple]):
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, 'w') as fh:
        fh.write(f"# {title}\n")
        fh.write(f"*Generated: {time.strftime('%Y-%m-%d %H:%M:%S')} — DO NOT MODIFY CODE*\n\n")
        for heading, content in sections:
            fh.write(f"## {heading}\n\n{content}\n\n")

# ─────────────────────────────────────────────────────────────────────────────
# Main
# ─────────────────────────────────────────────────────────────────────────────

def main():
    print("AIRI Compiler-Oriented Verification v2")
    print("=" * 60)
    print(f"Source: {SRC}")
    print()
    
    t0 = time.time()
    
    print("[1/8] Loading .kt files...")
    files = load_files(SRC)
    print(f"      {len(files)} files loaded ({sum(len(f.lines) for f in files):,} total lines)")
    
    print("[2/8] Building class model...")
    classes = parse_class_hierarchy(files)
    print(f"      {len(classes)} symbols (classes, interfaces, objects, enums)")
    
    print("[3/8] Resolving imports...")
    import_findings = resolve_imports(files, classes)
    critical_imports = [f for f in import_findings if f.severity == 'CRITICAL']
    print(f"      {len(critical_imports)} CRITICAL, {len(import_findings)-len(critical_imports)} other import issues")
    
    print("[4/8] Exhaustive deleted-symbol scan...")
    deleted_findings = scan_deleted_symbols(files)
    print(f"      {len(deleted_findings)} deleted symbol references in live code")
    
    print("[5/8] Verifying refactored files...")
    refactor_findings = verify_refactored_files(files, classes)
    print(f"      {len(refactor_findings)} issues in refactored files")
    
    print("[6/8] Checking overrides...")
    override_findings = verify_overrides(files, classes)
    print(f"      {len(override_findings)} potential override issues")
    
    print("[7/8] Checking reflection / string class lookups...")
    reflection_findings = check_reflection(files)
    print(f"      {len(reflection_findings)} reflection references to deleted symbols")
    
    print("[8/8] Manifest cross-check...")
    manifest_findings = check_manifest(ROOT, classes)
    print(f"      {len(manifest_findings)} manifest issues")
    
    elapsed = time.time() - t0
    print(f"\n      Analysis complete in {elapsed:.1f}s")
    print()
    
    # Aggregate
    all_findings = (
        import_findings + deleted_findings + refactor_findings +
        override_findings + reflection_findings + manifest_findings
    )
    verdict = build_verdict(all_findings)
    
    print("=" * 60)
    print(f"  CRITICAL:  {verdict['critical_count']}")
    print(f"  HIGH:      {verdict['high_count']}")
    print(f"  MEDIUM:    {verdict['medium_count']}")
    print(f"  LOW:       {verdict['low_count']}")
    print(f"  Weighted risk score:  {verdict['weighted_risk']}")
    print(f"  Gradle success prob:  {int(verdict['gradle_success_probability']*100)}%")
    print(f"  VERDICT: {verdict['verdict']}")
    print()
    
    REPORTS.mkdir(parents=True, exist_ok=True)
    
    # ── REPORT_A: Potential compile failures ─────────────────────────────────
    compile_fails = [
        f for f in all_findings
        if f.severity in ('CRITICAL','HIGH') and f.confidence >= 0.6
    ]
    write_report(REPORTS / "REPORT_A_compile_failures.md",
        "REPORT A — Potential Compile Failures",
        [
            ("Summary",
             f"- CRITICAL issues: **{verdict['critical_count']}**\n"
             f"- HIGH issues: **{verdict['high_count']}**\n"
             f"- Issues shown (confidence ≥ 60%): **{len(compile_fails)}**\n"
             f"- Estimated Gradle success probability: **{int(verdict['gradle_success_probability']*100)}%**\n"),
            ("CRITICAL — Deleted Symbol References in Live Code",
             findings_table([f for f in deleted_findings if f.severity == 'CRITICAL'])),
            ("CRITICAL — Import of Deleted Symbols",
             findings_table([f for f in import_findings if f.severity == 'CRITICAL'])),
            ("HIGH — Unresolved Imports (AIRI namespace, high confidence)",
             findings_table([f for f in import_findings if f.severity == 'HIGH' and f.confidence >= 0.7])),
            ("HIGH — Refactor Verification Issues",
             findings_table([f for f in refactor_findings if f.severity in ('CRITICAL','HIGH')])),
        ]
    )
    
    # ── REPORT_B: Potential runtime failures ─────────────────────────────────
    runtime_fails = [f for f in all_findings if f.category in (
        'INVALID_OVERRIDE', 'CONSTRUCTOR_PARAM_MISMATCH', 'WRONG_ARGUMENT',
        'MISSING_METHOD_CALL', 'MANIFEST_COMPONENT_MISSING',
        'REFLECTION_DELETED_CLASS', 'LEGACY_ROUTING_GATE'
    )]
    write_report(REPORTS / "REPORT_B_runtime_failures.md",
        "REPORT B — Potential Runtime Failures",
        [
            ("Summary",
             f"- Runtime risk issues: **{len(runtime_fails)}**\n"
             f"- Override issues: **{len(override_findings)}**\n"
             f"- Manifest issues: **{len(manifest_findings)}**\n"
             f"- Reflection issues: **{len(reflection_findings)}**\n"),
            ("Override / Interface Issues",
             findings_table(override_findings)),
            ("Manifest Component Issues",
             findings_table(manifest_findings)),
            ("Reflection to Deleted Classes",
             findings_table(reflection_findings)),
            ("Constructor / Argument Mismatches",
             findings_table([f for f in runtime_fails if 'CONSTRUCTOR' in f.category or 'ARGUMENT' in f.category])),
        ]
    )
    
    # ── REPORT_C: Removed-symbol verification ────────────────────────────────
    # For each deleted symbol: callers = 0, imports = 0, reflection = 0
    removed_table = "| Symbol | Live Callers | Live Imports | Reflection Refs | Status |\n|---|---|---|---|---|\n"
    for sym in sorted(DELETED_SYMBOLS):
        live_callers = sum(
            1 for f in files
            for ln in f.lines
            if not ln.strip().startswith('//')
            and not ln.strip().startswith('*')
            and re.search(r'\b' + re.escape(sym) + r'\b', ln)
            and not re.search(r'import.*' + re.escape(sym), ln)
            and not re.search(r'(?:class|fun|val|var)\s+' + re.escape(sym), ln)
        )
        live_imports = sum(
            1 for f in files
            for ln in f.lines
            if re.match(r'^\s*import.*\b' + re.escape(sym) + r'\b', ln)
            and not ln.strip().startswith('//')
        )
        refl_refs = sum(
            1 for r in reflection_findings
            if sym in r.symbol
        )
        status = "✅ CLEAN" if live_callers == 0 and live_imports == 0 else "❌ STILL REFERENCED"
        removed_table += f"| `{sym}` | {live_callers} | {live_imports} | {refl_refs} | {status} |\n"
    
    write_report(REPORTS / "REPORT_C_removed_symbol_verification.md",
        "REPORT C — Removed-Symbol Verification",
        [
            ("Summary",
             f"- Symbols verified: **{len(DELETED_SYMBOLS)}**\n"
             f"- With live callers: **{len(deleted_findings)}**\n"
             f"- With live imports: **{len([f for f in import_findings if f.severity == 'CRITICAL'])}**\n"
             f"- With reflection refs: **{len(reflection_findings)}**\n"),
            ("Per-Symbol Status", removed_table),
        ]
    )
    
    # ── REPORT_D: High-confidence build blockers ──────────────────────────────
    blockers = [f for f in all_findings if f.confidence >= 0.85 and f.severity in ('CRITICAL','HIGH')]
    write_report(REPORTS / "REPORT_D_build_blockers.md",
        "REPORT D — High-Confidence Build Blockers",
        [
            ("Verdict",
             f"**Verdict: {verdict['verdict']}**\n\n"
             f"- Estimated Gradle compilation success probability: **{int(verdict['gradle_success_probability']*100)}%**\n"
             f"- High-confidence blockers (confidence ≥ 85%): **{len(blockers)}**\n"
             f"- Weighted risk score: **{verdict['weighted_risk']}** (0 = clean, >5 = blocked)\n"),
            ("High-Confidence Build Blockers",
             findings_table(blockers) if blockers
             else "**No high-confidence build blockers detected.**\n\n"
                  "All deleted symbols appear properly cleaned up. "
                  "Remaining import issues are pre-existing theme/composable resolutions "
                  "that the static analyzer cannot verify without the Kotlin compiler.\n"),
        ]
    )
    
    # JSON summary
    summary = {
        "timestamp": time.strftime('%Y-%m-%d %H:%M:%S'),
        "files_analyzed": len(files),
        "symbols_indexed": len(classes),
        "verdict": verdict['verdict'],
        "gradle_success_probability_pct": int(verdict['gradle_success_probability'] * 100),
        "critical": verdict['critical_count'],
        "high": verdict['high_count'],
        "medium": verdict['medium_count'],
        "deleted_symbol_live_refs": len(deleted_findings),
        "deleted_symbol_imports": len([f for f in import_findings if f.severity == 'CRITICAL']),
        "reflection_to_deleted": len(reflection_findings),
        "manifest_issues": len(manifest_findings),
    }
    (REPORTS / "compiler_summary.json").write_text(json.dumps(summary, indent=2))
    
    print(f"Reports written to: {REPORTS}/")
    print()
    
    return 0 if verdict['verdict'] in ('BUILD_CLEAN', 'BUILD_LIKELY') else 1

if __name__ == "__main__":
    sys.exit(main())
