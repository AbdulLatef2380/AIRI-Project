from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "app/src/main/java/com/airi/assistant"
patterns = {
    "network_timeout": r"timeout|withTimeout|connectTimeout|readTimeout|writeTimeout",
    "retry": r"retry|backoff|exponential|429|503|408",
    "streaming": r"stream|emit|Flow|collect|buffer|chunk|delta",
    "cancellation": r"cancel|CancellationException|isActive|ensureActive|Job",
    "attachments": r"Attachment|MIME|mime|contentType|displaySize|uri|readBytes|FileProvider",
    "context_limits": r"context|token|maxTokens|max_tokens|truncate|prompt",
    "secrets": r"api[_-]?key|Authorization|Bearer|password|secret",
    "raw_errors": r"e\.message|exception\.message|Throwable|printStackTrace",
}

files = sorted(SRC.rglob("*.kt"))
print(f"files={len(files)}")
for name, pattern in patterns.items():
    hits = []
    rx = re.compile(pattern, re.I)
    for path in files:
        for no, line in enumerate(path.read_text(errors="replace").splitlines(), 1):
            if rx.search(line):
                hits.append(f"{path.relative_to(ROOT)}:{no}:{line.strip()[:180]}")
    print(f"\n[{name}] hits={len(hits)}")
    for hit in hits[:80]:
        print(hit)

print("\n[provider-contract-files]")
for path in sorted((SRC / "connector/api").glob("*.kt")):
    print(path.relative_to(ROOT))
print("\n[attachment-contract-files]")
for path in sorted(SRC.rglob("*Attachment*.kt")):
    print(path.relative_to(ROOT))
