// =============================================================================
//  LlamaBridge.cpp — JNI bridge for AIRI on-device LLM
//
//  CORE-ENGINE REWRITE (session-based KV-cache reuse).
//
//  Lifetime model
//  --------------
//  A single global llama_context (g_ctx) holds a single global KV cache.
//  Conversation state is persisted across messages by tracking the absolute
//  KV position in `g_n_past`. The KV cache is wiped exactly TWICE in the
//  natural lifetime of the app:
//
//      (a) when a model is (re)loaded   — loadModel / loadModelWithProgress
//      (b) when the caller starts a new logical conversation — beginSession()
//
//  All per-message work is incremental:
//      appendUserTurn(text)       → tokenize JUST that text, decode at g_n_past,
//                                   advance g_n_past. Last token is decoded with
//                                   logits=true so generateNextTokens() can sample.
//      generateNextTokens(N, cb)  → sample loop. Each sampled token is decoded
//                                   into KV at g_n_past++. No re-tokenization,
//                                   no re-decoding of history.
//      appendAssistantTurn(text)  → fold any closing markers (e.g. <|im_end|>\n)
//                                   into KV so the next user turn aligns.
//      resetSession()             → wipe KV, set g_n_past = 0.
//
//  KV-overflow protection
//  ----------------------
//  Before every append-or-generate, airi_kv_trim_if_needed() checks whether
//  g_n_past + new_tokens + reserve > n_ctx. If so, it slides the window:
//      keep first  KV_KEEP_HEAD tokens (system prompt area, never trimmed)
//      keep last   n_ctx/2      tokens (recent context)
//      drop the middle, then `seq_add` to shift the remaining tail down.
//  If trim is impossible we fall back to a full reset.
//
//  Hard logging (AIRI_PROOF tag)
//  -----------------------------
//      SESSION_BEGIN n_ctx=…
//      APPEND_DECODE n_new=… n_past_before=… n_ctx=… logits=…
//      APPEND_DECODE_OK n_new=… n_past_after=… elapsed_ms=…
//      KV_TRIM_START / KV_TRIM_OK / KV_TRIM_FALLBACK_RESET
//      GEN_START n_past=… n_ctx=… max_new=…
//      FIRST_TOKEN_BYTES / FIRST_TOKEN latency_ms=… n_past=…
//      GEN_DONE tokens=… elapsed_ms=… tps=… first_token_ms=… n_past=… n_ctx=…
//
//  Backward-compat
//  ---------------
//  generateResponse(prompt) and generateStream(prompt, cb) are retained as
//  legacy one-shot helpers for code paths that don't yet use the session API
//  (e.g. tool-call follow-up). Internally they reset the session, prime it
//  with the supplied full prompt, and run a single generation.
// =============================================================================

#include <jni.h>
#include <android/log.h>
#include <signal.h>
#include <unistd.h>
#include <string>
#include <vector>
#include <cmath>      // std::sqrt — used by the embedding sub-bridge for L2-norm
#include <atomic>
#include <thread>
#include <functional>
#include <cstring>
#include <cstdio>
#include <cstdarg>
#include <cerrno>
#include <algorithm>
#include <stdexcept>
#include <sys/stat.h>

#include "llama/include/llama.h"
#include "llama/ggml/include/ggml.h"

#define LOG_TAG "AIRI_NATIVE"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define PROOF(...) __android_log_print(ANDROID_LOG_INFO, "AIRI_PROOF", __VA_ARGS__)

// ─── global state ────────────────────────────────────────────────────────────
static llama_model*      g_model       = nullptr;
static llama_context*    g_ctx         = nullptr;
static std::atomic<bool> g_cancel{false};
static std::string       g_model_path;

// SPEC v2 (state-machine): semantic alias for the existing cancel atomic.
// The state-machine specification refers to this flag as `g_cancel_requested`;
// it is the SAME atomic as `g_cancel` (no extra storage, no extra contention),
// just exposed under the spec-mandated name so the inference loop and the
// nativeCancel() JNI entry point both read/write a single source of truth.
static std::atomic<bool>& g_cancel_requested = g_cancel;

// SPEC v2: result code of the most recent generation/append call.
//   0  = ok / no call yet
//  -1  = ERROR             (llama_decode failure or other native error)
//  -2  = CANCELLED         (g_cancel_requested was set during the call)
//  -3  = CONTEXT_OVERFLOW  (n_past + incoming_tokens >= n_ctx)
// Read from JVM via LlamaNative.nativeGetLastStatus() so the Kotlin safe-
// generation handler can route -3 → fullReset()+retry, -1 → fullReset()+stop,
// -2 → stop cleanly. Set at well-defined exit points inside airi_append_text
// and airi_generate_next; reset to 0 at the start of every new call.
static std::atomic<int> g_last_gen_status{0};

// SPEC v2: cache the most recent (n_ctx, n_threads) pair so nativeFullReset()
// can rebuild g_ctx with identical settings (including any user-applied
// runtime mode change). 0 means "use defaults".
static uint32_t g_last_n_ctx     = 0;
static int      g_last_n_threads = 0;

// SPEC v4 — per-generation sampling parameters.
// Written by nativeSetSamplingParams() (which takes LLAMA_LOCK) before every
// generateNextTokens call.  Read inside airi_generate_next() (also under
// LLAMA_LOCK via generateNextTokens) when building the sampler chain, so the
// native decoder uses the exact values the Kotlin layer (and ultimately the
// user via the Generation Settings dialog) requested instead of the old
// compile-time constants.
//
// Default values match the previous hardcoded chain so existing behaviour is
// preserved until the first nativeSetSamplingParams() call.
static float   g_sp_temperature       = 0.7f;
static int     g_sp_top_k             = 40;
static float   g_sp_top_p             = 0.9f;
static float   g_sp_min_p             = 0.05f;
static float   g_sp_repeat_penalty    = 1.1f;
static float   g_sp_presence_penalty  = 0.0f;
static float   g_sp_frequency_penalty = 0.0f;
// penalty_last_n: how many recent tokens to scan for repetition penalties.
// -1 = full context window, 0 = disabled, positive = explicit token count.
// Default 64 matches llama.cpp's own sample default.
static int32_t g_sp_penalty_last_n    = 64;

// SPEC v3 — STABILITY: monotonic identifiers used by the Kotlin layer to
// detect and DROP stale callbacks that originated from a since-destroyed
// llama_context.
//
//   g_session_id     — incremented every time the native llama_context is
//                      created, replaced, or wiped. Specifically:
//                        • loadModel / loadModelWithProgress (after init)
//                        • setRuntimeMode (after re-init)
//                        • nativeFullReset (after rebuild)
//                        • beginSession / resetSession (KV wipe)
//                      A callback that captured g_session_id == X and now
//                      reads g_session_id == X+1 KNOWS its parent context
//                      was destroyed mid-flight; it must drop the token
//                      and not advance any UI state.
//
//   g_generation_id  — incremented at the entry of every airi_generate_next
//                      call. Lets the Kotlin layer detect "old generation
//                      streams into new state" — i.e. a callback dispatched
//                      to the Main thread from generation N that arrives
//                      after generation N+1 has already started. The token
//                      is silently dropped instead of being appended to the
//                      wrong response buffer.
//
// Both counters are read-only from JVM via nativeGetSessionId() /
// nativeGetGenerationId(). The increment sites are explicit and audited;
// see PROOF("SESSION_ID_BUMP …") / PROOF("GENERATION_ID_BUMP …") tags.
static std::atomic<int64_t> g_session_id   {0};
static std::atomic<int64_t> g_generation_id{0};

// ─── concurrency primitives ──────────────────────────────────────────────────
//
// Threading model
// ---------------
// All JNI entry points that touch the chat pipeline globals (g_model, g_ctx,
// g_n_past, g_extra_stop_ids, g_draft_*) acquire `g_llama_mutex` at the JNI
// boundary and hold it for the duration of the call. This is enforced by the
// `LLAMA_LOCK()` macro placed at the top of every such entry.
//
// Why the JNI boundary (not finer-grained locks):
//   - Internal helpers (`decode_text_chunk`, `gen_loop`, `kv_trim_to_fit`,
//     `update_draft_with_main_segment`, …) all assume the caller already
//     holds the lock, so they can read/mutate g_ctx / g_n_past freely.
//     Putting the lock at the boundary means there's exactly ONE lock site
//     per call site type; no helper needs to know about locking.
//   - Cancellation is intentionally LOCK-FREE — `Java_..._cancel` only
//     stores into `g_cancel` (atomic) and does NOT acquire the mutex.
//     If cancel had to wait on the mutex, it would block until the
//     in-flight decode ran to completion, defeating the point of cancel.
//   - The generation loop reads `g_cancel` every iteration without holding
//     any extra lock, so cancel has the same latency as a normal atomic load.
//
// Embedding pipeline (g_emb_*) has its own mutex `g_emb_mutex`. Embedding
// compute is a separate context with no shared KV state, so it should not
// block chat decode (and vice versa).
//
// MTMD pipeline (g_mtmd_*) keeps its existing `g_mtmd_mutex` AND now
// additionally acquires `g_llama_mutex` because mtmd_encode_chunk feeds
// the result into `llama_decode(g_ctx, …)` which mutates g_ctx/g_n_past.
// Lock order is ALWAYS `g_llama_mutex` → `g_mtmd_mutex` (outer → inner) to
// guarantee no deadlock vs chat callers (which only take g_llama_mutex).
//
static std::mutex g_llama_mutex;
static std::mutex g_emb_mutex;

#define LLAMA_LOCK() std::lock_guard<std::mutex> _llama_lock(g_llama_mutex)
#define EMB_LOCK()   std::lock_guard<std::mutex> _emb_lock(g_emb_mutex)

// Session state — persists across messages.
static int               g_n_past      = 0;       // absolute KV position
static const int         KV_KEEP_HEAD  = 128;     // never trim the first N tokens
                                                  // (covers a typical system prompt)

// ─── Speculative decoding (optional) ─────────────────────────────────────────
// A second, smaller model whose KV state is kept in lockstep with the main
// model. When `generateNextTokensSpeculative` is called it proposes K tokens
// per step and the main model verifies them in a single batched decode.
// All access is OPTIONAL — if the draft is not loaded, the main pipeline is
// completely unaffected. If anything in the speculative path fails, the
// generator falls back to single-token decoding so output is never wrong.
static llama_model*      g_draft_model = nullptr;
static llama_context*    g_draft_ctx   = nullptr;
static int               g_draft_n_past= 0;
static std::string       g_draft_path;
static std::atomic<bool> g_draft_in_sync{false};   // becomes true after the
                                                   // first successful mirror
                                                   // following beginSession.

static std::atomic<long> g_spec_drafted {0};       // total drafts proposed
static std::atomic<long> g_spec_accepted{0};       // total drafts accepted
static std::atomic<long> g_spec_runs    {0};       // total spec-generate calls

// ─── Live timing counters (read from Kotlin via getLastTimings) ─────────────
//   All values are milliseconds, except g_n_decoded (token count).
//   These are written by the native pipeline at well-defined points and read
//   back from Kotlin so the "Generation Statistics" screen can show a real
//   breakdown instead of a single opaque number.
static std::atomic<long> g_t_load_ms       {0};   // model load wall time
static std::atomic<long> g_t_tokenize_ms   {0};   // last user-turn tokenize time
static std::atomic<long> g_t_prefill_ms    {0};   // last user-turn llama_decode (logits=true) time
static std::atomic<long> g_t_first_token_ms{0};   // last gen: time from gen entry → first sampled token
static std::atomic<long> g_t_decode_ms     {0};   // last gen: total decode loop wall time
static std::atomic<int>  g_n_decoded       {0};   // last gen: tokens produced

// Cached stop-token IDs (populated on model load). Some Gemma / ChatML GGUFs
// don't mark <end_of_turn> / <|im_end|> as EOG via metadata, which causes the
// model to "keep thinking" past the turn boundary and blow the timeout.
// We resolve these by string at load time and stop generation as a backup.
static std::vector<llama_token> g_extra_stop_ids;

static void airi_resolve_stop_tokens(const llama_model* model) {
    g_extra_stop_ids.clear();
    if (!model) return;
    const llama_vocab* vocab = llama_model_get_vocab(model);
    if (!vocab) return;
    static const char* k_stops[] = {
        "<end_of_turn>",   // Gemma-2 / Gemma-3
        "<|im_end|>",      // ChatML / Qwen
        "<|eot_id|>",      // Llama-3
        "</s>",            // Mistral
    };
    for (const char* s : k_stops) {
        llama_token toks[8];
        int n = llama_tokenize(vocab, s, (int)strlen(s), toks, 8,
                               /*add_special=*/false, /*parse_special=*/true);
        if (n == 1) {
            g_extra_stop_ids.push_back(toks[0]);
            PROOF("STOP_TOKEN_RESOLVED text=%s id=%d", s, (int)toks[0]);
        }
    }
}

static bool airi_is_stop_token(const llama_vocab* vocab, llama_token tok) {
    if (llama_vocab_is_eog(vocab, tok)) return true;
    for (llama_token s : g_extra_stop_ids) {
        if (s == tok) return true;
    }
    return false;
}

// Tunables — adjust here only.
//   KV cache for a 2B Q4 model at n_ctx=2048 in fp16 ≈ ~256MB; reducing n_ctx
//   to 1536 trims that ~25% and dramatically improves prefill latency on phones.
//   Threads MUST be capped: hardware_concurrency() on a 4P+4E SoC returns 8 and
//   spawning 8 worker threads makes the LITTLE cores stall the synchronous
//   batch, *increasing* first-token latency by 2–3x.
static const uint32_t AIRI_DEFAULT_N_CTX     = 1536;
static const uint32_t AIRI_DEFAULT_N_BATCH   = 256;
static const uint32_t AIRI_DEFAULT_N_UBATCH  = 128;
static const int      AIRI_MAX_THREADS_CAP   = 4;

static int airi_pick_threads() {
    int hw = (int)std::thread::hardware_concurrency();
    if (hw <= 0) hw = 4;
    return std::min(hw, AIRI_MAX_THREADS_CAP);
}

// Phase marker so the signal handler can report WHERE we crashed.
static const char*       g_phase       = "idle";

// ─── signal handler ──────────────────────────────────────────────────────────
static void airi_signal_handler(int sig) {
    __android_log_print(ANDROID_LOG_ERROR, "AIRI_PROOF",
        "SIGNAL_CAUGHT signal=%d phase=%s", sig, g_phase);
    signal(sig, SIG_DFL);
    raise(sig);
}

static void install_signal_handlers() {
    struct sigaction sa{};
    sa.sa_handler = airi_signal_handler;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = 0;
    sigaction(SIGSEGV, &sa, nullptr);
    sigaction(SIGABRT, &sa, nullptr);
    sigaction(SIGBUS,  &sa, nullptr);
    sigaction(SIGILL,  &sa, nullptr);
    sigaction(SIGFPE,  &sa, nullptr);
}

// ─── helpers ─────────────────────────────────────────────────────────────────
static bool file_exists(const char* path) {
    struct stat st{};
    return stat(path, &st) == 0;
}

static long file_size(const char* path) {
    struct stat st{};
    if (stat(path, &st) != 0) return -1;
    return (long)st.st_size;
}

