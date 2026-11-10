# Cloud usage evidence

- Google Gemini Generate Content: https://ai.google.dev/gemini-api/docs/generate-content
  - The official API supports model generation and agentic function calling. Token counting is exposed through Gemini response usage metadata in the API contract; AIRI's GeminiSseParser currently reads `usageMetadata.promptTokenCount` and `usageMetadata.candidatesTokenCount`.
- OpenAI API Overview: https://developers.openai.com/api/reference/overview/
  - OpenAI documents request IDs and rate-limit response headers including `x-ratelimit-limit-tokens`, `x-ratelimit-remaining-tokens`, and reset headers. AIRI currently requests streaming usage with `stream_options.include_usage`, but does not normalize rate-limit headers into accounting.
- Anthropic Messages API: https://platform.claude.com/docs/en/api/messages
  - The official schema includes `input_tokens`, `output_tokens`, and cache-related usage fields such as `cache_creation_input_tokens` and `cache_read_input_tokens`. AIRI currently records only input/output counts from `message_start` and `message_delta`.
- OpenRouter API overview: https://openrouter.ai/docs/api_reference/overview
  - OpenRouter documents normalized `usage.input_tokens`, `usage.output_tokens`, and `usage.total_tokens`, and allows querying generation statistics through `/api/v1/generation` after completion.
- OpenRouter limits: https://openrouter.ai/docs/api_reference/limits
  - `GET /api/v1/key` exposes key limits, remaining limits, budgets, and free-model counters. These are provider-side limits and must remain distinct from AIRI's local budget.

## AIRI screenshot finding

The screenshot displays `tokens 50000 / 8448`. In the current UI contract this is rendered by `CloudTokenUsageBar(used, cap)` from `ExecutionModePanel.kt:398-413`; it is not a provider response. The repository contains no source literal for `8448` or `8,448`. The most likely source is a previously persisted local `max_daily_cloud_tokens` value, while `50000` is the local daily counter. It therefore cannot be interpreted as a Gemini Flash Lite quota or a provider model context window without provider telemetry.

The implementation now treats this local value as optional advisory state. The default is `0` (unlimited), `NetworkGuard` logs but does not block when an advisory value is reached, and `RoutingPolicy` no longer turns the local counter into a cloud-denial rule. Provider limits, billing, and rate-limit responses remain authoritative and are not replaced by this local display.
