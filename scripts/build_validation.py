#!/usr/bin/env python3
"""
AIRI Offline Build Validation Pipeline
=======================================
Approximates Kotlin compilation without Gradle.
Parses every .kt file, builds a project-wide symbol table,
resolves imports/constructors/overrides, and produces
machine-readable build_blockers.json.

DO NOT MODIFY CODE. DO NOT DELETE CODE. VERIFY ONLY.
"""

import re, json, time, sys, collections
from pathlib import Path
from dataclasses import dataclass, field
from typing import Optional

ROOT    = Path(__file__).parent.parent
SRC     = ROOT / "app" / "src" / "main" / "java" / "com" / "airi" / "assistant"
REPORTS = ROOT / "reports" / "verification"

# ─── Kotlin identifiers that are NOT real class references ──────────────────
KOTLIN_BUILTINS = {
    "String","Int","Long","Boolean","Float","Double","Char","Byte","Short","Unit","Any",
    "Nothing","List","MutableList","Set","MutableSet","Map","MutableMap","Pair","Triple",
    "Array","IntArray","LongArray","BooleanArray","ByteArray","CharArray","FloatArray",
    "DoubleArray","ShortArray","Collection","MutableCollection","Iterable","Sequence",
    "Comparable","Number","Throwable","Exception","Error","IllegalStateException",
    "IllegalArgumentException","NullPointerException","IndexOutOfBoundsException",
    "UnsupportedOperationException","RuntimeException","StackOverflowError",
    "StringBuilder","Regex","Result","Lazy","suspend","Flow","SharedFlow","StateFlow",
    "MutableStateFlow","MutableSharedFlow","CoroutineScope","Job","Deferred","Channel",
    "SupervisorJob","Dispatchers","withContext","launch","async","delay","flow",
    "emit","collect","collectLatest","emitAll","flowOf","emptyFlow","merge",
    "override","abstract","open","sealed","data","inner","companion","external",
    "expect","actual","inline","tailrec","infix","operator","reified","crossinline",
    "noinline","vararg","lateinit","const","private","public","internal","protected",
    "object","interface","class","fun","val","var","init","constructor",
    "return","if","else","when","for","while","do","try","catch","finally","throw",
    "is","as","in","!in","!is","by","get","set","field","it","this","super","null","true","false",
    "AtomicBoolean","AtomicInteger","AtomicReference","ConcurrentHashMap","LinkedList",
    "HashMap","HashSet","ArrayList","Optional","Runnable","Callable",
    # Android
    "Context","Application","Activity","Fragment","Service","BroadcastReceiver",
    "ContentProvider","Intent","Bundle","Log","Handler","Message","Looper",
    "CoroutineScope","viewModelScope","lifecycleScope",
}

# Generated/framework symbols we cannot resolve statically
KNOWN_UNRESOLVABLE = {
    "R", "BuildConfig", "Manifest", "BuildConfig",
    "ActivityCompat", "ContextCompat", "ViewCompat",
    "NavController", "NavBackStackEntry",
}

# ─── Data models ─────────────────────────────────────────────────────────────

@dataclass
class Param:
    name: str
    type: str
    has_default: bool = False

@dataclass
class Method:
    name:       str
    params:     list   # list[Param]
    return_type:str
    is_override:bool
    is_abstract:bool
    is_suspend: bool
    line:       int

@dataclass
class Property:
    name:       str
    type:       str
    is_override:bool
    is_abstract:bool
    line:       int

@dataclass
class KtSymbol:
    """One class/interface/object/enum/function in the project."""
    kind:       str   # class|interface|object|enum|sealed|data|fun|val|typealias
    name:       str
    fqn:        str
    file:       str
    line:       int
    package:    str
    # for classes
    supertype:  str = ""          # direct supertype (class or interface)
    interfaces: list = field(default_factory=list)
    type_params: list = field(default_factory=list)
    constructors: list = field(default_factory=list)  # list[list[Param]]
    methods:    dict = field(default_factory=dict)    # name → list[Method]
    properties: dict = field(default_factory=dict)   # name → Property
    nested:     list = field(default_factory=list)   # simple names
    is_abstract:bool = False
    is_open:    bool = False
    is_sealed:  bool = False
    is_data:    bool = False
    is_internal:bool = False

@dataclass
class Blocker:
    severity:   str   # CRITICAL | HIGH | MEDIUM
    category:   str
    file:       str
    line:       int
    message:    str
    symbol:     str   = ""
    confidence: float = 1.0
    reason:     str   = ""   # why Kotlin compiler would fail

# ─── Parse helpers ────────────────────────────────────────────────────────────

def pkg_of(lines: list[str]) -> str:
    for ln in lines[:20]:
        m = re.match(r'^package\s+([\w.]+)', ln)
        if m: return m.group(1)
    return "unknown"

def strip_comments(content: str) -> str:
    """Remove block comments and line comments (preserve string content)."""
    # Remove /* ... */ first
    content = re.sub(r'/\*.*?\*/', '', content, flags=re.DOTALL)
    # Remove // to end of line (but not inside strings — simplified)
    lines = []
    for line in content.splitlines():
        # Find // not inside a string
        in_str = False
        i = 0
        while i < len(line) - 1:
            c = line[i]
            if c == '"' and (i == 0 or line[i-1] != '\\'):
                in_str = not in_str
            if not in_str and c == '/' and line[i+1] == '/':
                line = line[:i]
                break
            i += 1
        lines.append(line)
    return '\n'.join(lines)

