---
name: dependency_analysis
description: Documentation and execution contract for Dependency Analysis.
---

# Dependency Analysis

## Description
Analyze dependency roles, coupling, conflicts, and upgrade risk.

## Purpose
This built-in AIRI skill performs evidence-first analysis through `DefensiveEngineeringSkill`. It does not claim to execute code, access repositories, contact services, or produce measurements that were not supplied.

## When to use
Use it when the supplied material matches the declared input and the user needs a structured, reviewable result.

## When not to use
Do not use it as a substitute for a real build, profiler, debugger, incident commander, legal review, or authorized security process.

## Required tools
The skill requires only the active AIRI model bridge. It exposes no dangerous tool and performs no direct side effect.

## Inputs
dependency manifests and usage evidence

## Outputs
dependency map, risks, and conservative upgrade or cleanup plan

## Execution flow
1. Inventory supplied evidence and mark unknowns.
2. Analyze findings with severity, confidence, and evidence references.
3. Produce incremental actions and deterministic verification guidance.

## Security considerations
Keep changes reviewable and reversible. Never claim execution, compilation, test, or repository results without evidence.

## Limitations
Use before upgrades or cleanup. It does not resolve packages or certify licenses without supplied metadata.

## Related skills
Use the existing registry to compose this skill with repository, research, testing, documentation, or result-verification skills when their evidence is available.

## Version
1.0.0