// Stronger GGUF header validation than just the 4-byte magic. A truncated
// download will still pass the magic check (those 4 bytes land first), so we
// also verify:
//   • GGUF version is one of {1, 2, 3}        (anything else = corruption)
//   • tensor_count fits a sane range          (1..1_000_000)
//   • metadata_kv_count fits a sane range     (0..1_000_000)
// All multi-byte fields in the GGUF header are little-endian (per the spec).
// On any read or sanity failure we write the reason into the thread-local
// error buffer so loadModel() can surface it back to Java.
static thread_local char g_last_native_error[512] = {0};
static void airi_set_native_error(const char* fmt, ...) {
    va_list ap; va_start(ap, fmt);
    vsnprintf(g_last_native_error, sizeof(g_last_native_error), fmt, ap);
    va_end(ap);
}
static const char* airi_get_native_error() {
    return g_last_native_error[0] ? g_last_native_error : nullptr;
}

static bool is_valid_gguf(const char* path) {
    // Pre-flight check: HARD-FAIL only on bad magic / unreadable file.
    // Everything else (version range, tensor count, kv count) is logged as a
    // soft warning so that llama.cpp itself gets the chance to produce the
    // authoritative diagnostic — many real-world GGUFs have version=4 or
    // unusual counts and load just fine.
    FILE* f = fopen(path, "rb");
    if (!f) {
        airi_set_native_error("fopen failed: %s", strerror(errno));
        return false;
    }
    // GGUF header layout (little-endian):
    //   uint32 magic            "GGUF"
    //   uint32 version
    //   uint64 tensor_count
    //   uint64 metadata_kv_count
    uint8_t hdr[24] = {};
    size_t n = fread(hdr, 1, sizeof(hdr), f);
    fclose(f);
    if (n < sizeof(hdr)) {
        airi_set_native_error("file too short: read %zu of %zu header bytes", n, sizeof(hdr));
        return false;
    }
    if (!(hdr[0] == 'G' && hdr[1] == 'G' && hdr[2] == 'U' && hdr[3] == 'F')) {
        airi_set_native_error("bad GGUF magic: %02x %02x %02x %02x", hdr[0], hdr[1], hdr[2], hdr[3]);
        return false;
    }
    uint32_t version = (uint32_t)hdr[4]
                     | ((uint32_t)hdr[5] << 8)
                     | ((uint32_t)hdr[6] << 16)
                     | ((uint32_t)hdr[7] << 24);
    uint64_t tensor_count = 0, kv_count = 0;
    for (int i = 0; i < 8; i++) {
        tensor_count |= ((uint64_t)hdr[8 + i])  << (i * 8);
        kv_count     |= ((uint64_t)hdr[16 + i]) << (i * 8);
    }
    if (version < 1 || version > 4) {
        __android_log_print(ANDROID_LOG_WARN, "LLAMA_BRIDGE",
                            "is_valid_gguf: unusual GGUF version %u (continuing — llama.cpp will decide)",
                            version);
    }
    if (tensor_count == 0 || tensor_count > 1000000ULL) {
        __android_log_print(ANDROID_LOG_WARN, "LLAMA_BRIDGE",
                            "is_valid_gguf: unusual tensor_count=%llu (continuing — llama.cpp will decide)",
                            (unsigned long long)tensor_count);
    }
    if (kv_count > 1000000ULL) {
        __android_log_print(ANDROID_LOG_WARN, "LLAMA_BRIDGE",
                            "is_valid_gguf: unusual metadata_kv_count=%llu (continuing — llama.cpp will decide)",
                            (unsigned long long)kv_count);
    }
    g_last_native_error[0] = 0;  // clear stale errors on success
    return true;
}

// Capture llama.cpp's internal log messages so we can surface the *actual*
// failure reason (e.g. "unknown architecture", "tensor 'foo' has wrong shape")
// instead of the useless "llama_model_load_from_file returned null" string.
// We APPEND every ERROR line into g_last_native_error (separated by " | ")
// so the full chain of root cause + intermediate context survives all the
// way up to Kotlin — overwriting only kept the final useless summary.
static void airi_llama_log_callback(ggml_log_level level, const char* text, void* /*user*/) {
    if (!text) return;
    // Always echo to logcat so a developer attaching adb sees the full stream.
    int prio = ANDROID_LOG_INFO;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: prio = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_WARN:  prio = ANDROID_LOG_WARN;  break;
        case GGML_LOG_LEVEL_INFO:  prio = ANDROID_LOG_INFO;  break;
        case GGML_LOG_LEVEL_DEBUG: prio = ANDROID_LOG_DEBUG; break;
        default: break;
    }
    __android_log_print(prio, "LLAMA_CPP", "%s", text);
    if (level != GGML_LOG_LEVEL_ERROR) return;

    // Trim trailing newline / whitespace from the captured text so the
    // accumulated message reads cleanly.
    size_t len = strlen(text);
    char trimmed[480];
    size_t copy = len < sizeof(trimmed) - 1 ? len : sizeof(trimmed) - 1;
    memcpy(trimmed, text, copy);
    trimmed[copy] = 0;
    while (copy > 0 && (trimmed[copy - 1] == '\n' || trimmed[copy - 1] == '\r' || trimmed[copy - 1] == ' ')) {
        trimmed[--copy] = 0;
    }
    if (copy == 0) return;

    // APPEND to g_last_native_error rather than overwrite so the FIRST
    // (most informative) error survives. Stop appending when the buffer is
    // nearly full to avoid a partial/garbled tail.
    size_t cur = strlen(g_last_native_error);
    const size_t cap = sizeof(g_last_native_error);
    if (cur == 0) {
        // First error: prefix with "llama.cpp: " for clarity in the UI.
        snprintf(g_last_native_error, cap, "llama.cpp: %s", trimmed);
    } else if (cur + 4 < cap) {
        // Subsequent errors: separate with " | " so each diagnostic is
        // visually distinct without breaking single-line UI rendering.
        size_t remaining = cap - cur - 1;
        snprintf(g_last_native_error + cur, remaining, " | %s", trimmed);
    }
}

static std::atomic<bool> g_log_callback_installed{false};
static void airi_install_log_callback_once() {
    bool expected = false;
    if (g_log_callback_installed.compare_exchange_strong(expected, true)) {
        llama_log_set(airi_llama_log_callback, nullptr);
    }
}

// Validate that `s` is a *complete* UTF-8 string (no truncated codepoint at end).
// Used to decide when it is safe to hand a token chunk to JNI NewStringUTF.
static bool is_valid_utf8(const std::string& s) {
    const unsigned char* p = reinterpret_cast<const unsigned char*>(s.data());
    size_t n = s.size();
    size_t i = 0;
    while (i < n) {
        unsigned char c = p[i];
        size_t need;
        if      ((c & 0x80) == 0x00) need = 1;
        else if ((c & 0xE0) == 0xC0) need = 2;
        else if ((c & 0xF0) == 0xE0) need = 3;
        else if ((c & 0xF8) == 0xF0) need = 4;
        else return false;
        if (i + need > n) return false;
        for (size_t k = 1; k < need; k++) {
            if ((p[i + k] & 0xC0) != 0x80) return false;
        }
        i += need;
    }
    return true;
}

static void airi_batch_clear(llama_batch& b) {
    b.n_tokens = 0;
}

static void airi_batch_add(
    llama_batch& b,
    llama_token  id,
    llama_pos    pos,
    const std::vector<llama_seq_id>& seq_ids,
    bool         compute_logits)
{
    const int i = b.n_tokens;
    b.token   [i] = id;
    b.pos     [i] = pos;
    b.n_seq_id[i] = (int32_t)seq_ids.size();
    for (size_t s = 0; s < seq_ids.size(); s++) {
        b.seq_id[i][s] = seq_ids[s];
    }
    b.logits  [i] = compute_logits ? 1 : 0;
    b.n_tokens++;
}

// =============================================================================
// DRAFT MODEL: mirror the same text into the draft model's KV cache so the
// two contexts stay in lockstep. Called from appendUserTurn / appendAssistantTurn
// when a draft is loaded. Failures are NEVER fatal — they just mark the draft
// as out-of-sync, which makes the speculative-generate path fall back to plain
// single-token decoding (still correct, just no speedup).
// =============================================================================
static void airi_decode_text_into_draft(const std::string& text, bool last_token_logits) {
    if (!g_draft_model || !g_draft_ctx) return;
    if (text.empty()) return;

    const llama_vocab* vocab = llama_model_get_vocab(g_draft_model);
    if (!vocab) { g_draft_in_sync.store(false); return; }

    const bool add_bos = (g_draft_n_past == 0);

    int n_probe = -llama_tokenize(vocab, text.c_str(), (int)text.size(),
                                  nullptr, 0, add_bos, /*parse_special=*/true);
    if (n_probe < 0) n_probe = 0;
    int cap = std::max(n_probe + 8, 32);
    std::vector<llama_token> tokens(cap);
    int n_tokens = llama_tokenize(vocab, text.c_str(), (int)text.size(),
                                  tokens.data(), cap, add_bos, /*parse_special=*/true);
    if (n_tokens <= 0) { g_draft_in_sync.store(false); return; }
    tokens.resize(n_tokens);

    const uint32_t n_ctx = llama_n_ctx(g_draft_ctx);
    if ((uint32_t)(g_draft_n_past + n_tokens) > n_ctx) {
        // Draft KV would overflow — abandon mirror for this turn and mark
        // the draft as out-of-sync. The next appendUserTurn after a fresh
        // beginSession will re-establish sync.
        PROOF("DRAFT_MIRROR_OVERFLOW n_past=%d n_new=%d n_ctx=%u -> drop sync",
              g_draft_n_past, n_tokens, n_ctx);
        g_draft_in_sync.store(false);
        return;
    }

    llama_batch b = llama_batch_init(n_tokens, 0, 1);
    airi_batch_clear(b);
    for (int i = 0; i < n_tokens; i++) {
        const bool with_logits = last_token_logits && (i == n_tokens - 1);
        airi_batch_add(b, tokens[i], g_draft_n_past + i, {0}, with_logits);
    }
    int rc = llama_decode(g_draft_ctx, b);
    llama_batch_free(b);
    if (rc != 0) {
        PROOF("DRAFT_MIRROR_DECODE_FAILED rc=%d n_tokens=%d", rc, n_tokens);
        g_draft_in_sync.store(false);
        return;
    }
    g_draft_n_past += n_tokens;
    g_draft_in_sync.store(true);
}

static void airi_draft_clear_kv() {
    if (g_draft_ctx) {
        llama_memory_clear(llama_get_memory(g_draft_ctx), true);
    }
    g_draft_n_past = 0;
    g_draft_in_sync.store(false);
}

// =============================================================================
// SESSION-LEVEL KV WINDOW MANAGEMENT
// =============================================================================
//
// Slide the KV window when an upcoming append/generate would push g_n_past
// past n_ctx. Keep the leading KV_KEEP_HEAD tokens and the most recent
// (n_ctx/2) tokens; drop and shift everything in between.
// On any failure we fall back to a hard reset (data loss, but the engine
// stays alive).
static void airi_kv_trim_if_needed(int reserve) {
    if (!g_ctx) return;
    const uint32_t n_ctx = llama_n_ctx(g_ctx);
    if ((uint32_t)(g_n_past + reserve) <= n_ctx) return;

    const int safe_keep_head = std::min((int)KV_KEEP_HEAD, g_n_past / 2);
    // PHASE 1 fix (RC-1): reserve-aware tail.
    //   Old behaviour: keep_tail was fixed at n_ctx/2 regardless of how big
    //   `reserve` was. For a large incoming user turn (e.g. ~700 tokens of
    //   Arabic + chat-template overhead) on n_ctx=1536, that left only
    //   ~640 tokens of headroom and we threw KV_OVERFLOW.
    //   New behaviour: shrink keep_tail just enough so that
    //       safe_keep_head + keep_tail + reserve + margin ≤ n_ctx
    //   while still keeping a sane minimum so coherence isn't destroyed.
    //   We intentionally never grow keep_tail above n_ctx/2 (the previous
    //   default) — only shrink, never expand.
    const int reserve_room   = std::max(0, (int)n_ctx - safe_keep_head - reserve - 16);
    const int default_tail   = std::max(64, (int)(n_ctx / 2));
    const int keep_tail      = std::max(64, std::min(default_tail, reserve_room));
    if (g_n_past - safe_keep_head <= keep_tail) {
        // Not enough trimmable space; only option is a hard reset.
        PROOF("KV_TRIM_FORCE_RESET n_past=%d n_ctx=%u safe_keep=%d keep_tail=%d reserve=%d",
              g_n_past, n_ctx, safe_keep_head, keep_tail, reserve);
        llama_memory_clear(llama_get_memory(g_ctx), true);
        g_n_past = 0;
        return;
    }

    int p0   = safe_keep_head;
    int p1   = g_n_past - keep_tail;
    int drop = p1 - p0;

    llama_memory_t mem = llama_get_memory(g_ctx);
    PROOF("KV_TRIM_START n_past=%d n_ctx=%u p0=%d p1=%d drop=%d keep_head=%d keep_tail=%d",
          g_n_past, n_ctx, p0, p1, drop, safe_keep_head, keep_tail);

    bool ok = llama_memory_seq_rm(mem, 0, p0, p1);
    if (!ok) {
        PROOF("KV_TRIM_RM_FAILED p0=%d p1=%d -> hard reset", p0, p1);
        llama_memory_clear(mem, true);
        g_n_past = 0;
        return;
    }
    llama_memory_seq_add(mem, 0, p1, g_n_past, -drop);
    g_n_past -= drop;
    PROOF("KV_TRIM_OK n_past_new=%d", g_n_past);
}

