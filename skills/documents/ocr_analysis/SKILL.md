---
name: ocr_analysis
description: Official AIRI skill for ocr analysis. Use when the user requests ocr analysis.
---

# OCR Analysis

## Description

Official AIRI skill for ocr analysis.

## Purpose

Provide bounded on-device OCR through AIRI's typed skill contract. Latin text uses ML Kit; Arabic uses Tesseract4Android with the bundled `ara.traineddata` model. The implementation and manifest in `app/src/main/java/com/airi/assistant/ai/skills` are the source of truth for runtime behavior.

## When to use

Use this skill when the user's request matches `ocr_analysis` or its documented capability. Confirm the input scope, expected output, privacy level, and required connector or permission before execution.

## When not to use

Do not use this skill when the request belongs to a more specific registered skill, lacks the required evidence, or requires an external side effect that is not explicitly authorized.

## Required tools

Use only the tools declared by the skill manifest and exposed through `SkillToolBridge`. Never call a tool directly around the permission, connector-health, sandbox, and confirmation layers.

## Inputs

Provide the attachment URI and optionally `language=auto`, `language=latin`, or `language=arabic`. Keep URIs, credentials, and sensitive content scoped to the current project and session.

## Outputs

Return the manifest-defined `outputSchema`, preserve evidence boundaries, identify unknowns, and include safe verification guidance. Do not invent measurements, file locations, citations, or execution results.

## Execution flow

1. Validate input shape, size, URI scope, permissions, and connector availability.
2. Inventory supplied evidence and separate observations from assumptions.
3. Execute the registered implementation through the AIRI skill runtime.
4. Validate the execution result and preserve metadata/provenance.
5. Emit safe progress events and return the final bounded result.

## Examples

- Ask AIRI to ocr analysis using supplied evidence.
- Ask AIRI to read Arabic text from an attached image with `language=arabic`.
- Provide a bounded file, transcript, repository excerpt, or structured request and request a verifiable result.

## Limitations

Arabic accuracy depends on image quality, script direction, font, columns, and diacritics. Mixed Arabic/Latin layouts currently use the requested primary language. The skill analyzes supplied evidence only, never uploads it, and does not claim to execute commands, tests, scans, or external changes.

## Security considerations

Respect AIRI permission, connector, sandbox, privacy, and confirmation policies before any side effect.

## Related skills

Compose with other registered skills only when the planner can pass typed outputs and dependencies explicitly. Candidate related skills include `task_planner`, `repository_analysis`, `document_reader`, `memory_manager`, and `result_verification` where available.

## Version

1.0.0
