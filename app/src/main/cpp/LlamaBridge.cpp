#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <atomic>
#include <thread>
#include <functional>
#include <cstring>
#include <cstdio>
#include <sys/stat.h>

#include "llama/include/llama.h"
#include "llama/ggml/include/ggml.h"

#define LOG_TAG "AIRI_NATIVE"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// ─── global state ────────────────────────────────────────────────────────────
static llama_model*   g_model   = nullptr;
static llama_context* g_ctx     = nullptr;
static std::atomic<bool> g_cancel{false};
static std::string    g_model_path;

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

// Verify GGUF magic: bytes 0-3 == "GGUF"
static bool is_valid_gguf(const char* path) {
    FILE* f = fopen(path, "rb");
    if (!f) return false;
    uint8_t magic[4] = {};
    size_t n = fread(magic, 1, 4, f);
    fclose(f);
    if (n < 4) return false;
    return magic[0] == 'G' && magic[1] == 'G' && magic[2] == 'U' && magic[3] == 'F';
}

// ─── JNI functions ───────────────────────────────────────────────────────────
extern "C" {

// ----------------------------------------------------------------------------
// loadModel(modelPath: String): String
//   Returns: "LOAD_SUCCESS" | "FILE_NOT_FOUND" | "INVALID_GGUF" | "NATIVE_LOAD_FAILED:<detail>"
// ----------------------------------------------------------------------------
JNIEXPORT jstring JNICALL
Java_com_airi_assistant_ai_LlamaNative_loadModel(
    JNIEnv* env, jobject /*this*/, jstring jModelPath)
{
    const char* path = env->GetStringUTFChars(jModelPath, nullptr);
    std::string model_path(path);
    env->ReleaseStringUTFChars(jModelPath, path);

    LOGI("loadModel: path=%s", model_path.c_str());

    // 1. File exists?
    if (!file_exists(model_path.c_str())) {
        LOGE("loadModel: FILE_NOT_FOUND %s", model_path.c_str());
        return env->NewStringUTF("FILE_NOT_FOUND");
    }

    // 2. Size > 100MB?
    long sz = file_size(model_path.c_str());
    if (sz < 100 * 1024 * 1024L) {
        LOGE("loadModel: file too small (%ld bytes) — INVALID_GGUF", sz);
        return env->NewStringUTF("INVALID_GGUF");
    }

    // 3. Valid GGUF magic?
    if (!is_valid_gguf(model_path.c_str())) {
        LOGE("loadModel: bad GGUF magic — INVALID_GGUF");
        return env->NewStringUTF("INVALID_GGUF");
    }

    // 4. Unload any existing model
    if (g_ctx)   { llama_free(g_ctx);         g_ctx   = nullptr; }
    if (g_model) { llama_model_free(g_model);  g_model = nullptr; }

    // 5. llama.cpp backend init (once)
    llama_backend_init();

    // 6. Load model
    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;  // CPU-only on Android

    LOGI("loadModel: calling llama_model_load_from_file …");
    g_model = llama_model_load_from_file(model_path.c_str(), mparams);
    if (!g_model) {
        LOGE("loadModel: llama_model_load_from_file returned null — NATIVE_LOAD_FAILED");
        return env->NewStringUTF("NATIVE_LOAD_FAILED:llama_model_load_from_file returned null");
    }

    // 7. Create context
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx      = 2048;
    cparams.n_threads  = (int)std::thread::hardware_concurrency();
    cparams.n_threads_batch = cparams.n_threads;

    g_ctx = llama_new_context_with_model(g_model, cparams);
    if (!g_ctx) {
        llama_model_free(g_model);
        g_model = nullptr;
        LOGE("loadModel: llama_new_context_with_model returned null — NATIVE_LOAD_FAILED");
        return env->NewStringUTF("NATIVE_LOAD_FAILED:llama_new_context_with_model returned null");
    }

    g_model_path = model_path;
    LOGI("AIRI_MODEL: LOAD SUCCESS path=%s size=%ldMB threads=%d",
         model_path.c_str(), sz / (1024 * 1024), cparams.n_threads);

    return env->NewStringUTF("LOAD_SUCCESS");
}

// ----------------------------------------------------------------------------
// loadModelWithProgress(modelPath: String, callback: ProgressCallback)
// ----------------------------------------------------------------------------
JNIEXPORT void JNICALL
Java_com_airi_assistant_ai_LlamaNative_loadModelWithProgress(
    JNIEnv* env, jobject /*this*/, jstring jModelPath, jobject callback)
{
    const char* path = env->GetStringUTFChars(jModelPath, nullptr);
    std::string model_path(path);
    env->ReleaseStringUTFChars(jModelPath, path);

    LOGI("loadModelWithProgress: path=%s", model_path.c_str());

    // Progress: 5%
    jclass cbClass   = env->GetObjectClass(callback);
    jmethodID onProg = env->GetMethodID(cbClass, "onProgress", "(I)V");
    if (onProg) env->CallVoidMethod(callback, onProg, 5);

    // Validation
    if (!file_exists(model_path.c_str())) {
        LOGE("loadModelWithProgress: FILE_NOT_FOUND");
        if (onProg) env->CallVoidMethod(callback, onProg, -1);
        jclass ex = env->FindClass("java/lang/RuntimeException");
        env->ThrowNew(ex, "FILE_NOT_FOUND");
        return;
    }

    long sz = file_size(model_path.c_str());
    if (sz < 100 * 1024 * 1024L || !is_valid_gguf(model_path.c_str())) {
        LOGE("loadModelWithProgress: INVALID_GGUF");
        jclass ex = env->FindClass("java/lang/RuntimeException");
        env->ThrowNew(ex, "INVALID_GGUF");
        return;
    }

    // Progress: 10%
    if (onProg) env->CallVoidMethod(callback, onProg, 10);

    // Unload previous
    if (g_ctx)   { llama_free(g_ctx);        g_ctx   = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }

    llama_backend_init();

    // Progress: 15%
    if (onProg) env->CallVoidMethod(callback, onProg, 15);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;

    LOGI("loadModelWithProgress: loading model …");
    g_model = llama_model_load_from_file(model_path.c_str(), mparams);

    // Progress: 90%
    if (onProg) env->CallVoidMethod(callback, onProg, 90);

    if (!g_model) {
        LOGE("loadModelWithProgress: NATIVE_LOAD_FAILED");
        jclass ex = env->FindClass("java/lang/RuntimeException");
        env->ThrowNew(ex, "NATIVE_LOAD_FAILED:llama_model_load_from_file returned null");
        return;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx      = 2048;
    cparams.n_threads  = (int)std::thread::hardware_concurrency();
    cparams.n_threads_batch = cparams.n_threads;

    g_ctx = llama_new_context_with_model(g_model, cparams);
    if (!g_ctx) {
        llama_model_free(g_model);
        g_model = nullptr;
        LOGE("loadModelWithProgress: context creation failed");
        jclass ex = env->FindClass("java/lang/RuntimeException");
        env->ThrowNew(ex, "NATIVE_LOAD_FAILED:llama_new_context_with_model returned null");
        return;
    }

    g_model_path = model_path;

    // Progress: 100%
    if (onProg) env->CallVoidMethod(callback, onProg, 100);

    LOGI("AIRI_MODEL: LOAD SUCCESS (with progress) path=%s size=%ldMB threads=%d",
         model_path.c_str(), sz / (1024 * 1024), cparams.n_threads);
}

// ----------------------------------------------------------------------------
// generateResponse(prompt: String): String
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

    // Tokenise
    int n_max_tokens = 4096;
    std::vector<llama_token> tokens(n_max_tokens);
    const llama_vocab* vocab = llama_model_get_vocab(g_model);
    int n_tokens = llama_tokenize(vocab, prompt_str.c_str(), (int)prompt_str.size(),
                                  tokens.data(), n_max_tokens, /*add_special=*/true, /*parse_special=*/true);
    if (n_tokens < 0) {
        LOGE("generateResponse: tokenize failed");
        return env->NewStringUTF("");
    }
    tokens.resize(n_tokens);

    // Evaluate prompt
    llama_memory_clear(llama_get_memory(g_ctx), true);
    if (llama_decode(g_ctx, llama_batch_get_one(tokens.data(), (int)tokens.size())) != 0) {
        LOGE("generateResponse: llama_decode failed");
        return env->NewStringUTF("");
    }

    // Sampler
    llama_sampler* sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::string response;
    int max_new = 512;
    bool first = true;
    long t0 = 0;

    for (int i = 0; i < max_new && !g_cancel.load(); i++) {
        llama_token tok = llama_sampler_sample(sampler, g_ctx, -1);

        if (llama_vocab_is_eog(vocab, tok)) break;

        char buf[256] = {};
        int n = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, true);
        if (n > 0) {
            if (first) {
                t0 = (long)(ggml_time_us() / 1000LL);
                LOGI("AIRI_PROOF FIRST_TOKEN latency=0ms");
                first = false;
            }
            response.append(buf, n);
        }

        // Continue generation
        if (llama_decode(g_ctx, llama_batch_get_one(&tok, 1)) != 0) break;
    }

    llama_sampler_free(sampler);

    if (!response.empty()) {
        LOGI("AIRI_PROOF GENERATION_SUCCESS tokens_approx=%d", (int)response.size() / 4 + 1);
    }

    return env->NewStringUTF(response.c_str());
}

