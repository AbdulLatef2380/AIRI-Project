# AIRI Static Release Audit Manifest

- Audit date: 2026-10-01
- Branches: `main` at `587d6210`, `cp-foundation` at `c29ea79f`
- Mode: read-only static review; no source changes during discovery
- Required output: 40–200 distinct findings, each with severity, confidence, evidence path/line, impact, and remediation
- Severity: P0 release blocker, P1 critical, P2 high, P3 medium, P4 low/design debt
- Confidence: confirmed / likely / needs-runtime-proof
- Deduplication key: root cause + owning file/component, not symptom count
- Scope: Android app, agent/orchestrator, memory, skills/tools, connectors/auth/network, assistant/chat, UI/Compose, concurrency/lifecycle, security/privacy, build/tests/CI/release

## Evidence rules

1. Inspect source, tests, manifests, Gradle, resources, workflows, and docs directly.
2. Do not infer runtime success from naming or comments.
3. Do not report style preferences as defects unless they affect stability, security, accessibility, correctness, or user trust.
4. Do not modify repository files.
5. Every finding must include an exact repository path and approximate line or symbol.
