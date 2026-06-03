#!/usr/bin/env python3
import os, re
from collections import defaultdict

ROOT = "/vercel/share/v0-project/app/src/main/java"
files = []
for dp, dn, fn in os.walk(ROOT):
    for f in fn:
        if f.endswith(".kt"):
            files.append(os.path.join(dp, f))

fqn_decl = defaultdict(list)
pkg_of = {}
decl_re = re.compile(r'^\s*(?:public |internal |private |protected |open |final |sealed |abstract |data |enum |annotation |inner |value )*\s*(class|object|interface)\s+([A-Za-z_][A-Za-z0-9_]*)')
pkg_re = re.compile(r'^\s*package\s+([\w.]+)')
import_re = re.compile(r'^\s*import\s+([\w.]+)(?:\s+as\s+\w+)?\s*$')

for path in files:
    with open(path, encoding='utf-8', errors='replace') as fh:
        lines = fh.readlines()
    pkg = ""
    for ln in lines:
        m = pkg_re.match(ln)
        if m:
            pkg = m.group(1); break
    pkg_of[path] = pkg
    for ln in lines:
        m = decl_re.match(ln)
        if m:
            fqn_decl[pkg + "." + m.group(2)].append(path)

all_fqns = set(fqn_decl.keys())
all_pkgs = set(pkg_of.values())
topfun_re = re.compile(r'^\s*(?:public |internal |private |inline |suspend |operator |infix )*\s*(?:fun|val|const val|var)\s+(?:<[^>]*>\s*)?([A-Za-z_][A-Za-z0-9_]*)')
for path in files:
    pkg = pkg_of[path]
    with open(path, encoding='utf-8', errors='replace') as fh:
        for ln in fh:
            m = topfun_re.match(ln)
            if m:
                all_fqns.add(pkg + "." + m.group(1))

broken = []
for path in files:
    with open(path, encoding='utf-8', errors='replace') as fh:
        for i, ln in enumerate(fh, 1):
            m = import_re.match(ln)
            if not m: continue
            imp = m.group(1)
            if not imp.startswith("com.airi.assistant"): continue
            if imp.endswith(".*"):
                base = imp[:-2]
                if any(f.startswith(base + ".") for f in all_fqns) or base in all_pkgs:
                    continue
                broken.append(("wildcard", imp, path, i)); continue
            if imp in all_fqns: continue
            parent = imp.rsplit(".",1)[0]
            if parent in all_fqns: continue
            parent2 = parent.rsplit(".",1)[0] if "." in parent else parent
            if parent2 in all_fqns: continue
            broken.append(("unresolved", imp, path, i))

dups = {k:v for k,v in fqn_decl.items() if len(v) > 1}

print("TOTAL FILES:", len(files))
print("TOTAL DECLARED FQNs:", len(fqn_decl))
print("\n=== BROKEN INTERNAL IMPORTS (%d) ===" % len(broken))
for kind, imp, path, i in sorted(broken):
    print(f"{kind}\t{imp}\t{path.replace(ROOT+'/','')}:{i}")
print("\n=== DUPLICATE FQN DECLARATIONS (%d) ===" % len(dups))
for k, v in sorted(dups.items()):
    print(k)
    for p in v:
        print("   ", p.replace(ROOT+'/',''))
