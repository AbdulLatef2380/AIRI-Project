# AIRI Generative UI

AIRI now has a constrained, native-first response surface for charts, diagrams, statistics, and small forms inside the conversation. The model may choose this surface only when it improves the answer; ordinary answers remain plain text.

## Contract

The model emits at most one fenced block:

```text
```airi-ui
{"version":1,"title":"...","blocks":[...]}
```
```

The compiler accepts only eight block types:

- `stat`: one value with optional supporting text.
- `bar_chart`: equal-length labels and numeric values.
- `diagram`: bounded nodes and edges that reference existing node IDs.
- `route`: an ordered list of bounded stops with optional validated coordinates; it does not fetch maps or open URLs by itself.
- `form`: bounded text fields and a descriptive action ID.
- `actions`: bounded quick-action buttons whose prompts return through the normal agent dispatch path.
- `table`: a bounded comparison/data table with equal-width rows and no executable cells.
- `checklist`: a bounded stateful checklist whose state is local to the rendered response.

The Android renderer owns layout, color, typography, scrolling, and accessibility semantics. The model cannot provide executable code, HTML, CSS, JavaScript, arbitrary URLs, or arbitrary layout instructions. Block and nested IDs must be unique, chart values must be finite, route coordinates must be paired, and all table rows must match the header width. Invalid schemas are discarded and the original message is rendered through the existing Markdown path.

## Limits

All limits live in `GenerativeUiLimits` and are used by both the parser and the model-facing prompt contract, so they cannot drift apart: at most 8 blocks; at most 30 entries per `labels`/`values`/`nodes`/`edges`; text is truncated at 240 characters; tables have 2-6 columns and 1-20 rows; routes have 2-20 stops; forms have 1-8 fields; actions have 1-8 options; checklists have 1-20 items; ids match `[A-Za-z0-9_-]+` (max 48). Every id in a document must be unique across block ids, nested ids and a form's `actionId`; a collision is rejected with `DUPLICATE_ID`. Each rejection carries a typed `GenerativeUiRejectReason`; the reason is never derived from error text, and raw model text is never copied into it.

## Rendering lifecycle

model response → bounded compiler (`GenerativeUiCompiler`, small LRU cache) → native Compose renderer. During streaming, a complete valid document may be projected immediately; an unclosed fence or partial JSON remains plain text until the closing fence arrives. No speculative control is shown. Interactive block state (selected bar, selected node, form values, checked items) lives in `GenerativeUiStateStore`, keyed `<messageId>:<blockId>` for every block, bounded to 256 keys with least-recently-used eviction, and removed when its message or conversation is deleted.

## Actions

A UI action is **not** dispatched automatically. The real flow is:

1. The user taps a quick action or submits a form. `ChatViewModel.handleGenerativeUiAction` rebuilds the action from the assistant source message (`GenerativeUiCompiler.rebuildAction`), so callback data is never trusted. Unknown ids, unknown field names, missing required fields and oversize values are refused, and the user sees a short notice.
2. A verified action is staged: its `[AIRI_UI_ACTION:<id>]` prompt is placed in the composer and held by `PendingGenerativeUiActionGate`. Nothing is sent yet.
3. The user reviews the text and sends it manually. Only if the submitted text equals the staged prompt is the send attributed to the action; editing the composer, switching or deleting the conversation, or sending anything else drops the staged action.
4. The send starts a **new** execution through the normal `ChatViewModel` generation gate and the usual AgentLoop/tool/connector/terminal/approval policies. A UI action never calls a connector, terminal, sandbox or provider directly. That execution records a `GenerativeUiAction` runtime event with the source message, parent execution id, action id and field names only; raw form values, prompts and provider payloads are never copied into traces.

The rendered control shows a bounded lifecycle (`staged`, `running`, `succeeded`, or `failed`) keyed by source message and action id. The execution sets `running` when the staged prompt is claimed and sets the terminal phase from the persisted execution outcome; rejected or stale actions become `failed`. The same result is emitted as a typed `UI_PROJECTION_COMPLETED` event with the source and parent execution ids, without copying form values or prompts.

Models may also call the read-only `render_ui` tool with a JSON document. The tool validates it through the same compiler as the `airi-ui` fence fallback and returns a canonical fence; it never executes UI callbacks. A rejected document returns a typed invalid-argument result so the AgentLoop can repair or fall back to plain text.

## Repair retry

When a finished answer from a non-local provider carries an `airi-ui` block that the compiler rejects for a fixable reason (everything except `NO_FENCE` and `OVERSIZE`), `ChatViewModel` makes **one** silent, tool-less model call (`GenerativeUiRepairRunner`) that tells the model the typed reason in plain words and asks for the same reply with a corrected block, or plain text. The retry is skipped when the run invoked any tool, when the provider is local, when the answer is too large to echo, when the generation was cancelled, and it is bounded by a 25-second timeout. The repaired reply replaces the original only if it compiles, or is plain text with no `airi-ui` remnant; otherwise the original answer is kept, so a retry can never make the result worse. The trace shows the first rejection and then the final outcome. This also covers a truncated block (`UNCLOSED_FENCE`).

## Trace events

Every persisted assistant answer that actually contained an `airi-ui` candidate records one `UI_PROJECTION_COMPLETED` runtime event whose outcome is `accepted` (with `blockCount`) or `rejected` (with a typed `reason`). Answers without a candidate record nothing. The activity feed shows a rejection as a warning with status failed. Memory-context and provider-resolution milestones are recorded to the runtime trace and the activity feed, and are merged into the inline timeline through the execution's activity snapshot.

## AIRI identity

The runtime identity contract now tells configured models that AIRI can use this schema, while preserving the existing truthfulness rules around providers, capabilities, attachments, tools, memory, and approvals. The product identity remains AIRI; the selected model is only an execution backend.

## Why this design

OpenAI's official description of Intelligent UI says responses may combine text, visuals, and interactive elements, and that the format should depend on the question. It also describes a native component library plus a compiler that progressively renders model-produced interfaces. AIRI adopts the architectural idea, not the proprietary implementation: a versioned schema, an allow-listed component registry, a validator, and a native Compose renderer.

## Sources

- [1] [GPT-6 and Intelligent UI for everyone](https://openai.com/index/gpt-6-for-everyone/): describes question-dependent composition, native streamable components, and progressive compilation.
- [2] [Intelligent UI in ChatGPT](https://help.openai.com/en/articles/20001598-intelligent-ui-in-chatgpt): describes combining text, visuals, forms, charts, and interactive experiences, while allowing plain text when it is clearer.
- [3] [ChatKit widgets](https://developers.openai.com/api/docs/guides/chatkit-widgets): describes bounded widget components and action payloads handled by the application rather than arbitrary client code.

## Validation

`GenerativeUiCompilerTest` covers valid chart/form compilation, rejection of unknown or executable block types, schema version rejection, and plain-text fallback. `GenerativeUiSchemaHardeningTest` covers typed rejection reasons, `actionId` collisions, the parse cache and fence stripping; `GenerativeUiStateStoreTest` covers key isolation, action lifecycle state, removal and LRU eviction; `RenderUiToolTest` covers canonical rendering and compiler rejection; `PendingGenerativeUiActionGateTest` covers the staged-action lifecycle; `RuntimeTraceActivityMapperTest` covers accepted/rejected projection events. Device validation remains part of the Android CI matrix; local sandbox compilation may still require the configured Android SDK.
