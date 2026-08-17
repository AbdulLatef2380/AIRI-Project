# Local release-verification evidence

## Compile attempt
Command: /home/ubuntu/tools/gradle-8.5/bin/gradle :app:compileDebugKotlin --stacktrace
Result: BLOCKED_BY_ENVIRONMENT
Cause: Android SDK location is not defined and no local Android SDK or sdkmanager was found.

## Artifact inventory before CI
app/build does not exist

## Static verifier
[PASS] Generation ownership and cleanup: ViewModel owns and clears a generation id.
[PASS] Backend cancellation barrier: Callbacks are gated after cancellation.
[PASS] Smart memory admission: Embedding and durable facts use the admission policy.
[PASS] Session-scoped vector retrieval: Vector search no longer scans all sessions.
[PASS] RAG prompt-data boundary: RAG marks retrieved data as untrusted and uses explicit memory.
[PASS] Skill and knowledge shortcuts: UI emits directives and ViewModel parses them.
[PASS] RTL-aware input alignment: Text uses logical start alignment.
[PASS] Profile deletion coordination: Profile uses the full data deletion coordinator.
[PASS] Voice explicit-stop guard: Explicit stop cancels delayed recovery.
[PASS] Hotword duplicate guard: Wake events are rate limited.
[PASS] Scheduled task outcomes: Job results are persisted by worker.
[PASS] Unique WorkManager requests: Each persisted job owns a unique WorkRequest.
[PASS] OAuth PKCE binding: OAuth state binds an S256 verifier to token exchange.
[PASS] Voice session audio ownership: The active live session owns agent-response audio output.
[PASS] Arabic memory tokenization: Memory overlap retains Unicode and normalized Arabic tokens.
[PASS] Session deletion covers explicit memory: Removing a session deletes all of its stored messages before its session row.
[PASS] Room schema version and export: The current source declares memory Room v6 and experience Room v1 with schema export enabled.
[PASS] Runtime marker normalization: Remaining AIRI_PROOF markers: 0
[PASS] Resource input_saved_knowledge (values): /home/ubuntu/AIRI-Project-git/app/src/main/res/values/strings.xml
[PASS] Resource input_saved_knowledge (values-ar): /home/ubuntu/AIRI-Project-git/app/src/main/res/values-ar/strings.xml
[PASS] Resource key parity (values-ar): Missing keys: 0
[PASS] Resource input_saved_knowledge (values-es): /home/ubuntu/AIRI-Project-git/app/src/main/res/values-es/strings.xml
[PASS] Resource key parity (values-es): Missing keys: 0
[PASS] Resource input_saved_knowledge (values-zh): /home/ubuntu/AIRI-Project-git/app/src/main/res/values-zh/strings.xml
[PASS] Resource key parity (values-zh): Missing keys: 0

summary: 25/25 checks passed
