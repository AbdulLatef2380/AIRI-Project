---
name: git_workflow
description: Documentation and execution contract for Git Workflow Assistant.
---

# Git Workflow Assistant

## Description
Plan safe branches, commits, reviews, and rollback boundaries.

## Purpose
This built-in AIRI skill performs evidence-first analysis through `DefensiveEngineeringSkill`. It does not claim to execute code, access repositories, contact services, or produce measurements that were not supplied.

## When to use
Use it when the supplied material matches the declared input and the user needs a structured, reviewable result.

## When not to use
Do not use it as a substitute for a real build, profiler, debugger, incident commander, legal review, or authorized security process.

## Required tools
The skill requires only the active AIRI model bridge. It exposes no dangerous tool and performs no direct side effect.

## Inputs
repository state and change intent

## Outputs
small commit sequence, review gates, and safe rollback commands

## Execution flow
1. Inventory supplied evidence and mark unknowns.
2. Analyze findings with severity, confidence, and evidence references.
3. Produce incremental actions and deterministic verification guidance.

## Security considerations
Keep changes reviewable and reversible. Never claim execution, compilation, test, or repository results without evidence.

## Limitations
Use to organize changes. It does not execute Git commands or push branches.

## Related skills
Use the existing registry to compose this skill with repository, research, testing, documentation, or result-verification skills when their evidence is available.

## Version
1.0.0
