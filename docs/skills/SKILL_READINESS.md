# AIRI skill readiness contract

A skill being present in the catalog does not mean that it can execute. AIRI now exposes one monotonic lifecycle projection for every registered skill:

1. **Registered** — the official or user-installed manifest and factory are known.
2. **Ready** — static requirements are satisfied: the service is healthy, required connector actions are allowed, and declared Android permissions are granted.
3. **Exposed** — the skill is enabled and its namespaced schema can be shown to AgentLoop. Disabled skills are never exposed.
4. **Admitted** — the request-scoped dispatcher may consider the skill after the readiness gate.
5. **Executable** — all gates proven by the catalog are satisfied. Model and memory requirements remain request-scoped and are revalidated by `SkillToolBridge` with the live `SkillContext`.

`SkillReadiness` is intentionally fail-closed. A connector that is authenticated but unhealthy, or an authenticated connector action that is not allowed by the access profile, is not reported as executable. The UI shows the blocking reason and a localized next action instead of emitting a generic `auth_required` message.

The same stable manifest ID must be used for preferences and toggles. Display names are localized and must never be passed to `setSkillEnabled`.

## Runtime boundary

`SkillRegistry.getAllSkillInfos()` supplies the UI and diagnostics projection. `SkillToolBridge.readiness()` and `SkillInvocationAccessPolicy` remain the final request-scoped authorization boundary; they check the live model bridge, memory manager, permissions, connector health, and action policy again immediately before invocation.

This split is deliberate: discovery remains truthful when a connector is offline, while execution remains fail-closed. It also makes the failure actionable: the user can see whether AIRI is missing the connection, action consent, Android permission, enablement, or a request-scoped runtime dependency.