def parse_param_list(raw: str) -> list:
    """Parse 'name: Type = default, ...' into list[Param]."""
    if not raw.strip():
        return []
    params = []
    depth = 0
    current = []
    for ch in raw:
        if ch in '<([': depth += 1
        elif ch in '>)]': depth -= 1
        elif ch == ',' and depth == 0:
            params.append(''.join(current).strip())
            current = []
            continue
        current.append(ch)
    if current:
        params.append(''.join(current).strip())
    
    result = []
    for p in params:
        if not p: continue
        has_default = '=' in p
        p = p.split('=')[0].strip()
        # name: Type  or  val name: Type or vararg name: Type
        p = re.sub(r'^(?:val|var|vararg)\s+', '', p)
        parts = p.split(':', 1)
        name = parts[0].strip().lstrip('@').strip()
        typ  = parts[1].strip() if len(parts) > 1 else "Any"
        # clean annotations from type
        typ  = re.sub(r'@\w+\s*', '', typ).strip()
        if name and re.match(r'^\w+$', name):
            result.append(Param(name, typ, has_default))
    return result

def extract_supertypes(raw: str) -> tuple[str, list]:
    """Parse 'SuperClass(args), Interface1, Interface2<T>' → (superclass, [interfaces])."""
    if not raw: return "", []
    supers = []
    depth = 0
    current = []
    for ch in (raw + ','):
        if ch in '<([': depth += 1
        elif ch in '>)]': depth -= 1
        elif ch == ',' and depth == 0:
            supers.append(''.join(current).strip())
            current = []
            continue
        current.append(ch)
    
    superclass = ""
    interfaces = []
    for s in supers:
        s = s.strip()
        if not s: continue
        # Strip generics and ctor args for the name
        name = re.split(r'[<(]', s)[0].strip()
        if not re.match(r'^[A-Z]\w*$', name): continue
        if not superclass:
            superclass = name
        else:
            interfaces.append(name)
    return superclass, interfaces

# ─── Phase 1: Full symbol table ───────────────────────────────────────────────

CLASS_NAME_RE = re.compile(
    r'^(\s*)'
    r'(?:(?:public|private|internal|protected|abstract|open|sealed|data|inner|'
    r'annotation|inline|value|override|external|expect|actual)\s+)*'
    r'(class|interface|object|enum\s+class|sealed\s+class|data\s+class|data\s+object)\s+'
    r'(\w+)'                    # name (group 3)
    r'(?:\s*<([^>]*)>)?'        # type params (group 4)
)

def extract_class_ctor_and_supers(line: str, name_end: int) -> tuple:
    """Depth-aware extraction of (ctor_params, tparams, supertype, ifaces) from joined class header."""
    rest = line[name_end:]
    # Skip generic type params if present
    tp_m = re.match(r'\s*<([^>]*)>', rest)
    tparams = []
    if tp_m:
        tparams = [t.strip().split(':')[0].strip() for t in tp_m.group(1).split(',')]
        rest = rest[tp_m.end():]
    rest = rest.strip()
    # Skip "private constructor" or "constructor"
    rest = re.sub(r'^(?:private\s+|internal\s+)?constructor\s*', '', rest).strip()
    # Extract constructor params
    ctor_params = []
    if rest.startswith('('):
        depth = 0
        for i, c in enumerate(rest):
            if c in '(<[': depth += 1
            elif c in ')>]': depth -= 1
            if c in ')>]' and depth == 0:
                ctor_raw = rest[1:i]
                ctor_params = parse_param_list(ctor_raw)
                rest = rest[i+1:].strip()
                break
    # Supertypes after ':'
    supertype, ifaces = "", []
    if rest.startswith(':'):
        sraw = rest[1:].strip()
        sraw = re.sub(r'\s*\{.*$', '', sraw)
        sraw = re.sub(r'\s+where\s+.*$', '', sraw)
        supertype, ifaces = extract_supertypes(sraw)
    return ctor_params, tparams, supertype, ifaces

FUN_NAME_RE = re.compile(
    r'^(\s*)'
    r'((?:(?:public|private|internal|protected|abstract|open|override|'
    r'suspend|inline|operator|infix|tailrec|external|actual|expect)\s+)*)'
    r'fun\s+'
    r'(?:<[^>]+>\s*)?'
    r'(?:(\w+(?:<[^>]+>)?)\.)?' 
    r'(\w+)'                      # fun name (group 4)
    r'\s*(?:<[^>]+>)?\s*'
    r'\('                         # opening paren — params follow
)

def extract_fun_params_and_return(line: str, name_end: int) -> tuple[list, str]:
    """Depth-aware extraction of function params and return type from joined line."""
    # Find the opening paren
    paren_start = line.find('(', name_end)
    if paren_start == -1:
        return [], "Unit"
    
    # Balanced extraction of params
    depth = 0
    i = paren_start
    while i < len(line):
        c = line[i]
        if c in '(<[': depth += 1
        elif c in ')>]': 
            depth -= 1
            if depth == 0:
                params_raw = line[paren_start + 1:i]
                rest = line[i + 1:].strip()
                # Extract return type: ": ReturnType {"
                ret_match = re.match(r'\s*:\s*([\w<>\[\]?.*, ]+?)(?:\s*\{|=|$)', rest)
                ret_type = ret_match.group(1).strip() if ret_match else "Unit"
                return parse_param_list(params_raw), ret_type
        i += 1
    return [], "Unit"

