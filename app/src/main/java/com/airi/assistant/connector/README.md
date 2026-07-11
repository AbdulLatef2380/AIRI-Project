# connector — External Integration Layer

Adapters connecting AIRI to external services (LLMs, apps, device features).

## Structure

```
connector/
├── api/          Cloud LLM providers
│   ├── AnthropicProvider    Claude models via Anthropic API
│   ├── GeminiProvider       Gemini models via Google AI Studio
│   ├── OpenAiProvider       GPT models via OpenAI API
│   ├── OpenRouterProvider   Multi-model routing via OpenRouter
│   └── LlmCertPins          TLS certificate pins for all 4 hosts
├── app/          Third-party app connectors
│   ├── GitHubConnector      GitHub REST API (OAuth)
│   ├── GoogleConnector      Google Calendar + Drive (OAuth)
│   └── TelegramConnector    Telegram Bot API
├── mcp/          Model Context Protocol
│   ├── McpConnector         Generic MCP server client
│   └── NotionMcpConnector   Notion via MCP
├── local/        On-device connectors (no network)
│   ├── ContactsConnector
│   ├── CalendarConnector
│   ├── ClipboardConnector
│   └── LocationConnector
└── Connector.kt  Base interface

integration/       Legacy package — stubs only (replaced by connector/)
```

## API Key Flow

Keys flow: `SecretManagerScreen → SecureApiKeyStore → CloudBackend → <Provider>.keyProvider`

Each `CloudProvider` enum value maps to a key slot in `SecureApiKeyStore`. `CloudBackend` reads keys lazily via `keyProvider` lambdas set during `ServiceLocator` initialization.

## External Requirements

| Connector | Requirement | Status |
|-----------|------------|--------|
| AnthropicProvider | Anthropic API key | Optional — degrades gracefully |
| GeminiProvider | Gemini API key | Optional |
| OpenAiProvider | OpenAI API key | Optional |
| OpenRouterProvider | OpenRouter API key | Optional |
| GitHubConnector | GitHub OAuth App or PAT | Optional |
| GoogleConnector | Google OAuth credentials | Optional |
| TelegramConnector | Telegram Bot Token | Optional — connector is a stub |
| McpConnector | MCP server URL | Optional |

## Status

All LLM providers: **Production-ready** (CloudBackend handles failover between them)  
GitHubConnector: **Complete API** — write operations (commit, PR, branches) wired  
GoogleConnector: **Requires Google OAuth setup**  
TelegramConnector: **Stub** — needs implementation  
Local connectors: **Production-ready** (no external deps)