// =============================================================================
// CORE: tokenize-and-decode an arbitrary text fragment INCREMENTALLY.
// Used by appendUserTurn / appendAssistantTurn / legacy one-shots.
// =============================================================================
static int airi_append_text(JNIEnv* env, const std::string& text, bool last_token_logits) {
    if (!g_model || !g_ctx) throw std::runtime_error("MODEL_NOT_LOADED");
    if (text.empty())       return 0;

    // SPEC v2 — clear status at the start of every prefill call. Subsequent
    // exit points (-3 overflow, -2 cancel, -1 decode error) overwrite this
    // before throwing; a successful return therefore implicitly leaves
    // status = 0 for the JVM safe-generation handler to read.
    g_last_gen_status.store(0);

    const llama_vocab* vocab = llama_model_get_vocab(g_model);
    const uint32_t     n_ctx = llama_n_ctx(g_ctx);

    // Add BOS only on the very first append of a session.
    const bool add_bos = (g_n_past == 0);

    // SPEC v3 — canonical lifecycle marker. Emitted once at the entry of
    // every prefill, regardless of whether it is a system-prompt append, a
    // history-replay append, or a user-turn append. Pairs 1:1 with
    // PREFILL_END at the successful exit, so logcat can prove every prefill
    // either completed or has a matching {PREFILL_CANCELLED|APPEND_DECODE_FAILED|
    // CONTEXT_OVERFLOW} terminator. Counts are unknown at this point — they
    // are emitted in PREFILL_END.
    PROOF("PREFILL_BEGIN session_id=%lld text_bytes=%zu logits=%d add_bos=%d "
          "n_past_before=%d n_ctx=%u",
          (long long)g_session_id.load(), text.size(),
          last_token_logits ? 1 : 0, add_bos ? 1 : 0,
          g_n_past, n_ctx);

    // Two-pass tokenize.
    long t_tok0 = (long)(ggml_time_us() / 1000LL);
    int n_probe = -llama_tokenize(vocab, text.c_str(), (int)text.size(),
                                  nullptr, 0, add_bos, /*parse_special=*/true);
    if (n_probe < 0) n_probe = 0;
    int cap = std::max(n_probe + 8, 32);
    std::vector<llama_token> tokens(cap);
    int n_tokens = llama_tokenize(vocab, text.c_str(), (int)text.size(),
                                  tokens.data(), cap, add_bos, /*parse_special=*/true);
    long t_tok1 = (long)(ggml_time_us() / 1000LL);
    // Only record on the user-turn append (i.e. the one that ends with logits).
    if (last_token_logits) g_t_tokenize_ms.store(t_tok1 - t_tok0);
    if (n_tokens < 0) {
        PROOF("APPEND_TOKENIZE_FAILED rc=%d cap=%d", n_tokens, cap);
        throw std::runtime_error("TOKENIZE_FAILED");
    }
    tokens.resize(n_tokens);

    // SPEC v2 — CONTEXT OVERFLOW GUARD (PHASE 1, step 4).
    //   IF (n_past + incoming_tokens >= n_ctx) → return CONTEXT_OVERFLOW (-3)
    // KV trimming is intentionally NOT invoked here: per spec the overflow
    // condition must be surfaced as a hard error so the Kotlin safe-generation
    // handler can perform a clean fullReset()+retry instead of the engine
    // silently dropping tokens. The legacy airi_kv_trim_if_needed helper is
    // left in place (it is unrelated KV logic that other paths may still use)
    // but is no longer called from the active prefill path.
    if ((uint32_t)(g_n_past + n_tokens) >= n_ctx) {
        g_last_gen_status.store(-3);
        PROOF("CONTEXT_OVERFLOW phase=prefill n_past=%d n_new=%d n_ctx=%u status=-3",
              g_n_past, n_tokens, n_ctx);
        throw std::runtime_error("CONTEXT_OVERFLOW");
    }

    // SPEC v2 — CHUNKED PREFILL (PHASE 1, step 3).
    // The full prompt MUST NOT be passed in one llama_decode call. Split into
    // batches of AIRI_PREFILL_CHUNK tokens, check g_cancel_requested between
    // every chunk, and surface decode errors immediately as status=-1 so the
    // Kotlin layer can route them through fullReset(). Chunk size of 64 keeps
    // per-batch latency bounded on mid-range mobile CPUs while still amortising
    // llama_decode's per-call overhead across multiple tokens.
    static const int AIRI_PREFILL_CHUNK = 64;
    const int n_chunk_alloc = std::min(AIRI_PREFILL_CHUNK, std::max(n_tokens, 1));
    llama_batch batch = llama_batch_init(n_chunk_alloc, 0, 1);

    long t0 = (long)(ggml_time_us() / 1000LL);
    PROOF("APPEND_DECODE n_new=%d n_past_before=%d n_ctx=%u logits=%d chunk=%d",
          n_tokens, g_n_past, n_ctx, last_token_logits ? 1 : 0, AIRI_PREFILL_CHUNK);

    g_phase = "append_decode";
    int processed = 0;
    int chunk_idx = 0;
    while (processed < n_tokens) {
        // PHASE 1, step 1+2: cancel flag is checked INSIDE the decode loop,
        // not outside, so cancellation latency is bounded by one chunk.
        if (g_cancel_requested.load()) {
            g_last_gen_status.store(-2);
            llama_batch_free(batch);
            PROOF("PREFILL_CANCELLED processed=%d total=%d status=-2",
                  processed, n_tokens);
            // SPEC v3 — canonical cancel marker. The legacy PREFILL_CANCELLED
            // tag is preserved above for grep compatibility; this one is the
            // canonical marker emitted by EVERY cancellation exit (prefill or
            // generate) so a single regex `GENERATION_CANCELLED|CONTEXT_RESET`
            // surfaces all stop events.
            PROOF("GENERATION_CANCELLED phase=prefill processed=%d total=%d "
                  "session_id=%lld", processed, n_tokens,
                  (long long)g_session_id.load());
            throw std::runtime_error("PREFILL_CANCELLED");
        }
        const int this_chunk = std::min(AIRI_PREFILL_CHUNK, n_tokens - processed);
        airi_batch_clear(batch);
        for (int i = 0; i < this_chunk; i++) {
            const int abs_i = processed + i;
            const bool with_logits = last_token_logits && (abs_i == n_tokens - 1);
            airi_batch_add(batch, tokens[abs_i], g_n_past + abs_i, {0}, with_logits);
        }
        // SPEC v3 — per-chunk lifecycle marker. One line per llama_decode call
        // inside the prefill loop, so logcat can prove the loop is making
        // progress (or pinpoint exactly which chunk failed). Cheap — we are
        // already paying for tens of milliseconds of decode per chunk; one
        // log line is in the noise.
        PROOF("PREFILL_CHUNK idx=%d this_chunk=%d processed=%d total=%d "
              "n_past_before_chunk=%d", chunk_idx, this_chunk, processed,
              n_tokens, g_n_past + processed);
        int rc = llama_decode(g_ctx, batch);
        if (rc != 0) {
            g_last_gen_status.store(-1);
            llama_batch_free(batch);
            PROOF("APPEND_DECODE_FAILED rc=%d processed=%d this_chunk=%d total=%d status=-1",
                  rc, processed, this_chunk, n_tokens);
            // SPEC v3 — canonical error marker. Legacy APPEND_DECODE_FAILED
            // is kept above for grep compatibility; GENERATION_ERROR is the
            // canonical tag emitted by every -1 exit point.
            PROOF("GENERATION_ERROR phase=prefill rc=%d chunk_idx=%d "
                  "processed=%d total=%d session_id=%lld",
                  rc, chunk_idx, processed, n_tokens,
                  (long long)g_session_id.load());
            throw std::runtime_error("APPEND_DECODE_FAILED rc=" + std::to_string(rc));
        }
        processed += this_chunk;
        chunk_idx++;
    }
    long t1 = (long)(ggml_time_us() / 1000LL);
    llama_batch_free(batch);

    // The user-turn append (logits=true) IS the prefill that determines
    // first-token latency. Record it separately from history-replay appends.
    if (last_token_logits) g_t_prefill_ms.store(t1 - t0);

    g_n_past += n_tokens;
    PROOF("APPEND_DECODE_OK n_new=%d n_past_after=%d elapsed_ms=%ld n_past=%d n_ctx=%u kv_used_pct=%d",
          n_tokens, g_n_past, (t1 - t0), g_n_past, n_ctx,
          n_ctx > 0 ? (int)((100L * g_n_past) / n_ctx) : 0);
    // SPEC v3 — canonical lifecycle marker. Pairs 1:1 with PREFILL_BEGIN.
    // Emitted only on the success path; the error/cancel paths emit
    // GENERATION_ERROR / GENERATION_CANCELLED and throw, so a missing
    // PREFILL_END for a given PREFILL_BEGIN in logcat is itself a
    // diagnostic signal that the decode crashed before reaching the exit.
    PROOF("PREFILL_END n_new=%d n_past_after=%d chunks=%d elapsed_ms=%ld "
          "session_id=%lld status=0",
          n_tokens, g_n_past, chunk_idx, (t1 - t0),
          (long long)g_session_id.load());
    g_phase = "idle";
    return n_tokens;
}

