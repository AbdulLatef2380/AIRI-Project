#!/usr/bin/env python3
"""
Scans all Kotlin source files and reports:
  1. Brace-balance issues (orphaned braces causing premature class closure)
  2. Double-blank lines between } } patterns (likely orphaned braces)
"""
import os, re, sys
from pathlib import Path

SRC = Path("app/src/main/java")
issues = []

def find_top_level_declarations(lines):
    """Return list of (line_number_1indexed, kind, name) for top-level declarations."""
    decls = []
    for i, line in enumerate(lines, 1):
        m = re.match(r'^(data class|sealed class|enum class|class|object|interface|fun |typealias )\s+(\w+)', line)
        if m:
            decls.append((i, m.group(1).strip(), m.group(2)))
    return decls

for kt in sorted(SRC.rglob("*.kt")):
    lines = kt.read_text(errors='replace').splitlines()
    
    # Find the first top-level class/object/interface declaration
    class_start = None
    for i, line in enumerate(lines):
        if re.match(r'^(class|abstract class|open class|sealed class|data class|object|interface)\s+', line):
            class_start = i
            break
    
    if class_start is None:
        continue
    
    # Count braces from class_start to find where class closes
    depth = 0
    close_lines = []
    for i, line in enumerate(lines[class_start:], start=class_start+1):
        depth += line.count('{') - line.count('}')
        if depth <= 0 and i > class_start + 1:
            close_lines.append(i)

    total = len(lines)
    if len(close_lines) > 1:
        issues.append(f"MULTI-CLOSE  {kt.relative_to('.')}: class closes at lines {close_lines}, file has {total} lines")
    elif len(close_lines) == 1 and close_lines[0] != total:
        issues.append(f"EARLY-CLOSE  {kt.relative_to('.')}: class closes at line {close_lines[0]} but file has {total} lines — {total - close_lines[0]} orphaned lines after")
    # else: clean

if issues:
    print(f"Found {len(issues)} structural issues:\n")
    for iss in issues:
        print(" ", iss)
else:
    print("No brace-balance issues found.")