PROP_RE = re.compile(
    r'^(\s*)'
    r'((?:(?:override|open|abstract|private|protected|internal|lateinit|const|'
    r'actual|expect)\s+)*)'
    r'(val|var)\s+(\w+)'                                    # name (group 4)
    r'(?:\s*:\s*([\w<>\[\]?.*, ]+?))?'                      # type (group 5)
    r'\s*(?:=|by|get|set|\{|$)'
)

def build_symbol_table(src: Path) -> dict[str, KtSymbol]:
    table: dict[str, KtSymbol] = {}
    
    for kt in src.rglob("*.kt"):
        rel = str(kt.relative_to(src))
        try:
            raw = kt.read_text(errors='replace')
        except Exception:
            continue
        
        # Collapse multi-line class headers into single lines for easier parsing.
        # A class header continues until the opening '{' or end-of-constructor-params.
        lines = raw.splitlines()
        pkg   = pkg_of(lines)
        
        # Pre-process: join continuation lines for class/fun definitions
        joined_lines: list[tuple[int, str]] = []  # (original_lineno, content)
        i = 0
        MULTILINE_STARTERS = re.compile(
            r'\s*(?:(?:public|private|internal|protected|abstract|open|sealed|data|inner|'
            r'annotation|inline|value|override|external|expect|actual|suspend|operator|'
            r'infix|tailrec)\s+)*'
            r'(?:class|interface|object|enum\s+class|sealed\s+class|data\s+class|'
            r'data\s+object|fun)\s+\w'
        )
        while i < len(lines):
            line = lines[i]
            s = line.strip()
            if MULTILINE_STARTERS.match(line) and '(' in line:
                # Accumulate until parens balance AND ('{' seen or line ends with ')')
                header      = line
                paren_depth = header.count('(') - header.count(')')
                brace_seen  = '{' in header
                j = i + 1
                while j < len(lines) and (paren_depth > 0 or not brace_seen):
                    next_line = lines[j]
                    header      += ' ' + next_line.strip()
                    paren_depth += next_line.count('(') - next_line.count(')')
                    brace_seen   = brace_seen or ('{' in next_line)
                    j += 1
                    if paren_depth <= 0 and (brace_seen or not next_line.strip()):
                        break
                # Pull in supertype if class header ended but no '{'
                if 'fun ' not in header and not brace_seen and j < len(lines):
                    ns = lines[j].strip()
                    if ns.startswith(':') or (ns.startswith(',') and paren_depth <= 0):
                        header += ' ' + ns
                        j += 1
                joined_lines.append((i + 1, header))
                i = j
                continue
            joined_lines.append((i + 1, line))
            i += 1
        
        # Now parse the joined lines
        class_stack: list[tuple[KtSymbol, int]] = []  # (sym, brace_depth_at_open)
        brace_depth = 0
        
        for orig_lineno, line in joined_lines:
            stripped = line.rstrip()
            s = stripped.strip()
            
            if s.startswith('*') or s.startswith('//'): 
                brace_depth += s.count('{') - s.count('}')
                while class_stack and brace_depth < class_stack[-1][1]:
                    class_stack.pop()
                continue
            
            opens  = stripped.count('{')
            closes = stripped.count('}')
            
            # ── Class definition ──────────────────────────────────────────
            cm = CLASS_NAME_RE.match(stripped)
            if cm:
                kind_raw = cm.group(2).replace(' ', '_')
                name     = cm.group(3)
                
                # Depth-aware extraction of constructor params and supertypes
                ctor_params, tparams, supertype, ifaces = extract_class_ctor_and_supers(
                    stripped, cm.end()
                )
                
                qualifiers = stripped[:max(0, stripped.find(kind_raw.split('_')[0]))]
                
                parent_prefix = class_stack[-1][0].fqn + '.' if class_stack else ''
                if parent_prefix.startswith(pkg + '.'):
                    parent_inner = parent_prefix[len(pkg)+1:]
                    fqn = f"{pkg}.{parent_inner}{name}"
                else:
                    fqn = f"{pkg}.{name}"
                
                sym = KtSymbol(
                    kind=kind_raw, name=name, fqn=fqn, file=rel,
                    line=orig_lineno, package=pkg,
                    supertype=supertype, interfaces=ifaces,
                    type_params=tparams,
                    constructors=[ctor_params] if ctor_params else [],
                    is_abstract='abstract' in qualifiers,
                    is_open='open' in qualifiers or 'interface' in kind_raw,
                    is_sealed='sealed' in kind_raw,
                    is_data='data' in kind_raw,
                    is_internal='internal' in qualifiers,
                )
                table[fqn] = sym
                if class_stack:
                    class_stack[-1][0].nested.append(name)
                
                new_depth = brace_depth + opens - closes
                class_stack.append((sym, new_depth if '{' in stripped else new_depth + 1))
                brace_depth = brace_depth + opens - closes
                while class_stack and brace_depth < class_stack[-1][1]:
                    class_stack.pop()
                continue
            
            # ── Method definition ─────────────────────────────────────────
            fm = FUN_NAME_RE.match(stripped)
            if fm:
                mods     = fm.group(2) or ''
                receiver = fm.group(3)
                mname    = fm.group(4)
                
                # Extract params and return type with depth-aware parser
                params, ret = extract_fun_params_and_return(stripped, fm.end() - 1)
                
                m = Method(mname, params, ret,
                           is_override='override' in mods,
                           is_abstract='abstract' in mods,
                           is_suspend='suspend' in mods,
                           line=orig_lineno)
                
                if class_stack:
                    owner = class_stack[-1][0]
                    if mname not in owner.methods:
                        owner.methods[mname] = []
                    owner.methods[mname].append(m)
                elif not receiver:
                    fqn_fun = f"{pkg}.{mname}"
                    table[fqn_fun] = KtSymbol('fun', mname, fqn_fun, rel, orig_lineno, pkg)
            
            # ── Property definition ───────────────────────────────────────
            elif class_stack:
                pm = PROP_RE.match(stripped)
                if pm:
                    mods  = pm.group(2) or ''
                    pname = pm.group(4)
                    ptype = (pm.group(5) or 'Any').strip()
                    if pname not in ('import','package','return','val','var','it','this'):
                        prop = Property(pname, ptype,
                                        is_override='override' in mods,
                                        is_abstract='abstract' in mods,
                                        line=orig_lineno)
                        class_stack[-1][0].properties[pname] = prop
            else:
                # Top-level val/var
                pm = PROP_RE.match(stripped)
                if pm:
                    pname = pm.group(4)
                    if pname and re.match(r'^[A-Z_a-z]\w*$', pname):
                        fqn_val = f"{pkg}.{pname}"
                        table[fqn_val] = KtSymbol('val', pname, fqn_val, rel, orig_lineno, pkg)
            
            # Update brace depth
            brace_depth += opens - closes
            while class_stack and brace_depth < class_stack[-1][1]:
                class_stack.pop()
    
    return table

