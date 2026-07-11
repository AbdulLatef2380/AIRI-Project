# memory — Room Database and RAG

All persistent state: chat messages, sessions, artifacts, embeddings, behavior stats, audit log.

## Database

`AiriDatabase` — Room database encrypted with SQLCipher (AES-256).

### Schema (current: version 6)

| Table | Purpose |
|-------|---------|
| `episodic_memory` | Chat messages with `feedback INT` column (added migration 5→6) |
| `chat_sessions` | Session metadata (title, timestamp, model) |
| `workspace_artifact` | Code/file artifacts with `attachment_json` column |
| `message_embedding` | Vector embeddings for RAG retrieval |
| `context_cache` | Compressed context summaries |
| `behavior_stats` | Tool/agent execution statistics |
| `usage_stats` | Daily token usage per provider |
| `audit_log` | Security and access audit trail |

### Migrations

All migrations are registered in `AiriDatabase.MIGRATION_*`. The sequence is: 1→2→3→4→5→6. Never skip a migration — apply sequentially.

## Feedback Persistence

`ChatMessage.feedback: Int` — 1 = thumbs up, -1 = thumbs down, 0 = none.

Stored by `ChatViewModel.submitFeedback()` via `MemoryDao.updateFeedback()`. Loaded on chat startup via `getAllMessages()` (`SELECT *` includes the feedback column). Displayed in `AiBubble` via `initialFeedback` parameter.

## RAG Pipeline

`MemoryRagEngine` embeds user messages using a local embedding model and stores vectors in `message_embedding`. At inference time, the top-K similar messages are prepended to the context window as "memory context".

## Status

- Schema: **Current** (version 6, all migrations registered)
- Feedback persistence: **Complete** (write + restore on restart)
- RAG: **Wired** (requires local embedding model)