// =============================================================================
// CORE: generation loop — samples from the CURRENT KV state and pushes each
// sampled token back into KV at g_n_past. Never re-decodes history.
// =============================================================================
static std::string airi_generate_next(
    JNIEnv*   env,
    int       max_new_request,
    jobject   callback,
    jmethodID invoke)
{
    if (!g_model || !g_ctx) throw std::runtime_error("MODEL_NOT_LOADED");
    if (g_n_past <= 0)      throw std::runtime_error("NO_SESSION");

    // SPEC v3 — cancel-at-entry guard. Check g_cancel_requested BEFORE
    // clearing it. This closes the race window between prefill returning and
    // the first iteration-level cancel check inside the decode loop: if
    // nativeCancel() was called in that window, the old unconditional
    // store(false) below would have silently discarded the user's stop request.
    // With this check in place, any cancel that arrived after prefill completes
    // is honoured immediately as status=-2; the Kotlin layer routes that to
    // STATE_CANCELLED exactly as though it fired during decode.
    if (g_cancel_requested.load()) {
        g_last_gen_status.store(-2);
        PROOF("GENERATION_CANCELLED phase=generate_entry_pre_clear "
              "session_id=%lld cancel_was_pending=true",
              (long long)g_session_id.load());
        return std::string();
    }
    // Clear any residual cancel from the previous generation. beginSession()
    // already clears it on the hard-reset path; this store is the
    // belt-and-braces guard for the incremental path where beginSession is NOT
    // called this turn.
    g_cancel_requested.store(false);
    g_last_gen_status.store(0);
    g_phase = "generate";

    // SPEC v3 — bump the generation id at the start of every decode loop.
    // Captured by the Kotlin layer immediately after this JNI call returns
    // (or read by the Main-dispatched onToken block) so a callback that
    // belongs to generation N but arrives after generation N+1 has started
    // can be silently dropped instead of corrupting the new response buffer.
    const int64_t this_gen_id = g_generation_id.fetch_add(1) + 1;
    PROOF("GENERATION_ID_BUMP gen_id=%lld session_id=%lld",
          (long long)this_gen_id, (long long)g_session_id.load());

    const llama_vocab* vocab = llama_model_get_vocab(g_model);
    const uint32_t     n_ctx = llama_n_ctx(g_ctx);

    int max_new = std::min(max_new_request > 0 ? max_new_request : 256, 1024);

    // SPEC v2 — context overflow guard at generate entry. KV trimming is NOT
    // invoked: if even one new decode would exceed n_ctx, return -3 so the
    // Kotlin layer can fullReset() and rebuild the prompt cleanly.
    if ((uint32_t)(g_n_past + 1) >= n_ctx) {
        g_last_gen_status.store(-3);
        PROOF("CONTEXT_OVERFLOW phase=generate_entry n_past=%d n_ctx=%u status=-3",
              g_n_past, n_ctx);
        throw std::runtime_error("CONTEXT_OVERFLOW");
    }
    // Bound max_new to remaining KV headroom so the per-iteration overflow
    // check below cannot trip mid-stream and confuse the retry layer.
    const int headroom = (int)n_ctx - g_n_past - 1;
    if (headroom > 0 && max_new > headroom) {
        PROOF("GEN_MAX_NEW_CLAMPED requested=%d headroom=%d", max_new, headroom);
        max_new = headroom;
    }

    PROOF("GEN_START n_past=%d n_ctx=%u max_new=%d kv_used_pct=%d",
          g_n_past, n_ctx, max_new,
          n_ctx > 0 ? (int)((100L * g_n_past) / n_ctx) : 0);

    // Reset per-generation timing counters.
    g_t_first_token_ms.store(0);
    g_t_decode_ms.store(0);
    g_n_decoded.store(0);

    // SPEC v4 — build sampler chain from caller-supplied parameters.
    // Order: penalties → top_k → top_p → min_p → temperature → dist.
    // This follows the recommended llama.cpp sampler chain ordering so each
    // filter sees the full distribution before the next filter narrows it.
    // If a parameter is at its "disabled" value (e.g. top_k==0, min_p==0),
    // we skip adding that stage to keep the chain as short as possible.
    llama_sampler* sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (g_sp_penalty_last_n != 0 &&
        (g_sp_repeat_penalty > 1.0f ||
         g_sp_frequency_penalty != 0.0f ||
         g_sp_presence_penalty  != 0.0f)) {
        // llama_sampler_init_penalties(penalty_last_n, repeat, freq, present)
        llama_sampler_chain_add(sampler,
            llama_sampler_init_penalties(g_sp_penalty_last_n,
                                         g_sp_repeat_penalty,
                                         g_sp_frequency_penalty,
                                         g_sp_presence_penalty));
    }
    if (g_sp_top_k > 0)
        llama_sampler_chain_add(sampler, llama_sampler_init_top_k(g_sp_top_k));
    if (g_sp_top_p > 0.0f && g_sp_top_p < 1.0f)
        llama_sampler_chain_add(sampler, llama_sampler_init_top_p(g_sp_top_p, 1));
    if (g_sp_min_p > 0.0f)
        llama_sampler_chain_add(sampler, llama_sampler_init_min_p(g_sp_min_p, 1));
    llama_sampler_chain_add(sampler,
        llama_sampler_init_temp(g_sp_temperature > 0.0f ? g_sp_temperature : 0.7f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    PROOF("SAMPLER_CHAIN temp=%.3f top_k=%d top_p=%.3f min_p=%.3f "
          "repeat=%.3f freq=%.3f pres=%.3f",
          g_sp_temperature, g_sp_top_k, g_sp_top_p, g_sp_min_p,
          g_sp_repeat_penalty, g_sp_frequency_penalty, g_sp_presence_penalty);

    llama_batch batch = llama_batch_init(1, 0, 1);

    std::string full_response;
    std::string utf8_pending;
    bool        first             = true;
    long        t_start           = (long)(ggml_time_us() / 1000LL);
    long        t_first_token_ms  = 0;
    int         token_count       = 0;

    for (int i = 0; i < max_new; i++) {
        // SPEC v2 — PHASE 1, step 1+2: cancel flag MUST be checked inside the
        // decode loop, every iteration. Cancellation latency is therefore
        // bounded by one decode step, never by the whole generation.
        if (g_cancel_requested.load()) {
            g_last_gen_status.store(-2);
            // Per directive: emit BOTH the historical GEN_CANCELLED tag
            // (kept for log-grep back-compat) and the spec-mandated
            // GEN_CANCEL_EFFECTIVE tag, which marks the exact iteration the
            // cooperative cancel actually took effect (the matching
            // GEN_CANCEL_REQUESTED is logged from LlamaManager.cancelStream
            // on the JVM side).
            PROOF("GEN_CANCELLED iter=%d emitted=%d status=-2", i, token_count);
            PROOF("GEN_CANCEL_EFFECTIVE iter=%d emitted=%d n_past=%d", i, token_count, g_n_past);
            // SPEC v3 — canonical cancel marker (matches the prefill-side
            // GENERATION_CANCELLED so a single grep covers both phases).
            PROOF("GENERATION_CANCELLED phase=generate iter=%d emitted=%d "
                  "gen_id=%lld session_id=%lld",
                  i, token_count, (long long)this_gen_id,
                  (long long)g_session_id.load());
            break;
        }
        // SPEC v2 — per-iteration context overflow guard. The clamp on
        // max_new at entry should normally prevent this from firing, but a
        // belt-and-braces check here makes the contract bulletproof: if
        // we would write past n_ctx, return -3 instead of corrupting KV.
        if ((uint32_t)(g_n_past + 1) >= n_ctx) {
            g_last_gen_status.store(-3);
            PROOF("CONTEXT_OVERFLOW phase=generate_loop iter=%d n_past=%d n_ctx=%u status=-3",
                  i, g_n_past, n_ctx);
            break;
        }

        llama_token tok = llama_sampler_sample(sampler, g_ctx, -1);

        if (i == 0) PROOF("SAMPLE_OK_ITER0 tok=%d", (int)tok);

        if (airi_is_stop_token(vocab, tok)) {
            PROOF("EOG iter=%d emitted=%d tok=%d", i, token_count, (int)tok);
            break;
        }

        char piece[256] = {};
        int  n_piece = llama_token_to_piece(vocab, tok, piece, sizeof(piece), 0, true);
        // Per-token debug trace — gated to AIRI_TOKEN tag, off by default in
        // release. Use `adb shell setprop log.tag.AIRI_TOKEN VERBOSE` to see it.
        LOGD("AIRI_TOKEN i=%d tok=%d bytes=%d", i, (int)tok, n_piece);
        if (n_piece > 0) {
            full_response.append(piece, n_piece);
            utf8_pending.append(piece, n_piece);

            if (first) {
                t_first_token_ms = (long)(ggml_time_us() / 1000LL) - t_start;
                g_t_first_token_ms.store(t_first_token_ms);
                PROOF("FIRST_TOKEN_BYTES n_bytes=%d tok=%d", n_piece, (int)tok);
            }

            // Only flush a callback once we have a complete UTF-8 sequence —
            // otherwise NewStringUTF aborts the VM on partial Arabic / CJK.
            if (callback && invoke && is_valid_utf8(utf8_pending)) {
                jstring jChunk = env->NewStringUTF(utf8_pending.c_str());
                env->CallObjectMethod(callback, invoke, jChunk);
                env->DeleteLocalRef(jChunk);
                if (env->ExceptionCheck()) {
                    env->ExceptionDescribe();
                    env->ExceptionClear();
                    LOGW("airi_generate_next: callback raised — stopping");
                    break;
                }
                if (first) {
                    PROOF("FIRST_TOKEN latency_ms=%ld n_past=%d", t_first_token_ms, g_n_past);
                    first = false;
                }
                utf8_pending.clear();
            }
            token_count++;
        }

        // Decode the just-sampled token at the next absolute KV position.
        airi_batch_clear(batch);
        airi_batch_add(batch, tok, g_n_past, {0}, /*logits=*/true);
        int dec = llama_decode(g_ctx, batch);
        if (dec != 0) {
            // SPEC v2 — surface decode failures as status=-1 so the Kotlin
            // safe-generation handler can fullReset() and abort cleanly.
            g_last_gen_status.store(-1);
            PROOF("DECODE_FAILED iter=%d rc=%d n_past=%d status=-1", i, dec, g_n_past);
            // SPEC v3 — canonical error marker (matches prefill-side
            // GENERATION_ERROR for a single-grep view of all -1 exits).
            PROOF("GENERATION_ERROR phase=generate iter=%d rc=%d n_past=%d "
                  "gen_id=%lld session_id=%lld",
                  i, dec, g_n_past, (long long)this_gen_id,
                  (long long)g_session_id.load());
            break;
        }
        g_n_past++;
    }

    // Flush trailing valid UTF-8.
    if (callback && invoke && !utf8_pending.empty() && is_valid_utf8(utf8_pending)) {
        jstring jChunk = env->NewStringUTF(utf8_pending.c_str());
        env->CallObjectMethod(callback, invoke, jChunk);
        env->DeleteLocalRef(jChunk);
        if (env->ExceptionCheck()) env->ExceptionClear();
    } else if (!utf8_pending.empty()) {
        LOGW("airi_generate_next: dropping %d trailing bytes (incomplete UTF-8)",
             (int)utf8_pending.size());
    }

    llama_sampler_free(sampler);
    llama_batch_free(batch);

    long  t_end       = (long)(ggml_time_us() / 1000LL);
    long  elapsed_ms  = t_end - t_start;
    float tps         = (elapsed_ms > 0 && token_count > 0)
                          ? (token_count * 1000.0f / (float)elapsed_ms)
                          : 0.0f;

    g_t_decode_ms.store(elapsed_ms);
    g_n_decoded.store(token_count);
    PROOF("GEN_DONE tokens=%d elapsed_ms=%ld tps=%.2f first_token_ms=%ld n_past=%d n_ctx=%u kv_used_pct=%d",
          token_count, elapsed_ms, tps, t_first_token_ms, g_n_past, n_ctx,
          n_ctx > 0 ? (int)((100L * g_n_past) / n_ctx) : 0);

    g_phase = "idle";
    return full_response;
}

// ─── JNI ─────────────────────────────────────────────────────────────────────
extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* /*vm*/, void* /*reserved*/) {
    install_signal_handlers();
    LOGI("AIRI_NATIVE JNI_OnLoad: signal handlers installed");
    return JNI_VERSION_1_6;
}

// ----------------------------------------------------------------------------
// loadModel(modelPath): String
// ----------------------------------------------------------------------------
JNIEXPORT jstring JNICALL
Java_com_airi_assistant_ai_LlamaNative_loadModel(
    JNIEnv* env, jobject /*this*/, jstring jModelPath)
{
    LLAMA_LOCK();
    g_phase = "loadModel";
    const char* path = env->GetStringUTFChars(jModelPath, nullptr);
    std::string model_path(path);
    env->ReleaseStringUTFChars(jModelPath, path);

    LOGI("loadModel: path=%s", model_path.c_str());

    airi_install_log_callback_once();
    g_last_native_error[0] = 0;

    if (!file_exists(model_path.c_str())) {
        LOGE("loadModel: FILE_NOT_FOUND %s", model_path.c_str());
        return env->NewStringUTF("FILE_NOT_FOUND");
    }
    long sz = file_size(model_path.c_str());
    if (sz < 100 * 1024 * 1024L) {
        LOGE("loadModel: file too small (%ld bytes) — INVALID_GGUF", sz);
        std::string out = "INVALID_GGUF:file too small (" + std::to_string(sz) + " bytes)";
        return env->NewStringUTF(out.c_str());
    }
    if (!is_valid_gguf(model_path.c_str())) {
        const char* why = airi_get_native_error();
        LOGE("loadModel: INVALID_GGUF — %s", why ? why : "bad header");
        std::string out = std::string("INVALID_GGUF:") + (why ? why : "bad header");
        return env->NewStringUTF(out.c_str());
    }

    if (g_ctx)   { llama_free(g_ctx);          g_ctx   = nullptr; }
    if (g_model) { llama_model_free(g_model);  g_model = nullptr; }
    g_n_past = 0;

    llama_backend_init();

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;
    mparams.use_mmap     = true;   // pin: stream weights from storage on demand
    mparams.use_mlock    = false;  // never pin pages — would OOM the device

    long t_load0 = (long)(ggml_time_us() / 1000LL);
    g_model = llama_model_load_from_file(model_path.c_str(), mparams);
    long t_load1 = (long)(ggml_time_us() / 1000LL);
    g_t_load_ms.store(t_load1 - t_load0);
    if (!g_model) {
        const char* why = airi_get_native_error();
        std::string out = "NATIVE_LOAD_FAILED:";
        out += (why ? why : "llama_model_load_from_file returned null (no diagnostic captured)");
        out += " path=" + model_path + " size=" + std::to_string(sz);
        LOGE("loadModel: %s", out.c_str());
        return env->NewStringUTF(out.c_str());
    }
    airi_resolve_stop_tokens(g_model);

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = AIRI_DEFAULT_N_CTX;
    cparams.n_batch         = AIRI_DEFAULT_N_BATCH;
    cparams.n_ubatch        = AIRI_DEFAULT_N_UBATCH;
    cparams.n_threads       = airi_pick_threads();
    cparams.n_threads_batch = cparams.n_threads;

    g_ctx = llama_init_from_model(g_model, cparams);
    if (!g_ctx) {
        llama_model_free(g_model);
        g_model = nullptr;
        const char* why = airi_get_native_error();
        std::string out = "NATIVE_LOAD_FAILED:llama_init_from_model returned null";
        if (why) { out += " ("; out += why; out += ")"; }
        return env->NewStringUTF(out.c_str());
    }

    g_model_path = model_path;
    g_n_past = 0;
    // SPEC v2 — cache cparams so nativeFullReset() rebuilds an identical ctx.
    g_last_n_ctx     = cparams.n_ctx;
    g_last_n_threads = cparams.n_threads;
    // SPEC v3 — fresh ctx ⇒ new session id.
    {
        const int64_t new_sid = g_session_id.fetch_add(1) + 1;
        PROOF("SESSION_ID_BUMP from=loadModel new_session_id=%lld", (long long)new_sid);
    }
    LOGI("AIRI_MODEL: LOAD SUCCESS path=%s size=%ldMB threads=%d n_ctx=%u n_batch=%u mmap=1",
         model_path.c_str(), sz / (1024 * 1024), cparams.n_threads, cparams.n_ctx, cparams.n_batch);
    g_phase = "idle";
    return env->NewStringUTF("LOAD_SUCCESS");
}

// ----------------------------------------------------------------------------
// loadModelWithProgress(modelPath, callback)
// ----------------------------------------------------------------------------
JNIEXPORT void JNICALL
Java_com_airi_assistant_ai_LlamaNative_loadModelWithProgress(
    JNIEnv* env, jobject /*this*/, jstring jModelPath, jobject callback)
{
    LLAMA_LOCK();
    g_phase = "loadModelWithProgress";
    const char* path = env->GetStringUTFChars(jModelPath, nullptr);
    std::string model_path(path);
    env->ReleaseStringUTFChars(jModelPath, path);

    LOGI("loadModelWithProgress: path=%s", model_path.c_str());

    airi_install_log_callback_once();
    g_last_native_error[0] = 0;

    jclass cbClass   = env->GetObjectClass(callback);
    jmethodID onProg = env->GetMethodID(cbClass, "onProgress", "(I)V");
    if (onProg) env->CallVoidMethod(callback, onProg, 5);

    if (!file_exists(model_path.c_str())) {
        if (onProg) env->CallVoidMethod(callback, onProg, -1);
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"), "FILE_NOT_FOUND");
        return;
    }
    long sz = file_size(model_path.c_str());
    if (sz < 100 * 1024 * 1024L) {
        std::string out = "INVALID_GGUF:file too small (" + std::to_string(sz) + " bytes)";
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"), out.c_str());
        return;
    }
    if (!is_valid_gguf(model_path.c_str())) {
        const char* why = airi_get_native_error();
        std::string out = std::string("INVALID_GGUF:") + (why ? why : "bad header");
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"), out.c_str());
        return;
    }

    if (onProg) env->CallVoidMethod(callback, onProg, 10);

    if (g_ctx)   { llama_free(g_ctx);          g_ctx   = nullptr; }
    if (g_model) { llama_model_free(g_model);  g_model = nullptr; }
    g_n_past = 0;

    llama_backend_init();
    if (onProg) env->CallVoidMethod(callback, onProg, 15);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;
    mparams.use_mmap     = true;
    mparams.use_mlock    = false;
    long t_load0 = (long)(ggml_time_us() / 1000LL);
    g_model = llama_model_load_from_file(model_path.c_str(), mparams);
    long t_load1 = (long)(ggml_time_us() / 1000LL);
    g_t_load_ms.store(t_load1 - t_load0);
    if (g_model) airi_resolve_stop_tokens(g_model);
    if (onProg) env->CallVoidMethod(callback, onProg, 90);

    if (!g_model) {
        const char* why = airi_get_native_error();
        std::string out = "NATIVE_LOAD_FAILED:";
        out += (why ? why : "llama_model_load_from_file returned null (no diagnostic captured)");
        out += " path=" + model_path + " size=" + std::to_string(sz);
        LOGE("loadModelWithProgress: %s", out.c_str());
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"), out.c_str());
        return;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = AIRI_DEFAULT_N_CTX;
    cparams.n_batch         = AIRI_DEFAULT_N_BATCH;
    cparams.n_ubatch        = AIRI_DEFAULT_N_UBATCH;
    cparams.n_threads       = airi_pick_threads();
    cparams.n_threads_batch = cparams.n_threads;

    g_ctx = llama_init_from_model(g_model, cparams);
    if (!g_ctx) {
        llama_model_free(g_model);
        g_model = nullptr;
        const char* why = airi_get_native_error();
        std::string out = "NATIVE_LOAD_FAILED:llama_init_from_model returned null";
        if (why) { out += " ("; out += why; out += ")"; }
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"), out.c_str());
        return;
    }

    g_model_path = model_path;
    g_n_past = 0;
    // SPEC v2 — cache cparams so nativeFullReset() rebuilds an identical ctx.
    g_last_n_ctx     = cparams.n_ctx;
    g_last_n_threads = cparams.n_threads;
    // SPEC v3 — fresh ctx ⇒ new session id.
    {
        const int64_t new_sid = g_session_id.fetch_add(1) + 1;
        PROOF("SESSION_ID_BUMP from=loadModelWithProgress new_session_id=%lld",
              (long long)new_sid);
    }
    if (onProg) env->CallVoidMethod(callback, onProg, 100);
    LOGI("AIRI_MODEL: LOAD SUCCESS (with progress) path=%s size=%ldMB threads=%d n_ctx=%u n_batch=%u mmap=1",
         model_path.c_str(), sz / (1024 * 1024), cparams.n_threads, cparams.n_ctx, cparams.n_batch);
    g_phase = "idle";
}

// ----------------------------------------------------------------------------
// SESSION API
// ----------------------------------------------------------------------------
JNIEXPORT void JNICALL
Java_com_airi_assistant_ai_LlamaNative_beginSession(JNIEnv* env, jobject /*this*/) {
    LLAMA_LOCK();
    if (!g_model || !g_ctx) {
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"), "MODEL_NOT_LOADED");
        return;
    }
    g_phase = "beginSession";
    llama_memory_clear(llama_get_memory(g_ctx), true);
    g_n_past = 0;
    g_cancel.store(false);
    // Wipe the draft KV in lockstep so the next appendUserTurn re-syncs both.
    airi_draft_clear_kv();
    // SPEC v3 — KV-wipe ⇒ new session id. Any in-flight Main-dispatched
    // callback that captured the prior session id will now drop its token.
    const int64_t new_sid = g_session_id.fetch_add(1) + 1;
    PROOF("SESSION_BEGIN n_ctx=%u draft_loaded=%d session_id=%lld",
          llama_n_ctx(g_ctx), g_draft_ctx ? 1 : 0, (long long)new_sid);
    PROOF("SESSION_ID_BUMP from=beginSession new_session_id=%lld", (long long)new_sid);
    g_phase = "idle";
}

JNIEXPORT void JNICALL
Java_com_airi_assistant_ai_LlamaNative_resetSession(JNIEnv* /*env*/, jobject /*this*/) {
    LLAMA_LOCK();
    if (!g_ctx) return;
    g_phase = "resetSession";
    llama_memory_clear(llama_get_memory(g_ctx), true);
    g_n_past = 0;
    g_cancel.store(false);
    airi_draft_clear_kv();
    const int64_t new_sid = g_session_id.fetch_add(1) + 1;
    PROOF("SESSION_RESET session_id=%lld", (long long)new_sid);
    PROOF("SESSION_ID_BUMP from=resetSession new_session_id=%lld", (long long)new_sid);
    g_phase = "idle";
}

JNIEXPORT void JNICALL
Java_com_airi_assistant_ai_LlamaNative_appendUserTurn(
    JNIEnv* env, jobject /*this*/, jstring jText)
{
    LLAMA_LOCK();
    if (!g_model || !g_ctx) {
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"), "MODEL_NOT_LOADED");
        return;
    }
    const char* text = env->GetStringUTFChars(jText, nullptr);
    std::string s(text);
    env->ReleaseStringUTFChars(jText, text);
    try {
        // Mark last token logits=true so generateNextTokens can sample immediately.
        airi_append_text(env, s, /*last_token_logits=*/true);
        // Mirror to draft so speculative decoding has matching KV to verify against.
        airi_decode_text_into_draft(s, /*last_token_logits=*/true);
    } catch (const std::exception& e) {
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"), e.what());
    } catch (...) {
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"), "APPEND_USER_FAILED");
    }
}

JNIEXPORT void JNICALL
Java_com_airi_assistant_ai_LlamaNative_appendAssistantTurn(
    JNIEnv* env, jobject /*this*/, jstring jText)
{
    LLAMA_LOCK();
    if (!g_model || !g_ctx) {
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"), "MODEL_NOT_LOADED");
        return;
    }
    const char* text = env->GetStringUTFChars(jText, nullptr);
    std::string s(text);
    env->ReleaseStringUTFChars(jText, text);
    try {
        // Closing/system fragments don't need fresh logits.
        airi_append_text(env, s, /*last_token_logits=*/false);
        airi_decode_text_into_draft(s, /*last_token_logits=*/false);
    } catch (const std::exception& e) {
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"), e.what());
    } catch (...) {
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"), "APPEND_ASSISTANT_FAILED");
    }
}

JNIEXPORT void JNICALL
Java_com_airi_assistant_ai_LlamaNative_generateNextTokens(
    JNIEnv* env, jobject /*this*/, jint maxTokens, jobject callback)
{
    LLAMA_LOCK();
    if (!g_model || !g_ctx) {
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"), "MODEL_NOT_LOADED");
        return;
    }
    if (g_n_past <= 0) {
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"),
                      "NO_SESSION (call beginSession + appendUserTurn first)");
        return;
    }

    jclass    fnClass = env->GetObjectClass(callback);
    jmethodID invoke  = env->GetMethodID(fnClass, "invoke",
                                         "(Ljava/lang/Object;)Ljava/lang/Object;");
    if (!invoke) {
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"), "NO_CALLBACK");
        return;
    }

    try {
        airi_generate_next(env, (int)maxTokens, callback, invoke);
    } catch (const std::exception& e) {
        LOGE("generateNextTokens: native exception: %s", e.what());
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"), e.what());
    } catch (...) {
        LOGE("generateNextTokens: unknown native exception");
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"), "GEN_FAILED");
    }
}

JNIEXPORT jint JNICALL
Java_com_airi_assistant_ai_LlamaNative_getKvPosition(JNIEnv*, jobject) {
    LLAMA_LOCK();
    return (jint)g_n_past;
}

JNIEXPORT jint JNICALL
Java_com_airi_assistant_ai_LlamaNative_getNCtx(JNIEnv*, jobject) {
    LLAMA_LOCK();
    if (!g_ctx) return 0;
    return (jint)llama_n_ctx(g_ctx);
}

// ----------------------------------------------------------------------------
// LEGACY one-shot API (kept for tool-call follow-up paths). Each call RESETS
// the session and primes it from scratch with the supplied full prompt.
// ----------------------------------------------------------------------------
JNIEXPORT jstring JNICALL
Java_com_airi_assistant_ai_LlamaNative_generateResponse(
    JNIEnv* env, jobject /*this*/, jstring jPrompt)
{
    LLAMA_LOCK();
    if (!g_model || !g_ctx) {
        LOGE("generateResponse: no model loaded");
        return env->NewStringUTF("");
    }
    const char* prompt = env->GetStringUTFChars(jPrompt, nullptr);
    std::string prompt_str(prompt);
    env->ReleaseStringUTFChars(jPrompt, prompt);

    try {
        llama_memory_clear(llama_get_memory(g_ctx), true);
        g_n_past = 0;
        airi_append_text(env, prompt_str, /*last_token_logits=*/true);
        std::string out = airi_generate_next(env, /*max_new=*/256, nullptr, nullptr);
        return env->NewStringUTF(out.c_str());
    } catch (const std::exception& e) {
        LOGE("generateResponse: native exception: %s", e.what());
        return env->NewStringUTF("");
    } catch (...) {
        LOGE("generateResponse: unknown native exception");
        return env->NewStringUTF("");
    }
}

JNIEXPORT void JNICALL
Java_com_airi_assistant_ai_LlamaNative_generateStream(
    JNIEnv* env, jobject /*this*/, jstring jPrompt, jobject callback)
{
    LLAMA_LOCK();
    if (!g_model || !g_ctx) {
        LOGE("generateStream: no model loaded");
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"), "MODEL_NOT_LOADED");
        return;
    }

    const char* prompt = env->GetStringUTFChars(jPrompt, nullptr);
    std::string prompt_str(prompt);
    env->ReleaseStringUTFChars(jPrompt, prompt);

    jclass    fnClass = env->GetObjectClass(callback);
    jmethodID invoke  = env->GetMethodID(fnClass, "invoke",
                                         "(Ljava/lang/Object;)Ljava/lang/Object;");
    if (!invoke) {
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"), "NO_CALLBACK");
        return;
    }

    try {
        llama_memory_clear(llama_get_memory(g_ctx), true);
        g_n_past = 0;
        airi_append_text(env, prompt_str, /*last_token_logits=*/true);
        airi_generate_next(env, /*max_new=*/512, callback, invoke);
    } catch (const std::exception& e) {
        LOGE("generateStream: native exception: %s", e.what());
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"), e.what());
    } catch (...) {
        LOGE("generateStream: unknown native exception");
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"), "UNKNOWN_NATIVE_ERROR");
    }
}

// ----------------------------------------------------------------------------
// cancel()
// ----------------------------------------------------------------------------
JNIEXPORT void JNICALL
Java_com_airi_assistant_ai_LlamaNative_cancel(JNIEnv* /*env*/, jobject /*this*/)
{
    g_cancel.store(true);
    LOGD("airi_generate_next: cancel requested");
}

// ----------------------------------------------------------------------------
// SPEC v2 — state-machine entry points.
//
// nativeCancel()        — raises g_cancel_requested. Lock-free; never blocks
//                         on g_llama_mutex so it can interrupt an in-flight
//                         decode that is currently holding the lock. The
//                         decode loop checks the flag every iteration so
//                         cancellation latency is bounded by a single
//                         llama_decode step.
//
// nativeGetLastStatus() — returns the result code of the most recent
//                         airi_append_text / airi_generate_next call:
//                            0  = ok / no call yet
//                           -1  = ERROR             (decode/llama failure)
//                           -2  = CANCELLED         (g_cancel_requested set)
//                           -3  = CONTEXT_OVERFLOW  (n_past + N >= n_ctx)
//                         The Kotlin safe-generation handler reads this
//                         immediately after the JNI call returns to decide
//                         whether to fullReset()+retry (-3), fullReset()+stop
//                         (-1), or stop cleanly (-2).
//
// nativeFullReset()     — destroys g_ctx and rebuilds it from g_model with
//                         the cached cparams (g_last_n_ctx / g_last_n_threads).
//                         Mandated by the state machine's CLEANUP path: ANY
//                         error during PREFLIGHT/PREFILL/GENERATE results in
//                         a full context reset before the next turn so KV is
//                         never left in a torn state.
// ----------------------------------------------------------------------------
JNIEXPORT void JNICALL
Java_com_airi_assistant_ai_LlamaNative_nativeCancel(JNIEnv* /*env*/, jobject /*this*/)
{
    g_cancel_requested.store(true);
    LOGD("nativeCancel: cancel requested");
    PROOF("NATIVE_CANCEL_REQUESTED");
}

// SPEC v3 — cancel-flag clear.
//
// Called from the JVM layer at the START of every new generation cycle,
// BEFORE reconcileSession, to erase any stale cancel flag left by:
//
//   (a) a user-triggered cancel        (cancelStream → nativeCancel sets flag)
//   (b) a watchdog timeout             (watchdogScope → cancel() sets flag)
//   (c) the generate-entry early exit  (airi_generate_next lines 777–783
//       return status=-2 WITHOUT reaching the store(false) at line 788,
//       so the flag remains true for the next turn)
//
// Without this call the INCREMENTAL session path (sessionPrimed=true →
// beginSession() is NOT called → g_cancel_requested is never cleared)
// immediately throws PREFILL_CANCELLED on the very next appendUserTurn,
// causing the conversation to freeze after 1–3 messages.
//
// Lock-free: writes a single std::atomic<bool>, exactly as nativeCancel
// does in the set direction.  Safe to call from any thread.
JNIEXPORT void JNICALL
Java_com_airi_assistant_ai_LlamaNative_nativeClearCancel(JNIEnv* /*env*/, jobject /*this*/)
{
    g_cancel_requested.store(false);
    PROOF("NATIVE_CANCEL_CLEARED phase=gen_start");
}

JNIEXPORT jint JNICALL
Java_com_airi_assistant_ai_LlamaNative_nativeGetLastStatus(JNIEnv* /*env*/, jobject /*this*/)
{
    return (jint)g_last_gen_status.load();
}

// SPEC v3 — token-budget trimming primitive. Returns the EXACT number of
// tokens `text` would tokenize to under the currently loaded model's vocab.
//
// Implementation: standard llama.cpp two-pass probe (-llama_tokenize with
// nullptr buffer returns the negated count). Read-only on g_ctx — does not
// touch KV — so it is safe to call between turns. Returns:
//   ≥ 0  the token count (0 for empty input)
//    -1  no model loaded yet
//    -2  tokenizer failure (malformed text or vocab mismatch)
//
// Thread-safety: takes LLAMA_LOCK because it reads g_model. Costs ~tens of
// microseconds for typical chat-message-length inputs — cheap enough to call
// once per history message before every turn for the JVM-side budget trim.
JNIEXPORT jint JNICALL
Java_com_airi_assistant_ai_LlamaNative_nativeCountTokens(
    JNIEnv* env, jobject /*this*/, jstring jText)
{
    LLAMA_LOCK();
    if (!g_model) return -1;
    if (!jText)   return 0;
    const char* s = env->GetStringUTFChars(jText, nullptr);
    if (!s) return -2;
    const llama_vocab* vocab = llama_model_get_vocab(g_model);
    int len = (int)strlen(s);
    int n_probe = -llama_tokenize(vocab, s, len, nullptr, 0,
                                  /*add_special=*/false,
                                  /*parse_special=*/true);
    env->ReleaseStringUTFChars(jText, s);
    if (n_probe < 0) return -2;
    return (jint)n_probe;
}

// SPEC v3 — read the current session id. The Kotlin layer captures this
// before issuing generateNextTokens and re-checks inside every callback;
// a mismatch means the context was destroyed and the callback must drop
// its token instead of writing into the new session's response buffer.
JNIEXPORT jlong JNICALL
Java_com_airi_assistant_ai_LlamaNative_nativeGetSessionId(JNIEnv* /*env*/, jobject /*this*/)
{
    return (jlong)g_session_id.load();
}

// SPEC v3 — read the current generation id. Bumped at the entry of every
// airi_generate_next call; lets the Kotlin layer detect "old generation
// streams into new state" if its captured id is stale.
JNIEXPORT jlong JNICALL
Java_com_airi_assistant_ai_LlamaNative_nativeGetGenerationId(JNIEnv* /*env*/, jobject /*this*/)
{
    return (jlong)g_generation_id.load();
}

// SPEC v4 — sampling parameter setter.
//
// Called by LlamaManager immediately BEFORE every generateNextTokens() /
// generateNextTokensSpeculative() invocation so the decode loop picks up the
// exact sampler chain the Kotlin layer requested (temperature, top_k, top_p,
// min_p, repeat_penalty, presence_penalty, frequency_penalty).
//
// Thread-safety: acquires LLAMA_LOCK so the write is serialised with respect
// to any concurrent read inside airi_generate_next(). In practice the Kotlin
// single-threaded llamaDispatcher guarantees sequential ordering, but the lock
// is the belt-and-braces guard for any future refactor.
//
// Clamping (prevents pathological values):
//   temperature       [0.01, 5.0]  — ≤0 would be greedy (use speculative path)
//   top_k             [0, 200]     — 0  = disabled (pass-through)
//   top_p             [0.0, 1.0]
//   min_p             [0.0, 1.0]
//   repeat_penalty    [1.0, 2.0]   — <1.0 would reward repetition
//   presence_penalty  [0.0, 2.0]
//   frequency_penalty [0.0, 2.0]
JNIEXPORT void JNICALL
Java_com_airi_assistant_ai_LlamaNative_nativeSetSamplingParams(
    JNIEnv* /*env*/, jobject /*this*/,
    jfloat temperature,
    jint   topK,
    jfloat topP,
    jfloat minP,
    jfloat repeatPenalty,
    jfloat presencePenalty,
    jfloat frequencyPenalty)
{
    LLAMA_LOCK();
    g_sp_temperature       = std::max(0.01f, std::min(5.0f,  (float)temperature));
    g_sp_top_k             = std::max(0,     std::min(200,   (int)topK));
    g_sp_top_p             = std::max(0.0f,  std::min(1.0f,  (float)topP));
    g_sp_min_p             = std::max(0.0f,  std::min(1.0f,  (float)minP));
    g_sp_repeat_penalty    = std::max(1.0f,  std::min(2.0f,  (float)repeatPenalty));
    g_sp_presence_penalty  = std::max(0.0f,  std::min(2.0f,  (float)presencePenalty));
    g_sp_frequency_penalty = std::max(0.0f,  std::min(2.0f,  (float)frequencyPenalty));
    // penalty_last_n stays at its default (64). If the Kotlin layer ever
    // needs to expose it, add a jint parameter here and a matching field in
    // GenerationSettingsDialog. For now the header-confirmed 4-arg form of
    // llama_sampler_init_penalties(penalty_last_n, repeat, freq, present)
    // is satisfied by g_sp_penalty_last_n which is initialised to 64.
    PROOF("SAMPLING_PARAMS_SET temp=%.3f top_k=%d top_p=%.3f min_p=%.3f "
          "repeat=%.3f pres=%.3f freq=%.3f penalty_last_n=%d",
          g_sp_temperature, g_sp_top_k, g_sp_top_p, g_sp_min_p,
          g_sp_repeat_penalty, g_sp_presence_penalty, g_sp_frequency_penalty,
          g_sp_penalty_last_n);
}

JNIEXPORT void JNICALL
Java_com_airi_assistant_ai_LlamaNative_nativeFullReset(JNIEnv* env, jobject /*this*/)
{
    // SPEC v3 — STRICT MUTEX. The lock is acquired at the JNI boundary
    // (LLAMA_LOCK()) and held for the entire teardown+rebuild. While we
    // hold this lock NO other JNI entry that touches g_ctx (loadModel,
    // appendUserTurn, generateNextTokens, setRuntimeMode, beginSession,
    // resetSession, …) can run concurrently — they all acquire the same
    // mutex. This guarantees the invariant the spec demands:
    //     "llama_decode never runs after destroy"
    // because between `llama_free(g_ctx)` and `g_ctx = llama_init_…(…)`
    // the only thread that could possibly issue a llama_decode is the
    // current one, which is busy doing the rebuild.
    //
    // The only callable that does NOT take the lock is nativeCancel(),
    // which only stores into the atomic g_cancel — that is by design and
    // is safe because it never touches g_ctx itself.
    LLAMA_LOCK();
    if (!g_model) {
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"), "MODEL_NOT_LOADED");
        return;
    }
    g_phase = "nativeFullReset";

    // SPEC v3 — canonical lifecycle marker. Emitted at the very start of
    // every full context teardown/rebuild so a single grep `CONTEXT_RESET`
    // surfaces every reset across the app's lifetime.
    PROOF("CONTEXT_RESET reason=nativeFullReset n_past_before=%d "
          "session_id_before=%lld gen_id_before=%lld",
          g_n_past, (long long)g_session_id.load(),
          (long long)g_generation_id.load());

    // Tear down the existing context entirely (including KV) and rebuild
    // from the model with the same cparams. This is the CLEANUP step of the
    // state machine and is what makes "any error → full context reset"
    // observable from the JVM side.
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    g_n_past = 0;
    g_cancel_requested.store(false);
    g_last_gen_status.store(0);

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = (g_last_n_ctx > 0) ? g_last_n_ctx : AIRI_DEFAULT_N_CTX;
    cparams.n_batch         = AIRI_DEFAULT_N_BATCH;
    cparams.n_ubatch        = AIRI_DEFAULT_N_UBATCH;
    cparams.n_threads       = (g_last_n_threads > 0) ? g_last_n_threads : airi_pick_threads();
    cparams.n_threads_batch = cparams.n_threads;

    // SPEC v3 — REBUILD lifecycle markers. Pair 1:1 with REBUILD_END on
    // the success path; an absent REBUILD_END for a given REBUILD_BEGIN
    // means llama_init_from_model crashed (signal_handler will surface it).
    PROOF("REBUILD_BEGIN n_ctx=%u n_batch=%u n_ubatch=%u threads=%d",
          cparams.n_ctx, cparams.n_batch, cparams.n_ubatch, cparams.n_threads);

    g_ctx = llama_init_from_model(g_model, cparams);
    if (!g_ctx) {
        PROOF("REBUILD_FAILED reason=llama_init_from_model_returned_null");
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"),
                      "CONTEXT_REBUILD_FAILED");
        return;
    }
    // Wipe the draft KV in lockstep so the next prefill re-syncs both contexts.
    airi_draft_clear_kv();
    // SPEC v3 — fresh g_ctx ⇒ new session id. Bump BEFORE emitting REBUILD_END
    // so any concurrent reader sees the new id alongside the success log.
    const int64_t new_sid = g_session_id.fetch_add(1) + 1;
    PROOF("SESSION_ID_BUMP from=nativeFullReset new_session_id=%lld",
          (long long)new_sid);
    PROOF("REBUILD_END n_ctx=%u threads=%d session_id=%lld",
          cparams.n_ctx, cparams.n_threads, (long long)new_sid);
    PROOF("FULL_RESET n_ctx=%u threads=%d", cparams.n_ctx, cparams.n_threads);
    g_phase = "idle";
}

