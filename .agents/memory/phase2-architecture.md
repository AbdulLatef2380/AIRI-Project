---
name: Phase 2 Architecture Decisions
description: Key implementation decisions for Phase 2 — what was already implemented vs what needed building/wiring
---

## What was already fully implemented (do NOT re-implement):
- MemoryManager.kt — full sliding-window episodic + long-term memory with Room DB
- EmbeddingService.kt — JNI vector embeddings, cosine similarity, salience scoring
- RagRetriever.kt — semantic + chronological retrieval, multi-query RAG
- MemoryEvolutionEngine.kt — temporal decay, salience scoring, conflict resolution
- ConversationSummarizer.kt — wired in ChatViewModel line ~1468 (already active)
- SkillRegistry.kt — orchestration descriptors, enable/disable via SharedPreferences
- ArtifactManager.kt — generated file management under <filesDir>/workspace/artifacts/
- CloudSyncCoordinator.kt — bidirectional profile/preferences sync via Firestore
- MemoryLayer.kt — formal enum of memory layers (SHORT_TERM/WORKING/EPISODIC/LONG_TERM/SEMANTIC)

## What was ADDED in Phase 2 (these are real changes):
- MediaLibrary.kt (NEW) — `com.airi.assistant.media` package, manages images/docs/generated files with search/filter/metadata
- DynamicPromptEngine.kt (NEW) — `com.airi.assistant.ai.prompt` package, stateless object for full dynamic prompt assembly
- PromptService.buildSystemPromptWithContext() — new method with ragContextBlock + memorySummary injection (old buildSystemPrompt now delegates to it)
- SkillRegistry versioning — installSkillWithVersion(), validateDependencies(), getInstalledVersion(), semver compareVersions()
- CloudSyncCoordinator.pushMemories() / pullMemories() — incremental Firestore sync of isMemory=true rows, gated on cloudSyncEnabled + enableLongTermMemory
- ServiceLocator.mediaLibrary + dynamicPromptEngine — lazy properties added
- ChatViewModel RAG wiring — ragRetriever.buildContextBlock() called before every sendMessage system prompt build; result passed via buildGenerationSystemPrompt(ragContext)

## Critical wiring rules:
- RAG is called INSIDE the coroutine in sendMessage, before systemPrompt is assembled (line ~1341-1351)
- promptService.buildSystemPromptWithContext() is the authoritative prompt builder; buildSystemPrompt() delegates to it
- DynamicPromptEngine.build() is a pure function — no state, safe to call from any thread
- Memory sync: only isMemory=true rows sync to cloud; episodic history stays local-only

**Why:**  Extensive audit revealed many Phase 2 components already existed but were not wired (RAG unused, prompt not dynamic). Only targeted additions/wirings were needed — no rewrites.
