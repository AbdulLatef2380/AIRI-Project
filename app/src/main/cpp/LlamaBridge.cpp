// =============================================================================
//  LlamaBridge.cpp — JNI bridge for AIRI on-device LLM
//
//  This file is a deliberate port of the proven streaming pattern used by
//  llama.cpp's official Android example:
//    examples/llama.android/llama/src/main/cpp/llama-android.cpp
//
//  Key correctness properties (each one fixes a real crash mode that previous
//  iterations of this bridge suffered from):
//
//   1. UTF-8 byte buffering before crossing JNI:
//      `env->NewStringUTF` requires VALID modified UTF-8. A multi-byte glyph
//      (Arabic, CJK, emoji) is routinely split across two tokens, so we MUST
//      accumulate raw bytes and only flush a callback once `is_valid_utf8` is
//      true. Otherwise the VM aborts with
//      "JNI ERROR (app bug): input is not valid Modified UTF-8".
//      This is the most common llama.cpp Android crash on Arabic models.
//
//   2. Explicit position-tracked batch (manual `batch_add`) instead of the
//      deprecated `llama_batch_get_one`. Mirrors the upstream reference; avoids
//      silent KV head-tracking surprises.
//
//   3. Pre-flight KV-slot check (`n_prompt + max_new > n_ctx`) — emits
//      `AIRI_PROOF KV_OVERFLOW` and bails BEFORE we touch the decoder. Previously
//      we'd `break` mid-loop after the model returned non-zero, never reaching
//      FIRST_TOKEN.
//
//   4. POSIX signal handler installed at `JNI_OnLoad` so a native segfault is
//      reported as `AIRI_PROOF SIGNAL_CAUGHT signal=N phase=<...>` instead of
//      a silent tombstone with no AIRI marker.
//
//   5. Dense `AIRI_PROOF` checkpoints — STREAM_START, TOKENIZE_OK,
//      KV_PRECHECK_OK, BEFORE_PROMPT_DECODE, PROMPT_DECODE_OK, BEFORE_SAMPLE_LOOP,
//      SAMPLE_OK_ITER0, FIRST_TOKEN_BYTES, FIRST_TOKEN, GENERATION_SUCCESS.
//      If any one is missing in logcat, the failing phase is unambiguous.
//
//   6. `try / catch` around the entire generation path — any C++ exception
//      thrown inside llama.cpp internals is converted to a Java RuntimeException
//      with a usable message instead of crashing the process.
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
static llama_model*   g_model   = nullptr;
static llama_context* g_ctx     = nullptr;
static std::atomic<bool> g_cancel{false};
static std::string    g_model_path;

// Phase marker so the signal handler can report WHERE we crashed.
static const char*    g_phase   = "idle";

// ─── signal handler ──────────────────────────────────────────────────────────
static void airi_signal_handler(int sig) {
    // async-signal-safe-ish: __android_log_print is not strictly safe but is
    // the standard practice on Android for last-gasp diagnostics, and we abort
    // immediately afterwards regardless.
    __android_log_print(ANDROID_LOG_ERROR, "AIRI_PROOF",
        "SIGNAL_CAUGHT signal=%d phase=%s", sig, g_phase);
    // Restore default handler and re-raise so we still get the tombstone.
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
        if      ((c & 0x80) == 0x00) need = 1;        // 0xxxxxxx
        else if ((c & 0xE0) == 0xC0) need = 2;        // 110xxxxx
        else if ((c & 0xF0) == 0xE0) need = 3;        // 1110xxxx
        else if ((c & 0xF8) == 0xF0) need = 4;        // 11110xxx
        else return false;                            // illegal lead byte
        if (i + need > n) return false;               // truncated
        for (size_t k = 1; k < need; k++) {
            if ((p[i + k] & 0xC0) != 0x80) return false;
        }
        i += need;
    }
    return true;
}

// Inline replacement for common_batch_clear / common_batch_add (we don't link
// llama.cpp's `common` static lib in this build — see CMakeLists.txt).
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

// ─── JNI ─────────────────────────────────────────────────────────────────────
extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* /*vm*/, void* /*reserved*/) {
    install_signal_handlers();
    LOGI("AIRI_NATIVE JNI_OnLoad: signal handlers installed");
    return JNI_VERSION_1_6;
}

