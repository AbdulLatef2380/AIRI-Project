#!/usr/bin/env python3
"""Part 5 §7.7: fail on hard-coded user-facing text in Compose `Text("...")` under ui/.

Existing violations are frozen in scripts/airi_ui_hardcoded_text_baseline.json (per-file counts).
The check fails when a file has MORE literals than its baseline, or a new file has any.
Shrinking a count is fine; run with --update-baseline to lock the lower number in.

A literal is ignored when it contains no letters (symbols such as "•"), is a pure string
template with no literal letters, or the line carries `// i18n-ok`.
"""
import json, re, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
UI = ROOT / "app/src/main/java/com/airi/assistant/ui"
BASELINE = Path(__file__).with_name("airi_ui_hardcoded_text_baseline.json")
CALL = re.compile(r'\bText\(\s*(?:text\s*=\s*)?"((?:[^"\\]|\\.)*)"')
LETTERS = re.compile(r"[A-Za-z؀-ۿ]")
TEMPLATE = re.compile(r"\$\{[^}]*\}|\$[A-Za-z_]\w*")


def scan():
    found = {}
    for path in sorted(UI.rglob("*.kt")):
        count = 0
        for line in path.read_text(encoding="utf-8").splitlines():
            if "i18n-ok" in line:
                continue
            for m in CALL.finditer(line):
                literal = TEMPLATE.sub("", m.group(1))
                if LETTERS.search(literal):
                    count += 1
        if count:
            found[str(path.relative_to(ROOT))] = count
    return found


def main():
    current = scan()
    if "--update-baseline" in sys.argv:
        BASELINE.write_text(json.dumps(current, indent=2, sort_keys=True, ensure_ascii=False) + "\n", encoding="utf-8")
        print(f"baseline written: {sum(current.values())} literals in {len(current)} files")
        return 0
    baseline = json.loads(BASELINE.read_text(encoding="utf-8")) if BASELINE.exists() else {}
    bad = {f: (n, baseline.get(f, 0)) for f, n in current.items() if n > baseline.get(f, 0)}
    if bad:
        print("AIRI_UI_TEXT_LINT: hard-coded Text(\"...\") found; move it to strings.xml:")
        for f, (n, b) in sorted(bad.items()):
            print(f"  {f}: {n} literals (baseline {b})")
        return 1
    print(f"AIRI_UI_TEXT_LINT: ok ({sum(current.values())} legacy literals frozen in baseline)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
