#!/usr/bin/env python3
"""Guard against new hard-coded user-facing UI strings in Compose code.

The project still contains a bounded set of legacy literals. Their per-file
counts are frozen in ``airi_ui_hardcoded_text_baseline.json`` so this guard can
be enabled immediately without hiding regressions: a file may only reduce its
legacy count, never increase it, and a new file may not introduce a literal.

Covered call sites:
* Compose ``Text("...")`` literals;
* accessibility ``contentDescription = "..."`` literals;
* literal ``showSnackbar("...")`` messages;
* literal messages passed to ``Toast.makeText``.

Use ``// i18n-ok: reason`` only for a deliberate non-user-facing literal, such
as a protocol marker or an approved diagnostic token. The reason is required
so suppressions remain reviewable.
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
UI = ROOT / "app/src/main/java/com/airi/assistant/ui"
BASELINE = Path(__file__).with_name("airi_ui_hardcoded_text_baseline.json")

# Each pattern captures the user-facing literal as group 1. Keep this list
# intentionally narrow: broad string-literal matching would flag URLs, tool
# names, clipboard labels, and other non-UI protocol values.
PATTERNS = (
    re.compile(r'\bText\(\s*(?:text\s*=\s*)?"((?:[^"\\]|\\.)*)"'),
    re.compile(r'\bcontentDescription\s*=\s*"((?:[^"\\]|\\.)*)"'),
    re.compile(r'\bshowSnackbar\(\s*(?:message\s*=\s*)?"((?:[^"\\]|\\.)*)"'),
    re.compile(r'\bToast\.makeText\(\s*[^,]+,\s*"((?:[^"\\]|\\.)*)"'),
)
LETTERS = re.compile(r"[A-Za-z؀-ۿ]")
TEMPLATE = re.compile(r"\$\{[^}]*\}|\$[A-Za-z_]\w*")
SUPPRESSION = "i18n-ok:"


def _is_user_facing_literal(raw: str) -> bool:
    """Return true when a captured literal contains visible alphabetic text."""
    # A template such as "$count" is data, not a translatable phrase. Keep any
    # literal prefix/suffix, e.g. "Copy $label", because that prefix is UI text.
    literal = TEMPLATE.sub("", raw)
    return bool(LETTERS.search(literal))


def scan(root: Path = ROOT) -> dict[str, int]:
    """Count violations per Kotlin file under the production UI source tree."""
    ui_root = root / "app/src/main/java/com/airi/assistant/ui"
    found: dict[str, int] = {}
    if not ui_root.exists():
        return found
    for path in sorted(ui_root.rglob("*.kt")):
        count = 0
        for line in path.read_text(encoding="utf-8").splitlines():
            if SUPPRESSION in line:
                continue
            for pattern in PATTERNS:
                count += sum(
                    1 for match in pattern.finditer(line)
                    if _is_user_facing_literal(match.group(1))
                )
        if count:
            found[str(path.relative_to(root))] = count
    return found


def check(current: dict[str, int], baseline: dict[str, int]) -> dict[str, tuple[int, int]]:
    """Return files whose violation count exceeds the approved baseline."""
    return {
        path: (count, baseline.get(path, 0))
        for path, count in current.items()
        if count > baseline.get(path, 0)
    }


def main(argv: list[str] | None = None) -> int:
    argv = sys.argv[1:] if argv is None else argv
    current = scan()
    if "--update-baseline" in argv:
        BASELINE.write_text(
            json.dumps(current, indent=2, sort_keys=True, ensure_ascii=False) + "\n",
            encoding="utf-8",
        )
        print(f"baseline written: {sum(current.values())} literals in {len(current)} files")
        return 0

    baseline = json.loads(BASELINE.read_text(encoding="utf-8")) if BASELINE.exists() else {}
    bad = check(current, baseline)
    if bad:
        print("AIRI_UI_TEXT_LINT: new hard-coded UI text found; move it to strings.xml:")
        for path, (count, approved) in sorted(bad.items()):
            print(f"  {path}: {count} literals (baseline {approved})")
        print("Use // i18n-ok: reason only for an intentional non-user-facing literal.")
        return 1
    print(
        "AIRI_UI_TEXT_LINT: ok "
        f"({sum(current.values())} legacy literals frozen in baseline; "
        f"{len(current)} files scanned)"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