// ----------------------------------------------------------------------------
// loadModel(modelPath: String): String
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

    llama_backend_init();

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;

    LOGI("loadModel: calling llama_model_load_from_file …");
    g_model = llama_model_load_from_file(model_path.c_str(), mparams);
    if (!g_model) {
        LOGE("loadModel: llama_model_load_from_file returned null");
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
        LOGE("loadModel: context creation failed");
        return env->NewStringUTF("NATIVE_LOAD_FAILED:llama_new_context_with_model returned null");
    }

    g_model_path = model_path;
    LOGI("AIRI_MODEL: LOAD SUCCESS path=%s size=%ldMB threads=%d n_ctx=%u",
         model_path.c_str(), sz / (1024 * 1024), cparams.n_threads, cparams.n_ctx);
    g_phase = "idle";
    return env->NewStringUTF("LOAD_SUCCESS");
}

// ----------------------------------------------------------------------------
// loadModelWithProgress(modelPath: String, callback: ProgressCallback)
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
        LOGE("loadModelWithProgress: FILE_NOT_FOUND");
        if (onProg) env->CallVoidMethod(callback, onProg, -1);
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"), "FILE_NOT_FOUND");
        return;
    }
    long sz = file_size(model_path.c_str());
    if (sz < 100 * 1024 * 1024L || !is_valid_gguf(model_path.c_str())) {
        LOGE("loadModelWithProgress: INVALID_GGUF (size=%ld)", sz);
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"), "INVALID_GGUF");
        return;
    }

    if (onProg) env->CallVoidMethod(callback, onProg, 10);

    if (g_ctx)   { llama_free(g_ctx);          g_ctx   = nullptr; }
    if (g_model) { llama_model_free(g_model);  g_model = nullptr; }

    llama_backend_init();
    if (onProg) env->CallVoidMethod(callback, onProg, 15);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;

    LOGI("loadModelWithProgress: loading model …");
    g_model = llama_model_load_from_file(model_path.c_str(), mparams);
    if (onProg) env->CallVoidMethod(callback, onProg, 90);

    if (!g_model) {
        LOGE("loadModelWithProgress: NATIVE_LOAD_FAILED");
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
        LOGE("loadModelWithProgress: context creation failed");
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"),
                      "NATIVE_LOAD_FAILED:llama_new_context_with_model returned null");
        return;
    }

    g_model_path = model_path;
    if (onProg) env->CallVoidMethod(callback, onProg, 100);
    LOGI("AIRI_MODEL: LOAD SUCCESS (with progress) path=%s size=%ldMB threads=%d n_ctx=%u",
         model_path.c_str(), sz / (1024 * 1024), cparams.n_threads, cparams.n_ctx);
    g_phase = "idle";
}