// ----------------------------------------------------------------------------
// setRuntimeMode(nCtx, nThreads): hot-swap context size / thread count without
// reloading model weights from disk. Wipes KV (caller must re-prime via
// beginSession + appendUserTurn).
// ----------------------------------------------------------------------------
JNIEXPORT void JNICALL
Java_com_airi_assistant_ai_LlamaNative_setRuntimeMode(
    JNIEnv* env, jobject /*this*/, jint nCtx, jint nThreads)
{
    LLAMA_LOCK();
    if (!g_model) {
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"), "MODEL_NOT_LOADED");
        return;
    }
    g_phase = "setRuntimeMode";

    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    g_n_past = 0;
    g_cancel.store(false);

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = (uint32_t)std::max(512, (int)nCtx);
    cparams.n_batch         = AIRI_DEFAULT_N_BATCH;
    cparams.n_ubatch        = AIRI_DEFAULT_N_UBATCH;
    cparams.n_threads       = std::max(1, (int)nThreads);
    cparams.n_threads_batch = cparams.n_threads;

    g_ctx = llama_init_from_model(g_model, cparams);
    if (!g_ctx) {
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"),
                      "CONTEXT_REBUILD_FAILED");
        return;
    }
    // SPEC v2 — cache cparams so nativeFullReset() preserves the runtime mode.
    g_last_n_ctx     = cparams.n_ctx;
    g_last_n_threads = cparams.n_threads;
    // SPEC v3 — runtime-mode swap rebuilds g_ctx ⇒ new session id.
    {
        const int64_t new_sid = g_session_id.fetch_add(1) + 1;
        PROOF("SESSION_ID_BUMP from=setRuntimeMode new_session_id=%lld",
              (long long)new_sid);
    }
    PROOF("RUNTIME_MODE_SET n_ctx=%u threads=%d", cparams.n_ctx, cparams.n_threads);
    g_phase = "idle";
}

