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
#include <atomic>
#include <thread>
#include <functional>
#include <cstring>
#include <cstdio>
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

static bool is_valid_gguf(const char* path) {
    FILE* f = fopen(path, "rb");
    if (!f) return false;
    uint8_t magic[4] = {};
    size_t n = fread(magic, 1, 4, f);
    fclose(f);
    if (n < 4) return false;
    return magic[0] == 'G' && magic[1] == 'G' && magic[2] == 'U' && magic[3] == 'F';
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
    const int keep_tail      = std::max(64, (int)(n_ctx / 2));
    if (g_n_past - safe_keep_head <= keep_tail) {
        // Not enough trimmable space; only option is a hard reset.
        PROOF("KV_TRIM_FORCE_RESET n_past=%d n_ctx=%u safe_keep=%d keep_tail=%d",
              g_n_past, n_ctx, safe_keep_head, keep_tail);
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

    const llama_vocab* vocab = llama_model_get_vocab(g_model);
    const uint32_t     n_ctx = llama_n_ctx(g_ctx);

    // Add BOS only on the very first append of a session.
    const bool add_bos = (g_n_past == 0);

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

    // Slide the window if we're going to overflow.
    airi_kv_trim_if_needed(n_tokens + /*generation reserve*/ 64);

    // After a forced reset inside the trim helper, we may need to redo BOS.
    const bool add_bos_after_trim = (g_n_past == 0);
    if (add_bos_after_trim != add_bos) {
        // Re-tokenize once more to insert BOS that was previously skipped.
        int n_probe2 = -llama_tokenize(vocab, text.c_str(), (int)text.size(),
                                       nullptr, 0, add_bos_after_trim, true);
        if (n_probe2 < 0) n_probe2 = 0;
        cap = std::max(n_probe2 + 8, 32);
        tokens.assign(cap, 0);
        n_tokens = llama_tokenize(vocab, text.c_str(), (int)text.size(),
                                  tokens.data(), cap, add_bos_after_trim, true);
        if (n_tokens < 0) throw std::runtime_error("TOKENIZE_FAILED_AFTER_TRIM");
        tokens.resize(n_tokens);
    }

    if ((uint32_t)(g_n_past + n_tokens) > n_ctx) {
        // Still doesn't fit → fatal.
        PROOF("APPEND_OVERFLOW n_past=%d n_new=%d n_ctx=%u",
              g_n_past, n_tokens, n_ctx);
        throw std::runtime_error("KV_OVERFLOW: turn does not fit even after trim");
    }

    // Allocate batch large enough for this turn.
    int n_batch_alloc = std::max(n_tokens, 1);
    llama_batch batch = llama_batch_init(n_batch_alloc, 0, 1);

    airi_batch_clear(batch);
    for (int i = 0; i < n_tokens; i++) {
        const bool with_logits = last_token_logits && (i == n_tokens - 1);
        airi_batch_add(batch, tokens[i], g_n_past + i, {0}, with_logits);
    }

    long t0 = (long)(ggml_time_us() / 1000LL);
    PROOF("APPEND_DECODE n_new=%d n_past_before=%d n_ctx=%u logits=%d",
          n_tokens, g_n_past, n_ctx, last_token_logits ? 1 : 0);

    g_phase = "append_decode";
    int rc = llama_decode(g_ctx, batch);
    long t1 = (long)(ggml_time_us() / 1000LL);
    llama_batch_free(batch);

    if (rc != 0) {
        PROOF("APPEND_DECODE_FAILED rc=%d n_tokens=%d", rc, n_tokens);
        throw std::runtime_error("APPEND_DECODE_FAILED rc=" + std::to_string(rc));
    }

    // The user-turn append (logits=true) IS the prefill that determines
    // first-token latency. Record it separately from history-replay appends.
    if (last_token_logits) g_t_prefill_ms.store(t1 - t0);

    g_n_past += n_tokens;
    PROOF("APPEND_DECODE_OK n_new=%d n_past_after=%d elapsed_ms=%ld n_past=%d n_ctx=%u kv_used_pct=%d",
          n_tokens, g_n_past, (t1 - t0), g_n_past, n_ctx,
          n_ctx > 0 ? (int)((100L * g_n_past) / n_ctx) : 0);
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

    g_cancel.store(false);
    g_phase = "generate";

    const llama_vocab* vocab = llama_model_get_vocab(g_model);
    const uint32_t     n_ctx = llama_n_ctx(g_ctx);

    int max_new = std::min(max_new_request > 0 ? max_new_request : 256, 1024);

    // Make sure the headroom for `max_new` decode steps fits.
    airi_kv_trim_if_needed(max_new + 8);

    // After trim, KV may have been reset. If so, the caller's last-token
    // logits are gone and we cannot sample. Bail with a clear error.
    if (g_n_past == 0) {
        throw std::runtime_error("NO_LOGITS_AFTER_TRIM (session reset)");
    }

    PROOF("GEN_START n_past=%d n_ctx=%u max_new=%d kv_used_pct=%d",
          g_n_past, n_ctx, max_new,
          n_ctx > 0 ? (int)((100L * g_n_past) / n_ctx) : 0);

    // Reset per-generation timing counters.
    g_t_first_token_ms.store(0);
    g_t_decode_ms.store(0);
    g_n_decoded.store(0);

    llama_sampler* sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    llama_batch batch = llama_batch_init(1, 0, 1);

    std::string full_response;
    std::string utf8_pending;
    bool        first             = true;
    long        t_start           = (long)(ggml_time_us() / 1000LL);
    long        t_first_token_ms  = 0;
    int         token_count       = 0;

    for (int i = 0; i < max_new; i++) {
        if (g_cancel.load()) {
            PROOF("GEN_CANCELLED iter=%d emitted=%d", i, token_count);
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
            PROOF("DECODE_FAILED iter=%d rc=%d n_past=%d", i, dec, g_n_past);
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
    g_phase = "loadModel";
    const char* path = env->GetStringUTFChars(jModelPath, nullptr);
    std::string model_path(path);
    env->ReleaseStringUTFChars(jModelPath, path);

    LOGI("loadModel: path=%s", model_path.c_str());

    if (!file_exists(model_path.c_str())) {
        LOGE("loadModel: FILE_NOT_FOUND %s", model_path.c_str());
        return env->NewStringUTF("FILE_NOT_FOUND");
    }
    long sz = file_size(model_path.c_str());
    if (sz < 100 * 1024 * 1024L) {
        LOGE("loadModel: file too small (%ld bytes) — INVALID_GGUF", sz);
        return env->NewStringUTF("INVALID_GGUF");
    }
    if (!is_valid_gguf(model_path.c_str())) {
        LOGE("loadModel: bad GGUF magic — INVALID_GGUF");
        return env->NewStringUTF("INVALID_GGUF");
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
        return env->NewStringUTF("NATIVE_LOAD_FAILED:llama_model_load_from_file returned null");
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
        return env->NewStringUTF("NATIVE_LOAD_FAILED:llama_init_from_model returned null");
    }

    g_model_path = model_path;
    g_n_past = 0;
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
    g_phase = "loadModelWithProgress";
    const char* path = env->GetStringUTFChars(jModelPath, nullptr);
    std::string model_path(path);
    env->ReleaseStringUTFChars(jModelPath, path);

    LOGI("loadModelWithProgress: path=%s", model_path.c_str());

    jclass cbClass   = env->GetObjectClass(callback);
    jmethodID onProg = env->GetMethodID(cbClass, "onProgress", "(I)V");
    if (onProg) env->CallVoidMethod(callback, onProg, 5);

    if (!file_exists(model_path.c_str())) {
        if (onProg) env->CallVoidMethod(callback, onProg, -1);
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"), "FILE_NOT_FOUND");
        return;
    }
    long sz = file_size(model_path.c_str());
    if (sz < 100 * 1024 * 1024L || !is_valid_gguf(model_path.c_str())) {
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"), "INVALID_GGUF");
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
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"),
                      "NATIVE_LOAD_FAILED:llama_model_load_from_file returned null");
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
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"),
                      "NATIVE_LOAD_FAILED:llama_init_from_model returned null");
        return;
    }

    g_model_path = model_path;
    g_n_past = 0;
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
    PROOF("SESSION_BEGIN n_ctx=%u draft_loaded=%d",
          llama_n_ctx(g_ctx), g_draft_ctx ? 1 : 0);
    g_phase = "idle";
}

JNIEXPORT void JNICALL
Java_com_airi_assistant_ai_LlamaNative_resetSession(JNIEnv* /*env*/, jobject /*this*/) {
    if (!g_ctx) return;
    g_phase = "resetSession";
    llama_memory_clear(llama_get_memory(g_ctx), true);
    g_n_past = 0;
    g_cancel.store(false);
    airi_draft_clear_kv();
    PROOF("SESSION_RESET");
    g_phase = "idle";
}

JNIEXPORT void JNICALL
Java_com_airi_assistant_ai_LlamaNative_appendUserTurn(
    JNIEnv* env, jobject /*this*/, jstring jText)
{
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
    return (jint)g_n_past;
}

JNIEXPORT jint JNICALL
Java_com_airi_assistant_ai_LlamaNative_getNCtx(JNIEnv*, jobject) {
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
// setRuntimeMode(nCtx, nThreads): hot-swap context size / thread count without
// reloading model weights from disk. Wipes KV (caller must re-prime via
// beginSession + appendUserTurn).
// ----------------------------------------------------------------------------
JNIEXPORT void JNICALL
Java_com_airi_assistant_ai_LlamaNative_setRuntimeMode(
    JNIEnv* env, jobject /*this*/, jint nCtx, jint nThreads)
{
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

} // extern "C"