// ============================================================================
// Shared streaming engine — used by both generateResponse() and generateStream()
//
// Returns the full assembled response string. If `callback` and `invoke` are
// non-null, also pushes UTF-8-validated chunks to the Kotlin lambda.
// Throws std::runtime_error on fatal native errors so the caller can convert
// them into Java exceptions.
// ============================================================================
static std::string airi_run_inference(
    JNIEnv*       env,
    const std::string& prompt_str,
    int           max_new_request,    // user-requested ceiling (clamped below)
    jobject       callback,           // Kotlin Function1, or nullptr
    jmethodID     invoke              // Function1.invoke method id, or nullptr
) {
    if (!g_model || !g_ctx) {
        throw std::runtime_error("MODEL_NOT_LOADED");
    }

    g_cancel.store(false);
    g_phase = "tokenize";

    PROOF("STREAM_START prompt_len=%d", (int)prompt_str.size());

    const llama_vocab* vocab = llama_model_get_vocab(g_model);
    const uint32_t     n_ctx = llama_n_ctx(g_ctx);

    // 1. Tokenise (two-pass: probe length then allocate exact)
    int n_probe = -llama_tokenize(vocab, prompt_str.c_str(), (int)prompt_str.size(),
                                  nullptr, 0, /*add_special=*/true, /*parse_special=*/true);
    if (n_probe < 0) n_probe = 0;
    int cap = std::max(n_probe + 8, 32);
    std::vector<llama_token> tokens(cap);
    int n_prompt = llama_tokenize(vocab, prompt_str.c_str(), (int)prompt_str.size(),
                                  tokens.data(), cap,
                                  /*add_special=*/true, /*parse_special=*/true);
    if (n_prompt < 0) {
        PROOF("TOKENIZE_FAILED n_prompt=%d cap=%d", n_prompt, cap);
        throw std::runtime_error("TOKENIZE_FAILED");
    }
    tokens.resize(n_prompt);
    PROOF("TOKENIZE_OK n_prompt=%d n_ctx=%u", n_prompt, n_ctx);

    // 2. KV-slot pre-check.
    //    Reserve at least 8 tokens of headroom for the sampler.
    int max_new = std::min(max_new_request > 0 ? max_new_request : 256, 1024);
    if ((uint32_t)(n_prompt + max_new + 8) > n_ctx) {
        int allowed = (int)n_ctx - n_prompt - 8;
        if (allowed < 16) {
            PROOF("KV_OVERFLOW n_prompt=%d max_new=%d n_ctx=%u allowed=%d",
                  n_prompt, max_new, n_ctx, allowed);
            throw std::runtime_error("KV_OVERFLOW: prompt does not fit in context");
        }
        PROOF("KV_CLAMP max_new=%d -> %d (n_prompt=%d n_ctx=%u)",
              max_new, allowed, n_prompt, n_ctx);
        max_new = allowed;
    }
    PROOF("KV_PRECHECK_OK n_prompt=%d max_new=%d n_ctx=%u", n_prompt, max_new, n_ctx);

    // 3. Allocate batch (large enough for prompt OR a single token).
    int n_batch = std::max(n_prompt, 1);
    llama_batch batch = llama_batch_init(n_batch, 0, 1);

    // RAII-ish cleanup helper: `goto cleanup` jumps here on any error path.
    auto free_batch = [&]() { llama_batch_free(batch); };

    // 4. Prompt decode.
    g_phase = "prompt_decode";
    llama_memory_clear(llama_get_memory(g_ctx), true);

    airi_batch_clear(batch);
    for (int i = 0; i < n_prompt; i++) {
        // Only the LAST token needs logits computed (saves a lot of work).
        airi_batch_add(batch, tokens[i], i, {0}, /*logits=*/(i == n_prompt - 1));
    }

    long t_decode_start = (long)(ggml_time_us() / 1000LL);
    PROOF("BEFORE_PROMPT_DECODE n_tokens=%d", batch.n_tokens);
    int rc = llama_decode(g_ctx, batch);
    long t_decode_end = (long)(ggml_time_us() / 1000LL);
    if (rc != 0) {
        PROOF("PROMPT_DECODE_FAILED rc=%d n_tokens=%d", rc, batch.n_tokens);
        free_batch();
        throw std::runtime_error("PROMPT_DECODE_FAILED rc=" + std::to_string(rc));
    }
    PROOF("PROMPT_DECODE_OK n_tokens=%d elapsed_ms=%ld",
          batch.n_tokens, (t_decode_end - t_decode_start));

    // 5. Sampler chain.
    g_phase = "sampler_init";
    llama_sampler* sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    // 6. Sampling loop.
    PROOF("BEFORE_SAMPLE_LOOP max_new=%d", max_new);
    g_phase = "sample_loop";

    std::string  full_response;          // returned to caller
    std::string  utf8_pending;           // UTF-8 byte accumulator for JNI
    bool         first             = true;
    long         t_first           = 0;
    int          n_cur             = n_prompt;       // absolute KV position
    int          token_count       = 0;

    for (int i = 0; i < max_new; i++) {
        if (g_cancel.load()) {
            PROOF("SAMPLE_CANCELLED iter=%d emitted=%d", i, token_count);
            break;
        }

        llama_token tok = llama_sampler_sample(sampler, g_ctx, -1);

        if (i == 0) PROOF("SAMPLE_OK_ITER0 tok=%d", (int)tok);

        if (llama_vocab_is_eog(vocab, tok)) {
            PROOF("EOG iter=%d emitted=%d", i, token_count);
            break;
        }

        // Convert token id → bytes (these may be partial UTF-8).
        char piece[256] = {};
        int  n_piece = llama_token_to_piece(vocab, tok, piece, sizeof(piece), 0, true);
        if (n_piece > 0) {
            full_response.append(piece, n_piece);
            utf8_pending.append(piece, n_piece);

            if (first) {
                t_first = (long)(ggml_time_us() / 1000LL);
                PROOF("FIRST_TOKEN_BYTES n_bytes=%d tok=%d", n_piece, (int)tok);
            }

            // Only flush to JNI if we have a complete UTF-8 sequence —
            // otherwise NewStringUTF aborts the VM on partial Arabic / CJK.
            if (callback && invoke && is_valid_utf8(utf8_pending)) {
                jstring jChunk = env->NewStringUTF(utf8_pending.c_str());
                env->CallObjectMethod(callback, invoke, jChunk);
                env->DeleteLocalRef(jChunk);
                if (env->ExceptionCheck()) {
                    env->ExceptionDescribe();
                    env->ExceptionClear();
                    LOGW("airi_run_inference: callback raised — stopping");
                    break;
                }
                if (first) {
                    PROOF("FIRST_TOKEN latency_ms=%ld bytes='%s'",
                          (long)0,
                          utf8_pending.size() < 32 ? utf8_pending.c_str() : "<long>");
                    first = false;
                }
                utf8_pending.clear();
            }
            token_count++;
        }

        // Decode the just-sampled token at the next absolute position.
        airi_batch_clear(batch);
        airi_batch_add(batch, tok, n_cur, {0}, /*logits=*/true);
        n_cur++;

        int dec = llama_decode(g_ctx, batch);
        if (dec != 0) {
            PROOF("DECODE_FAILED iter=%d rc=%d n_cur=%d", i, dec, n_cur);
            break;
        }
    }

    // Flush any trailing valid UTF-8 even if first-flush never happened.
    if (callback && invoke && !utf8_pending.empty() && is_valid_utf8(utf8_pending)) {
        jstring jChunk = env->NewStringUTF(utf8_pending.c_str());
        env->CallObjectMethod(callback, invoke, jChunk);
        env->DeleteLocalRef(jChunk);
        if (env->ExceptionCheck()) env->ExceptionClear();
        utf8_pending.clear();
    } else if (!utf8_pending.empty()) {
        LOGW("airi_run_inference: dropping %d trailing bytes (incomplete UTF-8)",
             (int)utf8_pending.size());
    }

    llama_sampler_free(sampler);
    free_batch();

    long t_end = (long)(ggml_time_us() / 1000LL);
    float elapsed_sec = first ? 0.0f : (t_end - t_first) / 1000.0f;
    float tps = (elapsed_sec > 0.0f && token_count > 0) ? (token_count / elapsed_sec) : 0.0f;
    PROOF("TOKENS_PER_SEC value=%.2f total_tokens=%d elapsed=%.2fs", tps, token_count, elapsed_sec);
    if (token_count > 0) {
        PROOF("GENERATION_SUCCESS tokens=%d", token_count);
    } else {
        PROOF("GENERATION_EMPTY (no tokens emitted)");
    }
    g_phase = "idle";
    return full_response;
}

