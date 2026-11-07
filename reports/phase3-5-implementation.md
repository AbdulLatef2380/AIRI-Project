
## Phase 6 — memory and context engineering

The audit confirmed that AIRI already has one canonical Room-backed memory path, logical scopes (`USER`, `SESSION`, `PROJECT`), expiry and privacy filtering in the scoped DAO query, local semantic retrieval with a bounded chronological fallback, project admission filtering, and a shared `PromptBudgetLedger`. No duplicate memory store was introduced.

The concrete gap fixed in this phase was the per-session compression scratchpad: `MemoryStore.mergeFacts` was a public write boundary but did not independently bound or reject credential-like values. `MemoryFactAdmissionPolicy` now normalizes whitespace, caps each scratchpad fact at 240 characters, and rejects API keys, passwords, secrets, bearer tokens, and private keys before prompt injection. Existing session deletion already clears this scratchpad together with the Room session, traces, and drafts.

Added unit coverage for credential rejection, normalization, size bounds, and blank values. Existing tests continue to cover project/session isolation, expiry, duplicate suppression, deletion, conflict replacement, retrieval ranking, and context budget overflow.