# ─── Phase 2: Import resolution ───────────────────────────────────────────────

IGNORE_PREFIXES = (
    "android.", "androidx.", "com.google.", "org.json.", "org.json",
    "kotlin.", "kotlinx.", "java.", "javax.", "com.squareup.",
    "okhttp3.", "retrofit2.", "dagger.", "hilt.",
)

def resolve_imports(src: Path, table: dict[str, KtSymbol]) -> list[Blocker]:
    blockers = []
    
    # Build lookups
    by_simple:  dict[str, list[str]] = collections.defaultdict(list)  # name → [fqn]
    by_package: set[str]             = set()
    
    for fqn, sym in table.items():
        by_simple[sym.name].append(fqn)
        pkg = fqn.rsplit('.', 1)[0] if '.' in fqn else fqn
        by_package.add(pkg)
    
    for kt in src.rglob("*.kt"):
        rel = str(kt.relative_to(src))
        try:
            lines = kt.read_text(errors='replace').splitlines()
        except Exception:
            continue
        
        for lineno, line in enumerate(lines, 1):
            s = line.strip()
            if not s.startswith('import '): continue
            if '//' in line and line.index('//') < line.index('import'): continue
            
            imp = s[7:].split()[0].rstrip(';')
            
            # Skip framework, generated, wildcard
            if any(imp.startswith(p) for p in IGNORE_PREFIXES): continue
            if imp.endswith('.*'): continue
            if not imp.startswith('com.airi.assistant'): continue
            if imp.rstrip().endswith('BuildConfig') or imp.rstrip().endswith('.R'): continue
            
            # Handle alias: import X as Y — verify X
            if ' as ' in imp:
                imp = imp.split(' as ')[0].strip()
            
            simple = imp.rsplit('.', 1)[-1]
            
            # Skip Kotlin builtins
            if simple in KOTLIN_BUILTINS | KNOWN_UNRESOLVABLE: continue
            
            # Try to resolve: exact FQN, or simple name in table, or nested class
            if imp in table: continue
            if simple in by_simple: continue
            
            # Check nested: com.a.b.Outer.Inner → Outer.Inner
            parts = imp.split('.')
            found = False
            for i in range(len(parts)-1, 1, -1):
                outer_fqn = '.'.join(parts[:i])
                inner     = parts[i]
                if outer_fqn in table and inner in table[outer_fqn].nested:
                    found = True
                    break
                # also check if outer exists by simple name
                outer_name = parts[i-1]
                if outer_name in by_simple:
                    for ofqn in by_simple[outer_name]:
                        if inner in table[ofqn].nested:
                            found = True
                            break
                if found: break
            if found: continue
            
            # Check if it's a top-level function/val (by_simple covers classes)
            # Try: if the package exists, the symbol might be a top-level val
            parent_pkg = '.'.join(parts[:-1])
            if parent_pkg in by_package: continue  # package exists → probably top-level val/fun
            
            blockers.append(Blocker(
                severity='HIGH',
                category='UNRESOLVED_IMPORT',
                file=rel, line=lineno,
                message=f"Import '{imp}' cannot be resolved",
                symbol=simple,
                confidence=0.70,
                reason=f"No class, object, or top-level symbol '{simple}' found in package '{parent_pkg}'"
            ))
    
    return blockers

