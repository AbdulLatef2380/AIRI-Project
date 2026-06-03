#!/usr/bin/env python3
"""Conservative reachability analyzer for the AIRI Kotlin sources.

For every top-level class/object/interface declaration, count whole-word
references to its simple name across ALL .kt files EXCLUDING its own
declaration file. Symbols with zero external references are dead-code
*candidates*. Android entry points (manifest-declared, Workers, Services,
Activities, Application, Compose screens reachable from navigation) are
excluded from the candidate list.
"""
import os, re
from collections import defaultdict

ROOT = "/vercel/share/v0-project/app/src/main/java"
MANIFEST = "/vercel/share/v0-project/app/src/main/AndroidManifest.xml"

files = []
for dp, dn, fn in os.walk(ROOT):
    for f in fn:
        if f.endswith(".kt"):
            files.append(os.path.join(dp, f))

text = {}
for p in files:
    with open(p, encoding='utf-8', errors='replace') as fh:
        text[p] = fh.read()

manifest = ""
if os.path.exists(MANIFEST):
    manifest = open(MANIFEST, encoding='utf-8', errors='replace').read()

# top-level decls (column 0, not nested) — types AND functions/vals
decl_re = re.compile(r'^(?:public |internal |private |protected |open |final |sealed |abstract |data |enum |annotation |inner |value )*\s*(class|object|interface)\s+([A-Za-z_][A-Za-z0-9_]*)', re.M)
fun_re = re.compile(r'^(?:@\w+\s*)*(?:public |internal |private |inline |suspend |operator |infix )*\s*(?:fun|val|const val|var)\s+(?:<[^>]*>\s*)?(?:[A-Za-z_][\w.<>]*\.)?([A-Za-z_][A-Za-z0-9_]*)', re.M)

decl = {}  # simple name -> (file, kind)
for p in files:
    for m in decl_re.finditer(text[p]):
        kind, name = m.group(1), m.group(2)
        decl.setdefault(name, (p, kind))

# precompute word sets per file for speed
word_re = re.compile(r'[A-Za-z_][A-Za-z0-9_]*')
words_in = {p: set(word_re.findall(text[p])) for p in files}

candidates = []
for name, (decl_file, kind) in decl.items():
    refs = 0
    ref_files = []
    for p in files:
        if p == decl_file:
            continue
        if name in words_in[p]:
            refs += 1
            ref_files.append(p)
    if refs == 0:
        # entry-point exclusions
        if name in manifest:
            continue
        candidates.append((name, kind, decl_file))

# group by file: is the ENTIRE file dead? Consider ALL top-level exports
# (types AND functions/vals). A file is only a safe-delete candidate when
# every top-level name (incl. @Composable funcs) has zero external references.
file_decls = defaultdict(list)
for p in files:
    for m in decl_re.finditer(text[p]):
        file_decls[p].append(m.group(2))
    for m in fun_re.finditer(text[p]):
        file_decls[p].append(m.group(1))

def has_external_ref(name, decl_file):
    for p in files:
        if p == decl_file:
            continue
        if name in words_in[p]:
            return True
    return False

fully_dead_files = []
for p, names in file_decls.items():
    names = [n for n in set(names) if n not in ("it", "this", "get", "set")]
    if names and all(not has_external_ref(n, p) for n in names):
        fully_dead_files.append(p)

print("=== ZERO-EXTERNAL-REF TOP-LEVEL SYMBOLS (%d) ===" % len(candidates))
for name, kind, p in sorted(candidates, key=lambda x: x[2]):
    print(f"{kind:9} {name:42} {p.replace(ROOT+'/','')}")

print("\n=== FULLY-DEAD FILES (every top-level decl has zero external refs) (%d) ===" % len(fully_dead_files))
for p in sorted(fully_dead_files):
    print("  ", p.replace(ROOT+'/',''))
