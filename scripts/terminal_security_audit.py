#!/usr/bin/env python3
"""Static security contract audit for AIRI terminal_execute."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sandbox = (ROOT / "app/src/main/java/com/airi/assistant/agent/sandbox/SandboxExecutor.kt").read_text()
runtime = (ROOT / "app/src/main/java/com/airi/assistant/terminal/TerminalRuntime.kt").read_text()
governance = (ROOT / "app/src/main/java/com/airi/assistant/security/PermissionGovernanceLayer.kt").read_text()
dispatch = (ROOT / "app/src/main/java/com/airi/assistant/agent/loop/tool/ToolDispatcher.kt").read_text()
checks = {
    "terminal dispatch calls governed runtime": 'terminalExecutionGateway' in dispatch and 'executeForAgent(command, agentId)' in dispatch,
    "terminal runtime invokes governance": 'executionGateway.execute' in runtime and 'governance' in runtime,
    "argv execution avoids shell -c": 'ProcessBuilder(argv)' in sandbox and 'ProcessBuilder("/system/bin/sh"' not in sandbox,
    "binary allowlist exists": 'BINARY_ALLOWLIST' in sandbox,
    "git subcommands are allowlisted": 'GIT_SUBCOMMAND_ALLOWLIST' in sandbox,
    "shell metacharacters are rejected": 'META_CHARS' in sandbox and 'Shell metacharacter' in sandbox,
    "absolute/path scope restrictions exist": 'validateArguments' in sandbox and 'safePath' in sandbox and 'Absolute paths are outside the workspace' in sandbox,
    "environment is scrubbed": 'env.clear()' in sandbox and 'CALLER_ENV_ALLOWLIST' in sandbox,
    "output is bounded": 'OUTPUT_LIMIT_BYTES' in sandbox and 'output truncated' in sandbox,
    "process is forcibly cleaned up": 'proc.destroyForcibly()' in sandbox,
    "encoded dangerous payloads are expanded": 'decodeAndExpand' in governance and 'dangerousPatterns' in governance,
    "rate limiting is enforced": 'checkRateLimit' in governance,
}
failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(f"[{ 'PASS' if ok else 'FAIL' }] {name}")
if failed:
    raise SystemExit(f"FAIL: {len(failed)} terminal contracts")
print(f"PASS: {len(checks)} terminal security contracts")