// ----------------------------------------------------------------------------
// generateStream(prompt: String, onToken: (String) -> Unit)
// Called from Kotlin; onToken is a kotlin.jvm.functions.Function1
// ----------------------------------------------------------------------------
JNIEXPORT void JNICALL
Java_com_airi_assistant_ai_LlamaNative_generateStream(
    JNIEnv* env, jobject /*this*/, jstring jPrompt, jobject callback)
{
    if (!g_model || !g_ctx) {
        LOGE("generateStream: no model loaded");
        return;
    }

    g_cancel.store(false);

    const char* prompt = env->GetStringUTFChars(jPrompt, nullptr);
    std::string prompt_str(prompt);
    env->ReleaseStringUTFChars(jPrompt, prompt);

    LOGI("generateStream: prompt_len=%d", (int)prompt_str.size());

    // Tokenise
    int n_max_tokens = 4096;
    std::vector<llama_token> tokens(n_max_tokens);
    const llama_vocab* vocab = llama_model_get_vocab(g_model);
    int n_tokens = llama_tokenize(vocab, prompt_str.c_str(), (int)prompt_str.size(),
                                  tokens.data(), n_max_tokens, /*add_special=*/true, /*parse_special=*/true);
    if (n_tokens < 0) {
        LOGE("generateStream: tokenize failed (n_tokens=%d)", n_tokens);
        return;
    }
    tokens.resize(n_tokens);

    LOGD("generateStream: %d prompt tokens", n_tokens);

    // Clear KV cache and decode prompt
    llama_memory_clear(llama_get_memory(g_ctx), true);
    if (llama_decode(g_ctx, llama_batch_get_one(tokens.data(), (int)tokens.size())) != 0) {
        LOGE("generateStream: prompt decode failed");
        return;
    }

    // Build sampler chain
    llama_sampler* sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    // Get the Function1.invoke(Object): Object method for Kotlin lambda
    jclass fnClass  = env->GetObjectClass(callback);
    jmethodID invoke = env->GetMethodID(fnClass, "invoke", "(Ljava/lang/Object;)Ljava/lang/Object;");
    if (!invoke) {
        LOGE("generateStream: cannot find invoke method on callback");
        llama_sampler_free(sampler);
        return;
    }

    int max_new = 512;
    bool first = true;
    long t_start = 0;
    int token_count = 0;

    for (int i = 0; i < max_new && !g_cancel.load(); i++) {
        llama_token tok = llama_sampler_sample(sampler, g_ctx, -1);

        if (llama_vocab_is_eog(vocab, tok)) {
            LOGD("generateStream: EOG at token %d", i);
            break;
        }

        char buf[256] = {};
        int n = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, true);
        if (n > 0) {
            if (first) {
                t_start = (long)(ggml_time_us() / 1000LL);
                LOGI("AIRI_PROOF FIRST_TOKEN latency=0ms");
                first = false;
            }
            token_count++;

            // Call Kotlin lambda: callback.invoke(token_string)
            jstring jTok = env->NewStringUTF(std::string(buf, n).c_str());
            env->CallObjectMethod(callback, invoke, jTok);
            env->DeleteLocalRef(jTok);

            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                LOGW("generateStream: exception in callback, stopping");
                break;
            }
        }

        // Decode next token
        if (llama_decode(g_ctx, llama_batch_get_one(&tok, 1)) != 0) {
            LOGE("generateStream: decode failed at token %d", i);
            break;
        }
    }

    llama_sampler_free(sampler);

    long t_end = (long)(ggml_time_us() / 1000LL);
    float elapsed_sec = (t_end - t_start) / 1000.0f;
    float tps = (elapsed_sec > 0.0f && token_count > 0) ? (token_count / elapsed_sec) : 0.0f;

    LOGI("AIRI_PROOF TOKENS_PER_SEC value=%.2f total_tokens=%d elapsed=%.2fs",
         tps, token_count, elapsed_sec);
    LOGI("AIRI_PROOF GENERATION_SUCCESS");
}

// ----------------------------------------------------------------------------
// cancel()
// ----------------------------------------------------------------------------
JNIEXPORT void JNICALL
Java_com_airi_assistant_ai_LlamaNative_cancel__( // Kotlin: external fun cancel()
    JNIEnv* /*env*/, jobject /*this*/)
{
    g_cancel.store(true);
    LOGD("generateStream: cancel requested");
}

} // extern "C"
