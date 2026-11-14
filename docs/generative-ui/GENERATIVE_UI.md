# AIRI Generative UI

AIRI now has a constrained, native-first response surface for charts, diagrams, statistics, and small forms inside the conversation. The model may choose this surface only when it improves the answer; ordinary answers remain plain text.

## Contract

The model emits at most one fenced block:

```text
```airi-ui
{"version":1,"title":"...","blocks":[...]}
```
```

The compiler accepts only five block types:

- `stat`: one value with optional supporting text.
- `bar_chart`: equal-length labels and numeric values.
- `diagram`: bounded nodes and edges that reference existing node IDs.
- `form`: bounded text fields and a descriptive action ID.
- `actions`: bounded quick-action buttons whose prompts return through the normal agent dispatch path.

The Android renderer owns layout, color, typography, scrolling, and accessibility semantics. The model cannot provide executable code, HTML, CSS, JavaScript, arbitrary URLs, or arbitrary layout instructions. Invalid schemas are discarded and the original message is rendered through the existing Markdown path.

The complete lifecycle is: model response → bounded compiler → native Compose renderer → one-shot action dispatch → `ConversationExecutionCoordinator` → AgentLoop/tool/connector/terminal policy → `ExecutionStatusBus` and `UniversalRuntimeTrace` → persisted assistant turn. A UI action never calls a connector, terminal, sandbox, or live provider directly; it re-enters the same policy-controlled conversation path as typed input.

Form actions are converted to an explicit `[AIRI_UI_ACTION:<id>]` prompt and sent through the existing ChatViewModel dispatch path. They do not bypass tools, permissions, or approval flows.

Quick actions use the same path. Every persisted assistant turn also records a redacted `UI_PROJECTION_COMPLETED` runtime event with either `schema_candidate` or `text_fallback`, correlated by the existing execution ID. This keeps the chat surface, agent trace, routing, and diagnostics in one lifecycle without storing raw prompt or provider payloads in the UI trace.

## AIRI identity

The runtime identity contract now tells configured models that AIRI can use this schema, while preserving the existing truthfulness rules around providers, capabilities, attachments, tools, memory, and approvals. The product identity remains AIRI; the selected model is only an execution backend.

## Why this design

OpenAI's official description of Intelligent UI says responses may combine text, visuals, and interactive elements, and that the format should depend on the question. It also describes a native component library plus a compiler that progressively renders model-produced interfaces. AIRI adopts the architectural idea, not the proprietary implementation: a versioned schema, an allow-listed component registry, a validator, and a native Compose renderer.

## Sources

- [1] [GPT-6 and Intelligent UI for everyone](https://openai.com/index/gpt-6-for-everyone/): describes question-dependent composition, native streamable components, and progressive compilation.
- [2] [Intelligent UI in ChatGPT](https://help.openai.com/en/articles/20001598-intelligent-ui-in-chatgpt): describes combining text, visuals, forms, charts, and interactive experiences, while allowing plain text when it is clearer.
- [3] [ChatKit widgets](https://developers.openai.com/api/docs/guides/chatkit-widgets): describes bounded widget components and action payloads handled by the application rather than arbitrary client code.

## Validation

`GenerativeUiCompilerTest` covers valid chart/form compilation, rejection of unknown or executable block types, schema version rejection, and plain-text fallback. Device validation remains part of the Android CI matrix; local sandbox compilation may still require the configured Android SDK.