// ----------------------------------------------------------------------------
// generateResponse(prompt: String): String   — blocking, no streaming
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
        std::string out = airi_run_inference(env, prompt_str, /*max_new=*/256,
                                             nullptr, nullptr);
        return env->NewStringUTF(out.c_str());
    } catch (const std::exception& e) {
        LOGE("generateResponse: native exception: %s", e.what());
        return env->NewStringUTF("");
    } catch (...) {
        LOGE("generateResponse: unknown native exception");
        return env->NewStringUTF("");
    }
}

// ----------------------------------------------------------------------------
// generateStream(prompt: String, onToken: (String) -> Unit)   — streaming
// ----------------------------------------------------------------------------
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

    // Resolve the Kotlin Function1.invoke(Object): Object once.
    jclass    fnClass = env->GetObjectClass(callback);
    jmethodID invoke  = env->GetMethodID(fnClass, "invoke",
                                         "(Ljava/lang/Object;)Ljava/lang/Object;");
    if (!invoke) {
        LOGE("generateStream: cannot find invoke method on callback");
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"), "NO_CALLBACK");
        return;
    }

    try {
        airi_run_inference(env, prompt_str, /*max_new=*/512, callback, invoke);
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
Java_com_airi_assistant_ai_LlamaNative_cancel(
    JNIEnv* /*env*/, jobject /*this*/)
{
    g_cancel.store(true);
    LOGD("generateStream: cancel requested");
}

} // extern "C"