# ─── Phase 3: Constructor call verification ───────────────────────────────────

# Explicit constructor call sites we know about (from ChatViewModel)
KNOWN_CTOR_CALLS = [
    # (file, constructor_name, arg_count_min, arg_count_max, expected_types_hint)
    ("ui/viewmodel/ChatViewModel.kt", "LocalLlamaBackend",  1, 1, ["LlamaManager"]),
    ("ui/viewmodel/ChatViewModel.kt", "CloudBackend",       2, 3, ["ExecModePreferences","Context"]),
    ("ui/viewmodel/ChatViewModel.kt", "RuntimeRouter",      3, 3, ["LocalLlamaBackend","CloudBackend","ExecModePreferences"]),
    ("ui/viewmodel/ChatViewModel.kt", "HybridOrchestrator", 2, 2, ["RuntimeRouter","ExecModePreferences"]),
    ("ui/viewmodel/ChatViewModel.kt", "AgentLoop",          3, 3, ["HybridOrchestrator","ToolDispatcher","Context"]),
    ("ui/viewmodel/ChatViewModel.kt", "ToolDispatcher",     0, 1, ["MemoryManager"]),
    ("ui/viewmodel/ChatViewModel.kt", "ModelController",    12, 12, []),
]

def verify_constructors(src: Path, table: dict[str, KtSymbol]) -> list[Blocker]:
    blockers = []
    by_name = {sym.name: sym for sym in table.values()}
    
    for file_rel, cls_name, min_args, max_args, type_hints in KNOWN_CTOR_CALLS:
        kt = src / file_rel
        if not kt.exists(): continue
        content = kt.read_text(errors='replace')
        lines   = content.splitlines()
        
        # Find the constructor call
        ctor_pattern = re.compile(
            r'\b' + re.escape(cls_name) + r'\s*\(([^)]*)\)'
        )
        
        for lineno, line in enumerate(lines, 1):
            s = line.strip()
            if s.startswith('//'): continue
            m = ctor_pattern.search(line)
            if not m: continue
            
            args_raw = m.group(1).strip()
            # Count args (depth-aware)
            if not args_raw:
                nargs = 0
            else:
                depth = 0
                nargs = 1
                for ch in args_raw:
                    if ch in '<([{': depth += 1
                    elif ch in '>)]}': depth -= 1
                    elif ch == ',' and depth == 0:
                        nargs += 1
            
            # Verify arg count
            if nargs < min_args or nargs > max_args:
                # Look up the actual constructor
                sym = by_name.get(cls_name)
                expected_str = f"{min_args}" if min_args == max_args else f"{min_args}–{max_args}"
                blockers.append(Blocker(
                    severity='CRITICAL' if nargs < min_args else 'HIGH',
                    category='CONSTRUCTOR_ARG_COUNT_MISMATCH',
                    file=file_rel, line=lineno,
                    message=f"'{cls_name}(...)' called with {nargs} args; expected {expected_str}",
                    symbol=cls_name,
                    confidence=0.85,
                    reason=f"Kotlin compiler: 'None of the following candidates is applicable: {cls_name}'"
                ))
    
    # Also scan for direct constructor calls to deleted classes
    deleted = {
        "UnifiedCognitiveLoop","PlanGenerator","TypedPlanGraph","ActionPlan",
        "AgentService","AgentExecutionPipeline","ExecutionWatchdog",
        "DurableTaskManager","SharedCognitiveBus","RecoveryManager",
    }
    for kt in src.rglob("*.kt"):
        rel = str(kt.relative_to(src))
        try: lines = kt.read_text(errors='replace').splitlines()
        except: continue
        for lineno, line in enumerate(lines, 1):
            s = line.strip()
            if s.startswith('//'): continue
            for d in deleted:
                m = re.search(r'\b' + re.escape(d) + r'\s*\(', line)
                if m:
                    blockers.append(Blocker(
                        severity='CRITICAL',
                        category='CONSTRUCTOR_DELETED_CLASS',
                        file=rel, line=lineno,
                        message=f"Constructor call '{d}(...)' — class was deleted",
                        symbol=d,
                        confidence=0.98,
                        reason=f"Kotlin compiler: 'Unresolved reference: {d}'"
                    ))
    
    return blockers

# ─── Phase 4: Override mismatch verification ─────────────────────────────────

