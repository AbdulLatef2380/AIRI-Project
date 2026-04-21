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
    int n_probe = -llama_tokenize(vocab, text.c_str(), (int)text.size(),
                                  nullptr, 0, add_bos, /*parse_special=*/true);
    if (n_probe < 0) n_probe = 0;
    int cap = std::max(n_probe + 8, 32);
    std::vector<llama_token> tokens(cap);
    int n_tokens = llama_tokenize(vocab, text.c_str(), (int)text.size(),
                                  tokens.data(), cap, add_bos, /*parse_special=*/true);
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

    g_n_past += n_tokens;
    PROOF("APPEND_DECODE_OK n_new=%d n_past_after=%d elapsed_ms=%ld",
          n_tokens, g_n_past, (t1 - t0));
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

    PROOF("GEN_START n_past=%d n_ctx=%u max_new=%d", g_n_past, n_ctx, max_new);

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

        if (llama_vocab_is_eog(vocab, tok)) {
            PROOF("EOG iter=%d emitted=%d", i, token_count);
            break;
        }

        char piece[256] = {};
        int  n_piece = llama_token_to_piece(vocab, tok, piece, sizeof(piece), 0, true);
        if (n_piece > 0) {
            full_response.append(piece, n_piece);
            utf8_pending.append(piece, n_piece);

            if (first) {
                t_first_token_ms = (long)(ggml_time_us() / 1000LL) - t_start;
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

    PROOF("GEN_DONE tokens=%d elapsed_ms=%ld tps=%.2f first_token_ms=%ld n_past=%d n_ctx=%u",
          token_count, elapsed_ms, tps, t_first_token_ms, g_n_past, n_ctx);

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

    g_model = llama_model_load_from_file(model_path.c_str(), mparams);
    if (!g_model) {
        return env->NewStringUTF("NATIVE_LOAD_FAILED:llama_model_load_from_file returned null");
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = 2048;
    cparams.n_threads       = (int)std::thread::hardware_concurrency();
    cparams.n_threads_batch = cparams.n_threads;

    g_ctx = llama_new_context_with_model(g_model, cparams);
    if (!g_ctx) {
        llama_model_free(g_model);
        g_model = nullptr;
        return env->NewStringUTF("NATIVE_LOAD_FAILED:llama_new_context_with_model returned null");
    }

    g_model_path = model_path;
    g_n_past = 0;
    LOGI("AIRI_MODEL: LOAD SUCCESS path=%s size=%ldMB threads=%d n_ctx=%u",
         model_path.c_str(), sz / (1024 * 1024), cparams.n_threads, cparams.n_ctx);
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
    g_model = llama_model_load_from_file(model_path.c_str(), mparams);
    if (onProg) env->CallVoidMethod(callback, onProg, 90);

    if (!g_model) {
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"),
                      "NATIVE_LOAD_FAILED:llama_model_load_from_file returned null");
        return;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = 2048;
    cparams.n_threads       = (int)std::thread::hardware_concurrency();
    cparams.n_threads_batch = cparams.n_threads;

    g_ctx = llama_new_context_with_model(g_model, cparams);
    if (!g_ctx) {
        llama_model_free(g_model);
        g_model = nullptr;
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"),
                      "NATIVE_LOAD_FAILED:llama_new_context_with_model returned null");
        return;
    }

    g_model_path = model_path;
    g_n_past = 0;
    if (onProg) env->CallVoidMethod(callback, onProg, 100);
    LOGI("AIRI_MODEL: LOAD SUCCESS (with progress) path=%s size=%ldMB threads=%d n_ctx=%u",
         model_path.c_str(), sz / (1024 * 1024), cparams.n_threads, cparams.n_ctx);
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
    PROOF("SESSION_BEGIN n_ctx=%u", llama_n_ctx(g_ctx));
    g_phase = "idle";
}

JNIEXPORT void JNICALL
Java_com_airi_assistant_ai_LlamaNative_resetSession(JNIEnv* /*env*/, jobject /*this*/) {
    if (!g_ctx) return;
    g_phase = "resetSession";
    llama_memory_clear(llama_get_memory(g_ctx), true);
    g_n_past = 0;
    g_cancel.store(false);
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

} // extern "C"
