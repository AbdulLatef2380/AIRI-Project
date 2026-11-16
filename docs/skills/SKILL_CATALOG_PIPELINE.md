# Unified skill pipeline

AIRI now uses one canonical path for first-party skills:

```text
OfficialSkillLibrary
        ↓
SkillCatalog (manifest + deterministic instructions)
        ↓
SkillRegistry (enabled/discovered executable instances)
        ↓
AgentSkillCatalog / load_skill (prompt discovery + full instructions)
        ↓
SkillToolBridge (request-scoped authorization and invocation)
        ↓
structured ToolResult evidence
```

`SkillCatalog` is context-free and derives every official instruction document from the same `SkillManifest` used by the executable factory. This prevents a skill from being advertised by `OfficialSkillLibrary` while being absent from `load_skill`. The catalog includes the stable ID, schema, tools, connector dependencies, skill dependencies, risk/confirmation policy, limitations, and examples.

`SkillRegistry` rejects an official entry that has no corresponding catalog document before exposing it to routing. `load_skill` is read-only and returns the deterministic instruction document; it does not grant permissions or bypass connector gates. `SkillToolBridge` remains the final authorization boundary and rechecks live connector health, action consent, model access, memory access, and runtime requirements immediately before invocation.

A successful invocation must return a structured result with execution status and evidence. A denied or unavailable skill remains discoverable with an actionable readiness reason, but is never represented as executable merely because its manifest exists.

Legacy programming documents (`python_app` and `pydroid3_compat`) remain repository-backed and are adapted into the same prompt catalog for compatibility.
