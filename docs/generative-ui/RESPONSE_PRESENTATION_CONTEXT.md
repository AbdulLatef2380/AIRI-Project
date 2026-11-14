# AIRI response presentation context

AIRI does not use one visual style for every answer. `AssistantResponsePresentationPolicy` combines the current content with bounded conversation context:

- short plain turns stay prose-first;
- long sessions emphasize key points and composed summaries rather than repeating the whole history;
- attachments enable artifact-aware surfaces and preserve file/code affordances;
- tool outputs, remote sources, skills, and connectors mark the response as execution-aware, while their actual work remains in the AgentLoop and policy layers;
- streaming keeps progressive text rendering and defers a complete `airi-ui` surface until its fence and schema are complete;
- document, image, presentation, website, app, game, and prompt tasks receive distinct work-kind metadata so the model and renderer can choose an appropriate structure;
- Arabic and mixed-direction content preserve RTL prose while identifiers, code, paths, URLs, numbers, and tables remain LTR islands.

The policy is deterministic and side-effect free. It does not claim that an artifact, remote source, connector, model, or tool succeeded. Success is established only by the execution result, trace, and persisted artifact from the existing runtime contracts.