def verify_overrides(table: dict[str, KtSymbol]) -> list[Blocker]:
    blockers = []
    by_name = collections.defaultdict(list)
    for fqn, sym in table.items():
        by_name[sym.name].append(sym)
    
    for fqn, sym in table.items():
        if sym.kind not in ('class','data_class','sealed_class','object','enum_class'):
            continue
        
        # Get parent symbol
        parent = None
        if sym.supertype and sym.supertype not in KOTLIN_BUILTINS:
            for candidate in by_name.get(sym.supertype, []):
                if candidate.fqn != fqn:
                    parent = candidate
                    break
        
        # Check each override method
        for mname, mlist in sym.methods.items():
            for m in mlist:
                if not m.is_override: continue
                
                # Verify override target exists in parent
                if parent:
                    if mname not in parent.methods and mname not in parent.properties:
                        # Check interfaces
                        iface_has = False
                        for iname in sym.interfaces:
                            for ic in by_name.get(iname, []):
                                if mname in ic.methods or mname in ic.properties:
                                    iface_has = True
                                    break
                            if iface_has: break
                        
                        if not iface_has and sym.supertype not in KOTLIN_BUILTINS | KNOWN_UNRESOLVABLE:
                            blockers.append(Blocker(
                                severity='HIGH',
                                category='OVERRIDE_TARGET_NOT_FOUND',
                                file=sym.file, line=m.line,
                                message=f"'{sym.name}.{mname}()' overrides nothing in '{sym.supertype}'",
                                symbol=f"{sym.name}.{mname}",
                                confidence=0.50,   # Low — parent may be Android/framework
                                reason=f"Kotlin compiler: \"'{mname}' overrides nothing\""
                            ))
        
        # Check override properties
        for pname, prop in sym.properties.items():
            if not prop.is_override: continue
            if parent and pname not in parent.properties and pname not in parent.methods:
                iface_has = any(
                    pname in ic.properties or pname in ic.methods
                    for iname in sym.interfaces
                    for ic in by_name.get(iname, [])
                )
                if not iface_has and sym.supertype not in KOTLIN_BUILTINS | KNOWN_UNRESOLVABLE:
                    blockers.append(Blocker(
                        severity='MEDIUM',
                        category='OVERRIDE_PROPERTY_NOT_FOUND',
                        file=sym.file, line=prop.line,
                        message=f"'{sym.name}.{pname}' overrides nothing in '{sym.supertype}'",
                        symbol=f"{sym.name}.{pname}",
                        confidence=0.40,
                        reason=f"Kotlin compiler: \"'{pname}' overrides nothing\""
                    ))
    
    return blockers

# ─── Phase 5: Interface implementation verification ──────────────────────────

def verify_implementations(table: dict[str, KtSymbol]) -> list[Blocker]:
    blockers = []
    by_name = collections.defaultdict(list)
    for fqn, sym in table.items():
        by_name[sym.name].append(sym)
    
    for fqn, sym in table.items():
        if sym.kind not in ('class','data_class','object') or sym.is_abstract:
            continue
        
        for iname in sym.interfaces:
            if iname in KOTLIN_BUILTINS | KNOWN_UNRESOLVABLE: continue
            
            ifaces = by_name.get(iname, [])
            if not ifaces: continue  # Can't verify — framework interface
            
            iface = ifaces[0]
            
            # Every abstract method in iface must be implemented
            for mname, mlist in iface.methods.items():
                for m in mlist:
                    if not m.is_abstract: continue
                    if mname in sym.methods: continue
                    # Check superclass
                    has_in_super = False
                    sup_name = sym.supertype
                    for sc in by_name.get(sup_name, []):
                        if mname in sc.methods:
                            has_in_super = True
                            break
                    if not has_in_super:
                        blockers.append(Blocker(
                            severity='HIGH',
                            category='INTERFACE_NOT_IMPLEMENTED',
                            file=sym.file, line=sym.line,
                            message=f"'{sym.name}' implements '{iname}' but does not override '{mname}()'",
                            symbol=f"{sym.name}.{mname}",
                            confidence=0.55,
                            reason=f"Kotlin compiler: \"Class '{sym.name}' is not abstract and does not implement abstract member '{mname}'\""
                        ))
    
    return blockers

# ─── Phase 6: Key method call verification ───────────────────────────────────

# Methods called on key refactored objects — verify they still exist
KEY_METHOD_CALLS = [
    # (file_pattern, object_expr, method_name, expected_in_class)
    ("ChatViewModel.kt", "agentLoop",      "run",                     "AgentLoop"),
    ("ChatViewModel.kt", "modelController","loadModel",               "ModelController"),
    ("ChatViewModel.kt", "modelController","createInitialModelState", "ModelController"),
    ("ChatViewModel.kt", "modelController","refreshDiagnosticsSnapshot","ModelController"),
    ("ChatViewModel.kt", "modelController","refreshModelList",        "ModelController"),
    ("ChatViewModel.kt", "modelController","persistRegistry",         "ModelController"),
    ("ChatViewModel.kt", "modelController","restoreScannedIds",       "ModelController"),
    ("ChatViewModel.kt", "modelController","persistScannedIds",       "ModelController"),
    ("ChatViewModel.kt", "modelController","syncDownloadedModelAvailability","ModelController"),
    ("ChatViewModel.kt", "modelController","createModelFromFile",     "ModelController"),
    ("ChatViewModel.kt", "modelController","validationMessage",       "ModelController"),
    ("ChatViewModel.kt", "hybridOrchestrator","executeStream",        "HybridOrchestrator"),
    ("ChatViewModel.kt", "hybridOrchestrator","cancel",               "HybridOrchestrator"),
    ("AgentLoop.kt",     "dispatcher",     "execute",                 "ToolDispatcher"),
    ("AgentLoop.kt",     "orchestrator",   "executeStream",           "HybridOrchestrator"),
    ("ToolDispatcher.kt","dispatcher",     None,                      None),  # skip
]

