# AIRI Networking Audit — Phase H4
*Static analysis of execution/backend and ai/remote layers*

---

## What Is Real and Production-Grade

### CloudBackend + RetryPolicy
- **Retries**: `RetryPolicy.withRetry(maxAttempts = MAX_RETRIES)` wraps every `streamGenerate` call ✓
- **Exponential backoff**: `RetryPolicy` class uses backoff delay between attempts ✓
- **Error classification**: `CloudErrorType` distinguishes RATE_LIMITED, SERVER_ERROR, TIMEOUT, CONNECTION_LOST (retryable) from UNAUTHORIZED, QUOTA_EXCEEDED, CANCELLED (fail-fast) ✓
- **NetworkGuard**: Pre-flight check before any HTTP connection; blocks cloud if mode is LOCAL_ONLY ✓

### RemoteModelExecutor (OpenAI-compatible endpoints)
- `withTimeoutOrNull(90_000L)` wraps full stream ✓
- `connectTimeout = 8_000ms`, `readTimeout = 90_000ms` on HttpURLConnection ✓
- 3-attempt retry loop with `delay(RETRY_DELAY_MS)` ✓

### NetworkService
- Live `ConnectivityManager.NetworkCallback` ✓
- `requireOnline()` returns `Result<Unit>` ✓
- `observeConnectivity()` exposes `StateFlow<Boolean>` ✓

### ChatViewModel
- `withTimeout(90_000L)` wraps `streamRemoteResponse` — hard deadline prevents indefinite hang ✓

---

## Real Gaps

### H4-1: No first-token timeout
**Severity: HIGH**
The 90s `withTimeout` starts at request initiation. If the server accepts the TCP connection but delays sending the first token (common under provider overload), the user stares at "Thinking..." for up to 90 seconds before any feedback. A separate 15s first-token watchdog would trigger a retry or show a "slow response" warning.

**What to add**:
```kotlin
var firstTokenReceived = false
val firstTokenJob = scope.launch {
    delay(15_000L)
    if (!firstTokenReceived) {
        _streamingText.value = "⟳ النموذج بطيء — يُعاد المحاولة…"
    }
}
// In onToken: firstTokenReceived = true; firstTokenJob.cancel()
```

### H4-2: Provider failover not automatic
**Severity: MEDIUM**
`CloudBackend` retries the same provider after transient failures. If the chosen provider is fully down (e.g. OpenAI outage), AIRI will exhaust all retries against that provider before returning an error. There is no automatic failover to the next available provider (e.g. fallback from OpenAI → Anthropic → Gemini).

`RuntimeRouter` has `failover()` logic but it's called manually from `HybridOrchestrator` only after a local model failure — not after a cloud provider failure.

### H4-3: Partial stream corruption — no reassembly guard
**Severity: LOW**
SSE stream parsing in `CloudAdapterFactory` adapters splits on `data: ` lines. If a mobile network switching event causes a mid-stream packet loss, the partially received JSON token may be malformed. There is no reassembly buffer or corruption guard. The result is a truncated response with a potential JSON parse exception silently eaten by `runCatching`.

### H4-4: Duplicate request prevention
**Severity: LOW**
If the user taps "Send" twice rapidly before the ViewModel processes the first tap, two concurrent `sendMessage()` calls can enter the `HybridOrchestrator`. The `Mutex` inside `HybridOrchestrator` serializes them, so no duplicate execution occurs. However, the second message will queue and send after the first completes — appearing to the user as two identical messages sent. The input bar should be disabled while `isGenerating == true`. **This is a UI-level guard, not a networking fix.**

---

## Verified Timeout Chain

```
User taps Send
  └─► ChatViewModel.streamRemoteResponse()
        └─► withTimeout(90_000L) ◄── ChatViewModel level
              └─► CloudBackend.generateStream()
                    └─► RetryPolicy.withRetry(maxAttempts=3)
                          └─► CloudAdapterFactory.create().streamGenerate()
                                └─► HttpURLConnection (connectTimeout=8s, readTimeout=90s)
```

Total worst-case wait with 3 retries: 90s × 3 = 270s before final error surfaced.
Effective wait with `withTimeout(90_000L)` at ViewModel level: 90s hard cap regardless of retry count.

---

## Stream Recovery Under Network Switching

When `NetworkService.connectivityState` transitions `true → false → true` (network switch):
- Active `withTimeout` coroutine is NOT cancelled — it continues waiting
- If the TCP connection drops mid-stream, the `readTimeout = 90s` on HttpURLConnection will timeout the current attempt
- `RetryPolicy` then retries on the new network
- **Gap**: There is no active "network came back → retry now" signal. The retry waits for the full `readTimeout` expiry before reattempting.