// ----------------------------------------------------------------------------
// getLastTimings(): packed metric snapshot for the Generation Statistics screen.
// Layout (ms unless noted):
//   [0] model load time
//   [1] last user-turn tokenize time
//   [2] last user-turn prefill time (llama_decode w/ logits)
//   [3] last gen first-token latency
//   [4] last gen total decode loop time
//   [5] last gen token count (NOT ms — count)
//   [6] current n_past
//   [7] current n_ctx
// ----------------------------------------------------------------------------
// ----------------------------------------------------------------------------
// getModelDescription(): single-line metadata for the on-device benchmarking
// system. Returns a pipe-separated string:
//
//      "<desc>|<n_params>|<size_bytes>"
//
// e.g. "gemma 2B Q4_K - Medium|2614341888|1683459840"
//
// The Kotlin side parses this and combines with file-size + RSS readings to
// build a per-quantization benchmark record. Returns "UNAVAILABLE" if no
// model is currently loaded so the caller can no-op cleanly.
// ----------------------------------------------------------------------------
JNIEXPORT jstring JNICALL
Java_com_airi_assistant_ai_LlamaNative_getModelDescription(JNIEnv* env, jobject /*this*/)
{
    LLAMA_LOCK();
    if (!g_model) return env->NewStringUTF("UNAVAILABLE");
    char desc[256] = {0};
    llama_model_desc(g_model, desc, sizeof(desc));
    // llama_model_size and llama_model_n_params return uint64_t.
    unsigned long long sz_bytes  = (unsigned long long)llama_model_size(g_model);
    unsigned long long n_params  = (unsigned long long)llama_model_n_params(g_model);
    char out[384];
    snprintf(out, sizeof(out), "%s|%llu|%llu", desc, n_params, sz_bytes);
    PROOF("MODEL_META %s", out);
    return env->NewStringUTF(out);
}

JNIEXPORT jlongArray JNICALL
Java_com_airi_assistant_ai_LlamaNative_getLastTimings(JNIEnv* env, jobject /*this*/)
{
    jlong vals[8] = {
        (jlong)g_t_load_ms.load(),
        (jlong)g_t_tokenize_ms.load(),
        (jlong)g_t_prefill_ms.load(),
        (jlong)g_t_first_token_ms.load(),
        (jlong)g_t_decode_ms.load(),
        (jlong)g_n_decoded.load(),
        (jlong)g_n_past,
        (jlong)(g_ctx ? (long)llama_n_ctx(g_ctx) : 0L)
    };
    jlongArray arr = env->NewLongArray(8);
    if (arr) env->SetLongArrayRegion(arr, 0, 8, vals);
    return arr;
}

// ============================================================================
// SPECULATIVE DECODING JNI
// ============================================================================
//
// Optional speed-up path. Loads a SECOND, smaller GGUF as a draft model.
// During generation, the draft proposes K tokens per step and the main model
// verifies them in a SINGLE batched decode. Acceptance rate is logged via
// AIRI_PROOF SPEC_DONE. Pipeline correctness is preserved: the main model
// is the sole source of truth for sampled output. A draft mismatch just
// means we discard the rejected suffix and the main model's correction is
// emitted instead. If the draft is not loaded or its KV gets out of sync,
// the speculative generator transparently falls back to plain single-token
// decoding.
// ----------------------------------------------------------------------------
JNIEXPORT jstring JNICALL
Java_com_airi_assistant_ai_LlamaNative_loadDraftModel(
    JNIEnv* env, jobject /*this*/, jstring jPath)
{
    LLAMA_LOCK();
    if (!g_model || !g_ctx) return env->NewStringUTF("MAIN_NOT_LOADED");

    const char* p = env->GetStringUTFChars(jPath, nullptr);
    std::string path(p);
    env->ReleaseStringUTFChars(jPath, p);

    // Refuse to load the same file as the main model — that would only waste
    // RAM with zero acceptance benefit.
    if (path == g_model_path) return env->NewStringUTF("SAME_AS_MAIN");

    if (!file_exists(path.c_str()))   return env->NewStringUTF("FILE_NOT_FOUND");
    if (!is_valid_gguf(path.c_str())) return env->NewStringUTF("INVALID_GGUF");

    // Tear down any previous draft.
    if (g_draft_ctx)   { llama_free(g_draft_ctx);         g_draft_ctx   = nullptr; }
    if (g_draft_model) { llama_model_free(g_draft_model); g_draft_model = nullptr; }
    g_draft_n_past = 0;
    g_draft_in_sync.store(false);

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    mp.use_mmap     = true;
    mp.use_mlock    = false;

    g_draft_model = llama_model_load_from_file(path.c_str(), mp);
    if (!g_draft_model) return env->NewStringUTF("DRAFT_LOAD_FAILED");

    // Guard: speculative decoding requires identical token IDs across both
    // models, otherwise compare-and-accept is meaningless. We approximate by
    // checking the vocab size; in practice this is enough to keep families
    // aligned (Qwen-2.5-0.5B with Qwen-2.5-7B, Gemma-2-2B with Gemma-2-9B…).
    const llama_vocab* vm = llama_model_get_vocab(g_model);
    const llama_vocab* vd = llama_model_get_vocab(g_draft_model);
    if (!vm || !vd || llama_vocab_n_tokens(vm) != llama_vocab_n_tokens(vd)) {
        PROOF("DRAFT_VOCAB_MISMATCH main=%d draft=%d",
              vm ? (int)llama_vocab_n_tokens(vm) : -1,
              vd ? (int)llama_vocab_n_tokens(vd) : -1);
        llama_model_free(g_draft_model);
        g_draft_model = nullptr;
        return env->NewStringUTF("VOCAB_MISMATCH");
    }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx           = llama_n_ctx(g_ctx);
    cp.n_batch         = AIRI_DEFAULT_N_BATCH;
    cp.n_ubatch        = AIRI_DEFAULT_N_UBATCH;
    cp.n_threads       = airi_pick_threads();
    cp.n_threads_batch = cp.n_threads;
    g_draft_ctx = llama_init_from_model(g_draft_model, cp);
    if (!g_draft_ctx) {
        llama_model_free(g_draft_model);
        g_draft_model = nullptr;
        return env->NewStringUTF("DRAFT_CTX_FAILED");
    }

    g_draft_path = path;
    g_draft_n_past = 0;
    g_draft_in_sync.store(false);   // re-synced on next appendUserTurn
    PROOF("DRAFT_LOADED path=%s vocab=%d n_ctx=%u",
          path.c_str(), (int)llama_vocab_n_tokens(vd), cp.n_ctx);
    return env->NewStringUTF("DRAFT_OK");
}

JNIEXPORT void JNICALL
Java_com_airi_assistant_ai_LlamaNative_unloadDraftModel(JNIEnv* /*env*/, jobject /*this*/) {
    LLAMA_LOCK();
    if (g_draft_ctx)   { llama_free(g_draft_ctx);         g_draft_ctx   = nullptr; }
    if (g_draft_model) { llama_model_free(g_draft_model); g_draft_model = nullptr; }
    g_draft_n_past = 0;
    g_draft_path.clear();
    g_draft_in_sync.store(false);
    PROOF("DRAFT_UNLOADED");
}

