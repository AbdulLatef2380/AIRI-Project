# Semantic Intent Understanding — Implementation Note

## What changed

A request-bound `StructuredTaskUnderstanding` projection now sits between capability detection and request planning. It extracts a stable goal, task type, requested outcome, entities, required capabilities, constraints, missing information, confidence, clarification state, and ordered subtasks. It is diagnostic and planning data; it does not grant permissions or execute tools.

`AgentRequestPlanner` consumes the structured projection and stores it in `RequestPlan`, while the existing capability detector remains a safety backstop for live capability requirements and connector targeting. The AgentLoop records a redacted `STRUCTURED_TASK_UNDERSTOOD` trace stage containing only task type, goal, outcome, confidence, and entity names.

The projection distinguishes code returned in chat, file creation, file creation plus verification, code analysis, connector operations, and web research. It also extracts programming language, application type, interface, and an explicitly named filename for the calculator/build examples. Multi-step separators preserve bounded subtask summaries.

The existing coding detector was extended only with domain concepts needed to generalize the supplied paraphrase family (calculator/arithmetic/tool and common Arabic inflections); it remains a conservative candidate producer. Authorization and tool exposure remain governed by the existing runtime catalog, permission profile, and admission gates.

`TypedArgumentAdapters` was rewritten with explicit return types and named constructor arguments. This removes the recursive Kotlin type-inference failure reported by GitHub CI in nested schema conversion.

## Verification

- `python3 tools/final_runtime_integration_audit.py`: PASS.
- `python3 scripts/airi_localization_health.py --strict`: PASS.
- `python3 scripts/airi_release_health.py`: PASS.
- `git diff --check`: PASS.
- Added focused tests for calculator paraphrases, Arabic/English mixed requests, requested outcomes, connectors, ordinary conversation, and multi-step tasks.

The local Android unit test command could not configure `:app` because this sandbox has no Android SDK (`SDK location not found`). This is an environment limitation; GitHub Actions remains the authoritative Android compilation check.