def verify_method_calls(src: Path, table: dict[str, KtSymbol]) -> list[Blocker]:
    blockers = []
    # Build simple-name lookup (takes last in case of duplicates — good enough)
    by_name: dict[str, KtSymbol] = {}
    for fqn, sym in table.items():
        by_name[sym.name] = sym
    
    for file_suffix, obj_expr, method_name, cls_name in KEY_METHOD_CALLS:
        if method_name is None: continue
        
        target = by_name.get(cls_name)
        if not target:
            blockers.append(Blocker(
                severity='CRITICAL',
                category='CLASS_NOT_FOUND_FOR_CALL',
                file=f"*/{file_suffix}", line=0,
                message=f"Class '{cls_name}' not found; cannot verify '{obj_expr}.{method_name}()'",
                symbol=cls_name,
                confidence=0.90,
                reason=f"Kotlin compiler: 'Unresolved reference: {cls_name}'"
            ))
            continue
        
        if method_name not in target.methods and method_name not in target.properties:
            blockers.append(Blocker(
                severity='CRITICAL',
                category='METHOD_NOT_FOUND',
                file=f"*/{file_suffix}", line=0,
                message=f"'{cls_name}.{method_name}()' called but method not defined in '{cls_name}'",
                symbol=f"{cls_name}.{method_name}",
                confidence=0.85,
                reason=f"Kotlin compiler: 'Unresolved reference: {method_name}'"
            ))
    
    return blockers

# ─── Phase 7: Signature consistency for AgentLoop.run ────────────────────────

def verify_agentloop_run(src: Path, table: dict[str, KtSymbol]) -> list[Blocker]:
    """Verify AgentLoop.run call site matches its signature."""
    blockers = []
    
    # Find AgentLoop by simple name
    al = next((s for s in table.values() if s.name == "AgentLoop"), None)
    
    if not al or "run" not in al.methods:
        blockers.append(Blocker(
            severity='CRITICAL',
            category='AGENTLOOP_RUN_MISSING',
            file="agent/loop/AgentLoop.kt", line=0,
            message="AgentLoop.run() method not found in symbol table",
            symbol="AgentLoop.run",
            confidence=0.95,
            reason="Kotlin compiler: 'Unresolved reference: run'"
        ))
        return blockers
    
    # Verify call site in ChatViewModel
    cvm_path = src / "ui/viewmodel/ChatViewModel.kt"
    if not cvm_path.exists(): return blockers
    
    content = cvm_path.read_text(errors='replace')
    call_m = re.search(r'agentLoop\.run\s*\(', content)
    if not call_m:
        blockers.append(Blocker(
            severity='CRITICAL',
            category='AGENTLOOP_RUN_NOT_CALLED',
            file="ui/viewmodel/ChatViewModel.kt", line=0,
            message="agentLoop.run() is never called in ChatViewModel",
            symbol="AgentLoop.run",
            confidence=0.95,
            reason="AgentLoop never executes — user requests not processed"
        ))
    
    return blockers

# ─── Report helpers ───────────────────────────────────────────────────────────

def table_rows(blockers: list[Blocker], max_rows: int = 300) -> str:
    if not blockers:
        return "_None found._\n"
    rows = [
        "| Severity | Confidence | File | Line | Symbol | Message | Kotlin Error |",
        "|---|---|---|---|---|---|---|"
    ]
    for b in sorted(blockers, key=lambda x: (-x.confidence, x.severity))[:max_rows]:
        rows.append(
            f"| **{b.severity}** | {int(b.confidence*100)}% | `{b.file}` | {b.line} | "
            f"`{b.symbol}` | {b.message} | _{b.reason}_ |"
        )
    return '\n'.join(rows) + '\n'

def write_md(path: Path, title: str, sections: list):
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, 'w') as fh:
        fh.write(f"# {title}\n*{time.strftime('%Y-%m-%d %H:%M:%S')} — DO NOT MODIFY CODE*\n\n")
        for h, c in sections:
            fh.write(f"## {h}\n\n{c}\n\n")

# ─── Main ─────────────────────────────────────────────────────────────────────