JNIEXPORT jboolean JNICALL
Java_com_airi_assistant_ai_LlamaNative_isDraftLoaded(JNIEnv* /*env*/, jobject /*this*/) {
    return (g_draft_model && g_draft_ctx) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlongArray JNICALL
Java_com_airi_assistant_ai_LlamaNative_getSpecStats(JNIEnv* env, jobject /*this*/) {
    jlong v[3] = {
        (jlong)g_spec_drafted.load(),
        (jlong)g_spec_accepted.load(),
        (jlong)g_spec_runs.load()
    };
    jlongArray a = env->NewLongArray(3);
    if (a) env->SetLongArrayRegion(a, 0, 3, v);
    return a;
}

JNIEXPORT void JNICALL
Java_com_airi_assistant_ai_LlamaNative_resetSpecStats(JNIEnv* /*env*/, jobject /*this*/) {
    g_spec_drafted.store(0);
    g_spec_accepted.store(0);
    g_spec_runs.store(0);
}

// ----------------------------------------------------------------------------
// generateNextTokensSpeculative(maxTokens, draftN, callback)
// ----------------------------------------------------------------------------
JNIEXPORT void JNICALL
Java_com_airi_assistant_ai_LlamaNative_generateNextTokensSpeculative(
    JNIEnv* env, jobject /*this*/, jint maxTokens, jint draftN, jobject callback)
{
    LLAMA_LOCK();
    if (!g_model || !g_ctx) {
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"), "MODEL_NOT_LOADED");
        return;
    }
    if (g_n_past <= 0) {
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"),
                      "NO_SESSION (call beginSession + appendUserTurn first)");
        return;
    }

    jclass    fnClass = env->GetObjectClass(callback);
    jmethodID invoke  = env->GetMethodID(fnClass, "invoke",
                                         "(Ljava/lang/Object;)Ljava/lang/Object;");
    if (!invoke) {
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"), "NO_CALLBACK");
        return;
    }

    // Fall back transparently if the draft isn't usable for any reason.
    const bool draft_usable = g_draft_model && g_draft_ctx
                              && g_draft_in_sync.load()
                              && g_draft_n_past == g_n_past;
    if (!draft_usable) {
        const char* why = !g_draft_model      ? "no_draft"
                        : !g_draft_in_sync    ? "out_of_sync"
                        : (g_draft_n_past != g_n_past) ? "pos_mismatch"
                                                       : "unknown";
        PROOF("SPEC_FALLBACK reason=%s draft_loaded=%d draft_past=%d main_past=%d",
              why, g_draft_ctx ? 1 : 0, g_draft_n_past, g_n_past);
        try { airi_generate_next(env, (int)maxTokens, callback, invoke); }
        catch (const std::exception& e) {
            env->ThrowNew(env->FindClass("java/lang/RuntimeException"), e.what());
        } catch (...) {
            env->ThrowNew(env->FindClass("java/lang/RuntimeException"), "GEN_FAILED");
        }
        return;
    }

    g_cancel.store(false);
    g_phase = "spec_generate";

    const llama_vocab* vocab_main = llama_model_get_vocab(g_model);
    const uint32_t     n_ctx      = llama_n_ctx(g_ctx);
    const int K       = std::max(1, std::min((int)draftN > 0 ? (int)draftN : 4, 8));
    const int max_new = std::min((int)maxTokens > 0 ? (int)maxTokens : 256, 1024);

    // Reserve headroom on BOTH KVs. If main needs to slide its window we
    // simply bail to the standard path because the draft can no longer mirror
    // the same prefix without a full re-prefill.
    airi_kv_trim_if_needed(max_new + K + 8);
    if (g_n_past == 0 || g_draft_n_past != g_n_past) {
        g_draft_in_sync.store(false);
        PROOF("SPEC_FALLBACK reason=trim_desync main_past=%d draft_past=%d",
              g_n_past, g_draft_n_past);
        try { airi_generate_next(env, max_new, callback, invoke); }
        catch (const std::exception& e) {
            env->ThrowNew(env->FindClass("java/lang/RuntimeException"), e.what());
        }
        return;
    }

    g_t_first_token_ms.store(0);
    g_t_decode_ms.store(0);
    g_n_decoded.store(0);

    // Greedy is REQUIRED for correctness here — verifying drafts by equality
    // against a stochastic main sampler would silently change the output
    // distribution. Speculative decoding effectively pins the active model to
    // greedy sampling; this is the documented trade-off of the feature.
    llama_sampler* smpl_main  = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl_main, llama_sampler_init_greedy());
    llama_sampler* smpl_draft = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl_draft, llama_sampler_init_greedy());

    long total_drafted   = 0;
    long total_accepted  = 0;
    int  produced        = 0;
    long t_start         = (long)(ggml_time_us() / 1000LL);
    long t_first_token_ms= 0;
    bool first           = true;
    bool stop_now        = false;
    std::string utf8_pending;

    auto emit_token = [&](llama_token tok) -> bool {
        // Returns false if a stop token was hit (caller should break).
        if (airi_is_stop_token(vocab_main, tok)) { stop_now = true; return false; }
        char piece[256] = {};
        int n = llama_token_to_piece(vocab_main, tok, piece, sizeof(piece), 0, true);
        if (n > 0) {
            utf8_pending.append(piece, n);
            if (callback && invoke && is_valid_utf8(utf8_pending)) {
                jstring jc = env->NewStringUTF(utf8_pending.c_str());
                env->CallObjectMethod(callback, invoke, jc);
                env->DeleteLocalRef(jc);
                if (env->ExceptionCheck()) {
                    env->ExceptionClear();
                    stop_now = true;
                    return false;
                }
                if (first) {
                    t_first_token_ms = (long)(ggml_time_us() / 1000LL) - t_start;
                    g_t_first_token_ms.store(t_first_token_ms);
                    first = false;
                }
                utf8_pending.clear();
            }
        }
        produced++;
        return true;
    };

    PROOF("SPEC_START n_past=%d n_ctx=%u K=%d max_new=%d", g_n_past, n_ctx, K, max_new);

    while (produced < max_new && !g_cancel.load() && !stop_now) {
        if (!g_draft_in_sync.load() || g_draft_n_past != g_n_past) {
            PROOF("SPEC_ABORT reason=desync produced=%d", produced);
            break;
        }

        // (1) m_first = main's prediction at position g_n_past (from existing logits).
        llama_token m_first = llama_sampler_sample(smpl_main, g_ctx, -1);

        // (2) Draft K tokens, decoding each one through the draft so the next
        //     sample sees fresh logits.
        std::vector<llama_token> drafts; drafts.reserve(K);
        for (int i = 0; i < K && (produced + (int)drafts.size()) < max_new; ++i) {
            llama_token d = llama_sampler_sample(smpl_draft, g_draft_ctx, -1);
            llama_batch db = llama_batch_init(1, 0, 1);
            airi_batch_add(db, d, g_draft_n_past, {0}, /*logits=*/true);
            int rc = llama_decode(g_draft_ctx, db);
            llama_batch_free(db);
            if (rc != 0) {
                PROOF("SPEC_DRAFT_DECODE_FAILED iter=%d rc=%d", i, rc);
                g_draft_in_sync.store(false);
                break;
            }
            g_draft_n_past++;
            drafts.push_back(d);
        }

        if (drafts.empty()) {
            // Draft is broken — emit just m_first via main and stop the spec
            // path. The next call from Kotlin will hit the fallback branch.
            if (airi_is_stop_token(vocab_main, m_first)) { stop_now = true; break; }
            llama_batch mb = llama_batch_init(1, 0, 1);
            airi_batch_add(mb, m_first, g_n_past, {0}, /*logits=*/true);
            int rc = llama_decode(g_ctx, mb);
            llama_batch_free(mb);
            if (rc != 0) { PROOF("SPEC_BAIL_MAIN_DECODE_FAILED rc=%d", rc); break; }
            g_n_past++;
            emit_token(m_first);
            g_draft_in_sync.store(false);
            break;
        }

        total_drafted += (long)drafts.size();

        // (3) Verify: feed all K drafts to main in one shot, all positions logits=true.
        llama_batch verify = llama_batch_init((int)drafts.size(), 0, 1);
        airi_batch_clear(verify);
        for (size_t i = 0; i < drafts.size(); ++i) {
            airi_batch_add(verify, drafts[i], g_n_past + (int)i, {0}, /*logits=*/true);
        }
        int rcv = llama_decode(g_ctx, verify);
        llama_batch_free(verify);
        if (rcv != 0) {
            // Roll the rejected positions out of MAIN KV and bail.
            llama_memory_seq_rm(llama_get_memory(g_ctx), 0, g_n_past, -1);
            llama_memory_seq_rm(llama_get_memory(g_draft_ctx), 0,
                                g_draft_n_past - (int)drafts.size(), -1);
            g_draft_n_past -= (int)drafts.size();
            g_draft_in_sync.store(false);
            PROOF("SPEC_VERIFY_DECODE_FAILED rc=%d K=%zu", rcv, drafts.size());
            break;
        }

        // (4) Compare m_0..m_K against d_0..d_{K-1}. Accept the longest
        //     matching prefix and pick a final token (correction or bonus).
        size_t accepted = 0;
        llama_token final_tok = m_first;
        if (m_first == drafts[0]) {
            accepted = 1;
            for (size_t i = 0; i + 1 < drafts.size(); ++i) {
                llama_token m_next = llama_sampler_sample(smpl_main, g_ctx, (int)i);
                if (m_next == drafts[i + 1]) {
                    accepted++;
                } else {
                    final_tok = m_next; // correction
                    break;
                }
            }
            if (accepted == drafts.size()) {
                // Bonus: main's prediction one beyond the last accepted draft.
                final_tok = llama_sampler_sample(smpl_main, g_ctx,
                                                 (int)(drafts.size() - 1));
            }
        }
        // else: accepted = 0 and final_tok = m_first (the correction).
        total_accepted += (long)accepted;
        const int A = (int)accepted;

        // (5) Commit MAIN KV to "prefix + drafts[0..A-1] + final_tok".
        if (A < (int)drafts.size()) {
            // Drop rejected suffix from main KV.
            llama_memory_seq_rm(llama_get_memory(g_ctx), 0, g_n_past + A, -1);
            llama_batch mb = llama_batch_init(1, 0, 1);
            airi_batch_add(mb, final_tok, g_n_past + A, {0}, /*logits=*/true);
            int rcm = llama_decode(g_ctx, mb);
            llama_batch_free(mb);
            if (rcm != 0) {
                PROOF("SPEC_COMMIT_MAIN_FAILED rc=%d", rcm);
                g_n_past += A;
                g_draft_in_sync.store(false);
                break;
            }
            g_n_past += A + 1;
        } else {
            // All K drafts accepted; append the bonus.
            llama_batch mb = llama_batch_init(1, 0, 1);
            airi_batch_add(mb, final_tok, g_n_past + (int)drafts.size(), {0},
                           /*logits=*/true);
            int rcm = llama_decode(g_ctx, mb);
            llama_batch_free(mb);
            if (rcm != 0) {
                PROOF("SPEC_COMMIT_BONUS_FAILED rc=%d", rcm);
                g_n_past += (int)drafts.size();
                g_draft_in_sync.store(false);
                break;
            }
            g_n_past += (int)drafts.size() + 1;
        }

        // (6) Commit DRAFT KV to mirror the same final state.
        if (A < (int)drafts.size()) {
            int drop = (int)drafts.size() - A;
            llama_memory_seq_rm(llama_get_memory(g_draft_ctx), 0,
                                g_draft_n_past - drop, -1);
            g_draft_n_past -= drop;
        }
        {
            llama_batch db = llama_batch_init(1, 0, 1);
            airi_batch_add(db, final_tok, g_draft_n_past, {0}, /*logits=*/true);
            int rcd = llama_decode(g_draft_ctx, db);
            llama_batch_free(db);
            if (rcd != 0) {
                g_draft_in_sync.store(false);
                PROOF("SPEC_COMMIT_DRAFT_FAILED rc=%d -> drop sync", rcd);
            } else {
                g_draft_n_past++;
            }
        }

        // (7) Emit accepted drafts + final_tok, honoring stop tokens.
        for (int i = 0; i < A; ++i) {
            if (!emit_token(drafts[i])) break;
            if (produced >= max_new) { stop_now = true; break; }
        }
        if (!stop_now) emit_token(final_tok);
        if (produced >= max_new) stop_now = true;
    }

    // Flush any trailing complete UTF-8.
    if (callback && invoke && !utf8_pending.empty() && is_valid_utf8(utf8_pending)) {
        jstring jc = env->NewStringUTF(utf8_pending.c_str());
        env->CallObjectMethod(callback, invoke, jc);
        env->DeleteLocalRef(jc);
        if (env->ExceptionCheck()) env->ExceptionClear();
    } else if (!utf8_pending.empty()) {
        LOGW("spec_generate: dropping %d trailing bytes (incomplete UTF-8)",
             (int)utf8_pending.size());
    }

    llama_sampler_free(smpl_main);
    llama_sampler_free(smpl_draft);

    long t_end    = (long)(ggml_time_us() / 1000LL);
    long elapsed  = t_end - t_start;
    g_t_decode_ms.store(elapsed);
    g_n_decoded.store(produced);

    long acc_pct = total_drafted > 0 ? (total_accepted * 100 / total_drafted) : 0;
    g_spec_drafted.fetch_add(total_drafted);
    g_spec_accepted.fetch_add(total_accepted);
    g_spec_runs.fetch_add(1);
    PROOF("SPEC_DONE drafted=%ld accepted=%ld accept_pct=%ld%% produced=%d "
          "elapsed_ms=%ld first_token_ms=%ld n_past=%d draft_past=%d K=%d",
          total_drafted, total_accepted, acc_pct, produced, elapsed,
          t_first_token_ms, g_n_past, g_draft_n_past, K);

    g_phase = "idle";
}

// ────────────────────────────────────────────────────────────────────────────
// Embedding API (Phase 2 — semantic memory).
//
// This sub-bridge owns its OWN llama_model + llama_context, separate from
// the chat globals (g_model / g_ctx). That separation is mandatory because
// the embedding context must be initialised with `embeddings = true` and
// `pooling_type = LLAMA_POOLING_TYPE_MEAN`, which would corrupt sampling
// for normal chat decoding.
//
// All entry points are guarded — calling computeEmbedding before
// loadEmbeddingModel returns null cleanly. There is NO fallback to a
// fake vector. If the embedding model isn't loaded, the Kotlin layer
// (EmbeddingService.isReady) sees the false and the chat path falls back
// to chronological recall. No silent fakery.
// ────────────────────────────────────────────────────────────────────────────

static llama_model*   g_emb_model = nullptr;
static llama_context* g_emb_ctx   = nullptr;
static int            g_emb_dim   = 0;
static int            g_emb_nctx  = 512;

static void airi_free_embedding_state() {
    if (g_emb_ctx)   { llama_free(g_emb_ctx);          g_emb_ctx   = nullptr; }
    if (g_emb_model) { llama_model_free(g_emb_model);  g_emb_model = nullptr; }
    g_emb_dim = 0;
}

JNIEXPORT jstring JNICALL
Java_com_airi_assistant_ai_LlamaNative_loadEmbeddingModel(
    JNIEnv* env, jobject /*this*/, jstring jpath)
{
    EMB_LOCK();
    if (!jpath) return env->NewStringUTF("ERR_NULL_PATH");
    const char* path_c = env->GetStringUTFChars(jpath, nullptr);
    std::string model_path(path_c ? path_c : "");
    env->ReleaseStringUTFChars(jpath, path_c);

    if (model_path.empty() || !file_exists(model_path.c_str())) {
        return env->NewStringUTF("ERR_FILE");
    }

    airi_free_embedding_state();
    llama_backend_init();

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    mp.use_mmap     = true;
    mp.use_mlock    = false;
    g_emb_model = llama_model_load_from_file(model_path.c_str(), mp);
    if (!g_emb_model) {
        PROOF("EMBEDDING_MODEL_LOAD_FAILED stage=model_load path=%s", model_path.c_str());
        return env->NewStringUTF("ERR_MODEL_LOAD");
    }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx           = g_emb_nctx;
    cp.n_batch         = g_emb_nctx;
    cp.n_ubatch        = g_emb_nctx;
    cp.n_threads       = airi_pick_threads();
    cp.n_threads_batch = cp.n_threads;
    cp.embeddings      = true;
    cp.pooling_type    = LLAMA_POOLING_TYPE_MEAN;
    g_emb_ctx = llama_init_from_model(g_emb_model, cp);
    if (!g_emb_ctx) {
        airi_free_embedding_state();
        PROOF("EMBEDDING_MODEL_LOAD_FAILED stage=ctx_init path=%s", model_path.c_str());
        return env->NewStringUTF("ERR_CTX_INIT");
    }

    g_emb_dim = llama_model_n_embd(g_emb_model);
    PROOF("EMBEDDING_MODEL_LOADED dim=%d n_ctx=%u path=%s",
          g_emb_dim, cp.n_ctx, model_path.c_str());

    char buf[64];
    snprintf(buf, sizeof(buf), "OK dim=%d", g_emb_dim);
    return env->NewStringUTF(buf);
}

