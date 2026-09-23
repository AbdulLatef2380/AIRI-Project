---
name: ioc_analysis
description: Documentation and execution contract for IOC Analysis.
---

# IOC Analysis

## Description
Normalize and assess indicators of compromise defensively.

## Purpose
This built-in AIRI skill performs evidence-first analysis through `DefensiveEngineeringSkill`. It does not claim to execute code, access repositories, contact services, or produce measurements that were not supplied.

## When to use
Use it when the supplied material matches the declared input and the user needs a structured, reviewable result.

## When not to use
Do not use it as a substitute for a real build, profiler, debugger, incident commander, legal review, or authorized security process.

## Required tools
The skill requires only the active AIRI model bridge. It exposes no dangerous tool and performs no direct side effect.

## Inputs
indicators and provenance

## Outputs
type, confidence, age, context, containment, and safe retrospective checks

## Execution flow
1. Inventory supplied evidence and mark unknowns.
2. Analyze findings with severity, confidence, and evidence references.
3. Produce incremental actions and deterministic verification guidance.

## Security considerations
This is defensive and analysis-only. It must not scan, exploit, persist, evade, exfiltrate, or target systems. Provide remediation, detection, containment, lab-safe validation, or authorized planning only.

## Limitations
Use for defensive analysis. It does not scan, beacon, detonate, or contact indicators.

## Related skills
Use the existing registry to compose this skill with repository, research, testing, documentation, or result-verification skills when their evidence is available.

## Version
1.0.0