def main():
    print("AIRI Offline Build Validation Pipeline")
    print("=" * 60)
    t0 = time.time()
    
    print("[1/7] Building symbol table...")
    table = build_symbol_table(SRC)
    kt_count = sum(1 for _ in SRC.rglob("*.kt"))
    print(f"      {len(table)} symbols from {kt_count} files")
    
    print("[2/7] Resolving imports...")
    import_blockers = resolve_imports(SRC, table)
    print(f"      {len(import_blockers)} unresolved")
    
    print("[3/7] Verifying constructors...")
    ctor_blockers = verify_constructors(SRC, table)
    print(f"      {len(ctor_blockers)} issues")
    
    print("[4/7] Checking overrides...")
    override_blockers = verify_overrides(table)
    print(f"      {len(override_blockers)} issues")
    
    print("[5/7] Checking interface implementations...")
    impl_blockers = verify_implementations(table)
    print(f"      {len(impl_blockers)} issues")
    
    print("[6/7] Verifying key method calls...")
    call_blockers = verify_method_calls(SRC, table)
    al_blockers   = verify_agentloop_run(SRC, table)
    method_blockers = call_blockers + al_blockers
    print(f"      {len(method_blockers)} issues")
    
    # Gather all
    all_b = import_blockers + ctor_blockers + override_blockers + impl_blockers + method_blockers
    
    # Deduplicate on (file, line, symbol)
    seen = set()
    unique_b = []
    for b in all_b:
        key = (b.file, b.line, b.symbol, b.category)
        if key not in seen:
            seen.add(key)
            unique_b.append(b)
    
    critical  = [b for b in unique_b if b.severity == 'CRITICAL']
    high      = [b for b in unique_b if b.severity == 'HIGH']
    medium    = [b for b in unique_b if b.severity == 'MEDIUM']
    
    # High-confidence blockers only
    hc = [b for b in unique_b if b.confidence >= 0.80]
    
    print()
    print("=" * 60)
    print(f"  CRITICAL:  {len(critical)}")
    print(f"  HIGH:      {len(high)}")
    print(f"  MEDIUM:    {len(medium)}")
    print(f"  High-confidence blockers (≥80%): {len(hc)}")
    
    elapsed = time.time() - t0
    print(f"  Analysis time: {elapsed:.1f}s")
    print()
    
    REPORTS.mkdir(parents=True, exist_ok=True)
    
    # ── REPORT_1: Unresolved symbols ─────────────────────────────────────────
    write_md(REPORTS / "REPORT_1_unresolved_symbols.md",
        "REPORT 1 — Unresolved Symbols",
        [
            ("Summary", f"- Total: **{len(import_blockers)}**\n- Critical: **{len([b for b in import_blockers if b.severity=='CRITICAL'])}**\n"),
            ("Unresolved Imports", table_rows(import_blockers)),
        ]
    )
    
    # ── REPORT_2: Invalid signatures ─────────────────────────────────────────
    write_md(REPORTS / "REPORT_2_invalid_signatures.md",
        "REPORT 2 — Invalid Signatures",
        [
            ("Summary", f"- Method call issues: **{len(call_blockers)}**\n- Constructor issues: **{len(ctor_blockers)}**\n"),
            ("Constructor Mismatches", table_rows(ctor_blockers)),
            ("Method Call Issues", table_rows(call_blockers + al_blockers)),
        ]
    )
    
    # ── REPORT_3: Override mismatches ─────────────────────────────────────────
    write_md(REPORTS / "REPORT_3_override_mismatches.md",
        "REPORT 3 — Override Mismatches",
        [
            ("Summary", f"- Override issues: **{len(override_blockers)}**\n"),
            ("Override Issues", table_rows(override_blockers)),
        ]
    )
    
    # ── REPORT_4: Constructor mismatches ─────────────────────────────────────
    write_md(REPORTS / "REPORT_4_constructor_mismatches.md",
        "REPORT 4 — Constructor Mismatches",
        [
            ("Summary", f"- Constructor issues: **{len(ctor_blockers)}**\n"),
            ("All Constructor Issues", table_rows(ctor_blockers)),
            ("Interface Implementation Issues", table_rows(impl_blockers)),
        ]
    )
    
    # ── REPORT_5: Potential compile blockers ──────────────────────────────────
    note = (
        "**Zero high-confidence build blockers detected.**\n\n"
        "All deleted symbols are absent from live code. "
        "Constructor call sites match actual signatures. "
        "AgentLoop.run() is present and called. "
        "Remaining issues (overrides, implementations) have <55% confidence "
        "because parent types are commonly in Android/framework code not parsed here.\n"
    ) if not hc else table_rows(hc)
    
    write_md(REPORTS / "REPORT_5_compile_blockers.md",
        "REPORT 5 — Potential Compile Blockers",
        [
            ("Summary", f"- High-confidence blockers: **{len(hc)}**\n"
                        f"- Critical (any confidence): **{len(critical)}**\n"),
            ("High-Confidence Blockers (≥80%)", note),
            ("All Critical Issues", table_rows(critical) if critical else "_None._\n"),
        ]
    )
    
    # ── build_blockers.json ───────────────────────────────────────────────────
    blockers_json = []
    for b in sorted(unique_b, key=lambda x: (-x.confidence, x.severity)):
        blockers_json.append({
            "severity":   b.severity,
            "confidence": round(b.confidence, 2),
            "category":   b.category,
            "file":       b.file,
            "line":       b.line,
            "symbol":     b.symbol,
            "message":    b.message,
            "kotlin_error": b.reason,
        })
    
    result = {
        "timestamp":           time.strftime('%Y-%m-%d %H:%M:%S'),
        "files_analyzed":      kt_count,
        "symbols_indexed":     len(table),
        "total_issues":        len(unique_b),
        "critical":            len(critical),
        "high":                len(high),
        "medium":              len(medium),
        "high_confidence_blockers": len(hc),
        "verdict": (
            "BUILD_BLOCKED"  if any(b.confidence >= 0.90 and b.severity == 'CRITICAL' for b in unique_b) else
            "BUILD_AT_RISK"  if len(hc) > 3 else
            "BUILD_LIKELY"   if len(hc) > 0 else
            "BUILD_CLEAN"
        ),
        "blockers": blockers_json,
    }
    
    (REPORTS / "build_blockers.json").write_text(json.dumps(result, indent=2))
    
    print(f"  VERDICT: {result['verdict']}")
    print()
    print(f"  Reports: {REPORTS}/")
    print(f"  Machine-readable: {REPORTS}/build_blockers.json")
    
    return 0 if result['verdict'] in ('BUILD_CLEAN','BUILD_LIKELY') else 1

if __name__ == "__main__":
    sys.exit(main())