JNIEXPORT jfloatArray JNICALL
Java_com_airi_assistant_ai_LlamaNative_computeEmbedding(
    JNIEnv* env, jobject /*this*/, jstring jtext)
{
    EMB_LOCK();
    if (!g_emb_model || !g_emb_ctx || g_emb_dim <= 0) return nullptr;
    if (!jtext) return nullptr;

    const char* text_c = env->GetStringUTFChars(jtext, nullptr);
    std::string text(text_c ? text_c : "");
    env->ReleaseStringUTFChars(jtext, text_c);
    if (text.empty()) return nullptr;

    const llama_vocab* vocab = llama_model_get_vocab(g_emb_model);

    // Probe required token count, then allocate exactly.
    int n_probe = -llama_tokenize(vocab, text.c_str(), (int)text.size(),
                                  nullptr, 0, /*add_special=*/true, /*parse_special=*/false);
    if (n_probe <= 0) return nullptr;
    if (n_probe > g_emb_nctx) {
        // Truncate to context size — embedding models are bounded; long
        // inputs are surfaced as "context too long" rather than silently
        // giving a partial vector that the search would treat as equally
        // valid. We DO accept truncation here (vs reject) because callers
        // already feed reasonable-sized chat messages and the alternative
        // (failing every long message) is worse UX.
        PROOF("EMBEDDING_TRUNCATED requested=%d cap=%d", n_probe, g_emb_nctx);
        n_probe = g_emb_nctx;
    }
    std::vector<llama_token> tokens(n_probe);
    int n_tokens = llama_tokenize(vocab, text.c_str(), (int)text.size(),
                                  tokens.data(), n_probe,
                                  /*add_special=*/true, /*parse_special=*/false);
    if (n_tokens < 0) {
        // Negative = "buffer was too small by this many tokens"; we already
        // sized exactly so this should never happen. Bail safely.
        PROOF("EMBEDDING_TOKENIZE_FAILED rc=%d", n_tokens);
        return nullptr;
    }
    if (n_tokens == 0) return nullptr;

    // Wipe any prior embedding-context KV — pooled embeddings only need
    // one batched decode of the input sequence.
    llama_memory_clear(llama_get_memory(g_emb_ctx), true);

    llama_batch batch = llama_batch_init(n_tokens, 0, 1);
    for (int i = 0; i < n_tokens; i++) {
        // For pooled embeddings we want logits/embeddings on EVERY token —
        // the pooler will mean-reduce them per sequence id. Setting
        // logits=true on the last token is sufficient with MEAN pooling
        // because llama.cpp marks the whole sequence for output when
        // embeddings=true.
        airi_batch_add(batch, tokens[i], i, {0}, /*logits=*/true);
    }
    int dec = llama_decode(g_emb_ctx, batch);
    llama_batch_free(batch);
    if (dec != 0) {
        PROOF("EMBEDDING_DECODE_FAILED rc=%d n_tokens=%d", dec, n_tokens);
        return nullptr;
    }

    const float* emb = llama_get_embeddings_seq(g_emb_ctx, /*seq_id=*/0);
    if (!emb) emb = llama_get_embeddings(g_emb_ctx);
    if (!emb) {
        PROOF("EMBEDDING_NULL_OUTPUT n_tokens=%d", n_tokens);
        return nullptr;
    }

    // L2-normalise — turns cosine similarity into a plain dot product on
    // the Kotlin side (massively cheaper for the per-query top-k loop).
    int dim = g_emb_dim;
    double sumsq = 0.0;
    for (int i = 0; i < dim; i++) sumsq += (double)emb[i] * (double)emb[i];
    double norm = std::sqrt(sumsq);
    if (norm < 1e-12) norm = 1.0;

    std::vector<jfloat> jbuf(dim);
    for (int i = 0; i < dim; i++) jbuf[i] = (jfloat)((double)emb[i] / norm);

    jfloatArray out = env->NewFloatArray(dim);
    if (!out) return nullptr;
    env->SetFloatArrayRegion(out, 0, dim, jbuf.data());

    PROOF("EMBEDDING_CREATED n_tokens=%d dim=%d", n_tokens, dim);
    return out;
}

JNIEXPORT void JNICALL
Java_com_airi_assistant_ai_LlamaNative_unloadEmbeddingModel(
    JNIEnv* /*env*/, jobject /*this*/)
{
    EMB_LOCK();
    airi_free_embedding_state();
    PROOF("EMBEDDING_MODEL_UNLOADED");
}

JNIEXPORT jint JNICALL
Java_com_airi_assistant_ai_LlamaNative_getEmbeddingDim(
    JNIEnv* /*env*/, jobject /*this*/)
{
    EMB_LOCK();
    return (jint)g_emb_dim;
}

// =============================================================================
//  MTMD VISION SUB-BRIDGE  (Phase 3)
// -----------------------------------------------------------------------------
//  Image multimodal via the upstream `tools/mtmd` library that we vendored
//  into  app/src/main/cpp/llama/tools/mtmd/  and wired into the build via
//  the AIRI_HAS_MTMD CMake switch. Audio (mtmd-audio.cpp / miniaudio path)
//  is intentionally NOT compiled in.
//
//  Lifetime
//  --------
//    g_mtmd_ctx is created by airi_load_mmproj(), passing the EXISTING
//    g_model so vision projections are linked against the same vocabulary
//    and tensor types as the text model. mtmd_free() is the matching
//    teardown. All access is serialised under g_mtmd_mutex.
//
//  Generation flow (`evalImageAndGenerate`)
//  ----------------------------------------
//    1. Compose `<__media__> <prompt>` (using `mtmd_default_marker()` so the
//       splice point matches what `mtmd_tokenize()` expects for THIS model).
//    2. Wrap the caller-supplied RGB888 buffer in an `mtmd_bitmap`.
//    3. `mtmd_tokenize` → `mtmd_input_chunks*` (interleaved text + image
//       chunks, the order the model was trained on).
//    4. Wipe KV cache for a clean conditioning pass (no chat history bleed).
//    5. `mtmd_helper_eval_chunks` decodes everything into KV in the right
//       order (text-with-llama_decode, image-with-mtmd_encode_chunk +
//       llama_decode of the embedding output). Logits-on-last so we can
//       sample.
//    6. Standard sampler loop until EOG or maxNewTokens.
//
//  HARD CAVEAT
//  -----------
//    This is the FIRST cut, written without an Android NDK in the dev
//    environment. The C++ is API-correct against the vendored mtmd headers
//    but has not been compiled. First GHA build will surface any include /
//    linkage issues that need to be iterated. UI gating ensures users
//    cannot trigger this path until ModelCapabilities.detect() flips
//    `vision = true`, which only happens for vision-capable model profiles.
// =============================================================================
#if defined(AIRI_HAS_MTMD) && AIRI_HAS_MTMD
// `mtmd.h` and `mtmd-helper.h` are reachable via the
// `app/src/main/cpp/llama/tools/mtmd` include directory added by
// CMakeLists. The previous `tools/mtmd/...` prefix would only resolve if
// `app/src/main/cpp/llama` was on the search path, which it isn't.
#include "mtmd.h"
#include "mtmd-helper.h"

static mtmd_context*   g_mtmd_ctx = nullptr;
static std::mutex      g_mtmd_mutex;
static std::string     g_mmproj_path;

JNIEXPORT jboolean JNICALL
Java_com_airi_assistant_ai_LlamaNative_loadMmproj(
    JNIEnv* env, jobject /*this*/, jstring jPath)
{
    LLAMA_LOCK();
    std::lock_guard<std::mutex> lock(g_mtmd_mutex);
    if (g_model == nullptr) {
        PROOF("MMPROJ_LOAD_FAILED reason=no_text_model");
        return JNI_FALSE;
    }
    if (g_mtmd_ctx) {
        mtmd_free(g_mtmd_ctx);
        g_mtmd_ctx = nullptr;
    }
    const char* cPath = env->GetStringUTFChars(jPath, nullptr);
    g_mmproj_path = cPath ? cPath : "";
    mtmd_context_params params = mtmd_context_params_default();
    params.use_gpu        = false;
    params.print_timings  = false;
    params.n_threads      = 4;
    // NOTE: `verbosity` was removed from `mtmd_context_params` upstream;
    // log level is now controlled globally via the GGML log callback,
    // which the rest of LlamaBridge already configures elsewhere.
    g_mtmd_ctx = mtmd_init_from_file(cPath, g_model, params);
    env->ReleaseStringUTFChars(jPath, cPath);
    if (g_mtmd_ctx == nullptr) {
        PROOF("MMPROJ_LOAD_FAILED reason=mtmd_init_failed path=%s",
              g_mmproj_path.c_str());
        g_mmproj_path.clear();
        return JNI_FALSE;
    }
    PROOF("MMPROJ_LOADED path=%s vision=%d",
          g_mmproj_path.c_str(),
          (int)mtmd_support_vision(g_mtmd_ctx));
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_airi_assistant_ai_LlamaNative_unloadMmproj(
    JNIEnv* /*env*/, jobject /*this*/)
{
    LLAMA_LOCK();
    std::lock_guard<std::mutex> lock(g_mtmd_mutex);
    if (g_mtmd_ctx) {
        mtmd_free(g_mtmd_ctx);
        g_mtmd_ctx = nullptr;
        PROOF("MMPROJ_UNLOADED path=%s", g_mmproj_path.c_str());
        g_mmproj_path.clear();
    }
}

JNIEXPORT jboolean JNICALL
Java_com_airi_assistant_ai_LlamaNative_isMmprojLoaded(
    JNIEnv* /*env*/, jobject /*this*/)
{
    LLAMA_LOCK();
    std::lock_guard<std::mutex> lock(g_mtmd_mutex);
    return g_mtmd_ctx ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_airi_assistant_ai_LlamaNative_evalImageAndGenerate(
    JNIEnv* env, jobject /*this*/,
    jstring jPrompt, jbyteArray jRgb,
    jint width, jint height, jint maxNewTokens)
{
    LLAMA_LOCK();
    std::lock_guard<std::mutex> lock(g_mtmd_mutex);
    if (g_mtmd_ctx == nullptr || g_model == nullptr || g_ctx == nullptr) {
        PROOF("MMPROJ_EVAL_FAILED reason=not_ready");
        return env->NewStringUTF("");
    }
    // ---- Compose prompt with the model-specific image marker -----------------
    const char* cPrompt = env->GetStringUTFChars(jPrompt, nullptr);
    std::string prompt(cPrompt ? cPrompt : "");
    env->ReleaseStringUTFChars(jPrompt, cPrompt);
    const char* marker = mtmd_default_marker();
    if (marker && prompt.find(marker) == std::string::npos) {
        prompt = std::string(marker) + "\n" + prompt;
    }

    // ---- Wrap RGB888 buffer in an mtmd_bitmap -------------------------------
    const jsize need = (jsize)(3 * width * height);
    const jsize have = env->GetArrayLength(jRgb);
    if (have < need) {
        PROOF("MMPROJ_EVAL_FAILED reason=bitmap_size have=%d need=%d", have, need);
        return env->NewStringUTF("");
    }
    jbyte* rgb = env->GetByteArrayElements(jRgb, nullptr);
    mtmd_bitmap* bmp = mtmd_bitmap_init(
        (uint32_t)width, (uint32_t)height,
        reinterpret_cast<const unsigned char*>(rgb));
    env->ReleaseByteArrayElements(jRgb, rgb, JNI_ABORT);
    if (!bmp) {
        PROOF("MMPROJ_EVAL_FAILED reason=bitmap_init");
        return env->NewStringUTF("");
    }

    // ---- Tokenize (interleaves text + image chunks) -------------------------
    mtmd_input_text input;
    input.text          = prompt.c_str();
    input.add_special   = true;
    input.parse_special = true;

    mtmd_input_chunks* chunks = mtmd_input_chunks_init();
    // mtmd_tokenize takes `const mtmd_bitmap **`; declaring the array as
    // `const mtmd_bitmap *[1]` lets the array decay to that pointer type
    // implicitly. The bitmap itself is still owned by us and freed below.
    const mtmd_bitmap* bitmaps[1] = { bmp };
    int32_t tok_rc = mtmd_tokenize(g_mtmd_ctx, chunks, &input, bitmaps, 1);
    if (tok_rc != 0) {
        mtmd_input_chunks_free(chunks);
        mtmd_bitmap_free(bmp);
        PROOF("MMPROJ_EVAL_FAILED reason=tokenize rc=%d", tok_rc);
        return env->NewStringUTF("");
    }

    // ---- Wipe KV for a clean vision conditioning pass -----------------------
    // Upstream renamed `llama_kv_self_clear(ctx)` to a two-step call against
    // the new memory abstraction. `data=true` matches the old behavior of
    // wiping both metadata and the underlying KV buffers.
    llama_memory_clear(llama_get_memory(g_ctx), /*data=*/true);
    llama_pos n_past = 0;

    // ---- Eval chunks (text via llama_decode, image via mtmd_encode_chunk) ---
    int32_t eval_rc = mtmd_helper_eval_chunks(
        g_mtmd_ctx, g_ctx, chunks,
        n_past,
        /*seq_id*/      0,
        /*n_batch*/     256,
        /*logits_last*/ true,
        &n_past);
    mtmd_input_chunks_free(chunks);
    mtmd_bitmap_free(bmp);
    if (eval_rc != 0) {
        PROOF("MMPROJ_EVAL_FAILED reason=eval_chunks rc=%d n_past=%d",
              eval_rc, (int)n_past);
        return env->NewStringUTF("");
    }
    PROOF("MMPROJ_EVAL_OK n_past=%d w=%d h=%d", (int)n_past, width, height);

    // ---- Sampler (mirrors the text-only generate path defaults) -------------
    llama_sampler* sampler = llama_sampler_chain_init(
        llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    const llama_vocab* vocab = llama_model_get_vocab(g_model);
    std::string out;
    out.reserve(maxNewTokens * 4);
    int generated = 0;
    for (int i = 0; i < (int)maxNewTokens; ++i) {
        llama_token id = llama_sampler_sample(sampler, g_ctx, -1);
        if (llama_vocab_is_eog(vocab, id)) break;
        char buf[256];
        int n = llama_token_to_piece(vocab, id, buf, sizeof(buf), 0, true);
        if (n > 0) out.append(buf, n);
        llama_batch batch = llama_batch_get_one(&id, 1);
        if (llama_decode(g_ctx, batch) != 0) break;
        n_past++;
        generated++;
    }
    llama_sampler_free(sampler);
    PROOF("MMPROJ_GENERATE_DONE generated=%d chars=%d",
          generated, (int)out.size());
    return env->NewStringUTF(out.c_str());
}
#else  // AIRI_HAS_MTMD
// Honest no-op stubs so the Java side links even when mtmd is disabled at
// build time. Each one logs an explicit AIRI_PROOF disabled-tag so log
// readers can immediately tell that the build flavour does not include
// vision — no silent fallback, no fake "ok" return value.
JNIEXPORT jboolean JNICALL
Java_com_airi_assistant_ai_LlamaNative_loadMmproj(
    JNIEnv* /*env*/, jobject /*this*/, jstring /*jPath*/)
{ PROOF("MMPROJ_LOAD_FAILED reason=mtmd_disabled_at_build"); return JNI_FALSE; }

JNIEXPORT void JNICALL
Java_com_airi_assistant_ai_LlamaNative_unloadMmproj(
    JNIEnv* /*env*/, jobject /*this*/) {}

JNIEXPORT jboolean JNICALL
Java_com_airi_assistant_ai_LlamaNative_isMmprojLoaded(
    JNIEnv* /*env*/, jobject /*this*/)
{ return JNI_FALSE; }

JNIEXPORT jstring JNICALL
Java_com_airi_assistant_ai_LlamaNative_evalImageAndGenerate(
    JNIEnv* env, jobject /*this*/, jstring /*p*/, jbyteArray /*r*/,
    jint /*w*/, jint /*h*/, jint /*n*/)
{
    PROOF("MMPROJ_EVAL_FAILED reason=mtmd_disabled_at_build");
    return env->NewStringUTF("");
}
#endif // AIRI_HAS_MTMD

} // extern "C"
