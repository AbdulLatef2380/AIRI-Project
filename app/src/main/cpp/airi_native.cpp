// ============================================================
// airi_native.cpp — AIRI LLM Native Bridge
// Fixes applied: C-1, C-2, C-3, C-4, C-5, C-6, C-7, C-8
// Logic is otherwise identical to the original implementation.
// ============================================================

#include <jni.h>
#include <string>
#include <mutex>
#include <vector>
#include <android/log.h>

// C-8 FIX: llama.h is a C++ header. Wrapping it in extern "C" corrupts
// C++ name-mangled linkage. Include it directly, no wrapper.
#include "llama.h"

#define LOG_TAG "AIRI_NATIVE"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static std::mutex engineMutex;

static llama_model*       model = nullptr;
static llama_context*     ctx   = nullptr;
// C-1 / C-2 / C-3 / C-4 FIX: All token-level APIs now require llama_vocab*,
// not llama_model*. Cache the vocab pointer alongside the model so all
// functions in this TU share one consistent handle without restructuring.
static const llama_vocab* vocab = nullptr;

static const int DEFAULT_N_CTX     = 2048;
static const int DEFAULT_N_THREADS = 4;

// ---------------------------------------------------------------------------
// Helper: populate one slot in an already-allocated llama_batch.
//
// C-5 FIX: llama_batch_add() has been removed from the public API.
// Batch slots must be populated by writing the struct fields directly.
// This inline helper replicates exactly what the removed helper did.
// ---------------------------------------------------------------------------
static inline void batch_add_token(llama_batch& batch,
                                   llama_token   token_id,
                                   llama_pos     pos,
                                   llama_seq_id  seq_id,
                                   bool          compute_logits)
{
    batch.token   [batch.n_tokens] = token_id;
    batch.pos     [batch.n_tokens] = pos;
    batch.n_seq_id[batch.n_tokens] = 1;
    batch.seq_id  [batch.n_tokens][0] = seq_id;
    batch.logits  [batch.n_tokens] = compute_logits ? 1 : 0;
    batch.n_tokens++;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_airi_assistant_LlamaNative_loadModel(
        JNIEnv* env,
        jobject,
        jstring model_path) {

    std::lock_guard<std::mutex> lock(engineMutex);

    const char* path = env->GetStringUTFChars(model_path, nullptr);
    std::string result;

    if (ctx) {
        llama_free(ctx);
        ctx = nullptr;
    }

    if (model) {
        // M-1 (included for zero-mismatch): llama_free_model() is deprecated.
        // Use llama_model_free() — the non-deprecated replacement.
        llama_model_free(model);
        model = nullptr;
        vocab = nullptr;
    }

    llama_backend_init();

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0; // CPU only — safe on Android

    // M-1 (included for zero-mismatch): llama_load_model_from_file() is deprecated.
    // Use llama_model_load_from_file() — the non-deprecated replacement.
    model = llama_model_load_from_file(path, model_params);

    if (!model) {
        result = "Failed to load model.";
        LOGE("Model load failed");
    } else {
        // C-1 / C-2 / C-3 / C-4 FIX: obtain and cache the vocab pointer.
        // llama_model_get_vocab() is the accessor for the current API.
        vocab = llama_model_get_vocab(model);

        llama_context_params ctx_params = llama_context_default_params();
        ctx_params.n_ctx     = DEFAULT_N_CTX;
        ctx_params.n_threads = DEFAULT_N_THREADS;

        // M-1 (included for zero-mismatch): llama_new_context_with_model() is deprecated.
        // Use llama_init_from_model() — the non-deprecated replacement.
        ctx = llama_init_from_model(model, ctx_params);

        if (!ctx) {
            result = "Failed to create context.";
            LOGE("Context creation failed");
        } else {
            result = "AIRI Core Loaded Successfully";
            LOGI("Model loaded successfully");
        }
    }

    env->ReleaseStringUTFChars(model_path, path);
    return env->NewStringUTF(result.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_airi_assistant_LlamaNative_generateResponse(
        JNIEnv* env,
        jobject,
        jstring prompt) {

    std::lock_guard<std::mutex> lock(engineMutex);

    if (!ctx || !model || !vocab) {
        return env->NewStringUTF("Error: Model not loaded.");
    }

    const char* input = env->GetStringUTFChars(prompt, nullptr);
    std::string user_prompt(input);

    // C-1 FIX: llama_tokenize() first parameter is now const llama_vocab*,
    // not llama_model*. Pass the cached vocab pointer.
    std::vector<llama_token> tokens(user_prompt.length() + 1);
    int n_tokens = llama_tokenize(vocab,
                                  user_prompt.c_str(),
                                  (int32_t)user_prompt.length(),
                                  tokens.data(),
                                  (int32_t)tokens.size(),
                                  true,
                                  false);
    if (n_tokens < 0) {
        tokens.resize(-n_tokens);
        n_tokens = llama_tokenize(vocab,
                                  user_prompt.c_str(),
                                  (int32_t)user_prompt.length(),
                                  tokens.data(),
                                  (int32_t)tokens.size(),
                                  true,
                                  false);
    }
    tokens.resize(n_tokens);

    if (tokens.empty()) {
        env->ReleaseStringUTFChars(prompt, input);
        return env->NewStringUTF("Tokenization failed.");
    }

    // Allocate batch — llama_batch_init() is unchanged in the current API.
    llama_batch batch = llama_batch_init((int32_t)tokens.size(), 0, 1);

    // C-5 FIX: llama_batch_add() has been removed from the public API.
    // Use the local batch_add_token() helper to populate each slot directly.
    for (size_t i = 0; i < tokens.size(); ++i) {
        batch_add_token(batch,
                        tokens[i],
                        (llama_pos)i,
                        0,                          // seq_id = 0
                        i == tokens.size() - 1);    // compute logits only for last token
    }

    if (llama_decode(ctx, batch) != 0) {
        llama_batch_free(batch);
        env->ReleaseStringUTFChars(prompt, input);
        return env->NewStringUTF("Decode failed.");
    }

    std::string output;
    const int max_tokens = 128;

    // Greedy sampling loop — logic preserved exactly from original.
    for (int i = 0; i < max_tokens; ++i) {
        // C-4 FIX: llama_n_vocab() is deprecated and takes llama_vocab*.
        // Use llama_vocab_n_tokens(vocab) — the non-deprecated replacement.
        auto n_vocab = llama_vocab_n_tokens(vocab);
        auto* logits = llama_get_logits_ith(ctx, batch.n_tokens - 1);

        llama_token id = 0;
        float max_logit = logits[0];
        for (llama_token v = 1; v < n_vocab; ++v) {
            if (logits[v] > max_logit) {
                max_logit = logits[v];
                id = v;
            }
        }

        // C-3 FIX: llama_token_eos() is deprecated and takes llama_vocab*.
        // Use llama_vocab_eos(vocab) — the non-deprecated replacement.
        if (id == llama_vocab_eos(vocab)) {
            break;
        }

        char buf[128];
        // C-2 FIX: llama_token_to_piece() first parameter is now const llama_vocab*,
        // not llama_model*. Pass the cached vocab pointer.
        int n = llama_token_to_piece(vocab, id, buf, sizeof(buf), 0, false);
        if (n > 0) {
            output.append(buf, n);
        }

        // C-6 FIX: llama_batch_clear() has been removed from the public API.
        // Reset the token count directly — this is the exact equivalent operation.
        batch.n_tokens = 0;

        // C-5 FIX (second site): same batch_add_token() helper for the decode loop.
        batch_add_token(batch,
                        id,
                        (llama_pos)(tokens.size() + i),
                        0,      // seq_id = 0
                        true);  // always compute logits for the single new token

        if (llama_decode(ctx, batch) != 0) {
            break;
        }
    }

    llama_batch_free(batch);

    env->ReleaseStringUTFChars(prompt, input);
    return env->NewStringUTF(output.c_str());
}
