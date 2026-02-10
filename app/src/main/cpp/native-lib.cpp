#include <jni.h>
#include <string>
#include <android/log.h>
#include <mutex>
#include <vector>
#include "llama.cpp/llama.h"

#define TAG "AIRI_NATIVE"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static llama_model* model = nullptr;
static llama_context* ctx = nullptr;
static std::mutex engineMutex;
static bool backendInitialized = false;

extern "C" {

JNIEXPORT jint JNICALL
Java_com_airi_core_NativeBridge_initEngine(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(engineMutex);

    if (backendInitialized) {
        LOGI("Backend already initialized");
        return 0;
    }

    llama_backend_init();
    backendInitialized = true;

    LOGI("Backend initialized successfully");
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_airi_core_NativeBridge_loadModel(JNIEnv* env, jobject, jstring model_path) {

    std::lock_guard<std::mutex> lock(engineMutex);

    if (!backendInitialized) {
        LOGE("Backend not initialized");
        return -10;
    }

    const char* path = env->GetStringUTFChars(model_path, nullptr);

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;

    model = llama_model_load_from_file(path, model_params);

    if (!model) {
        LOGE("Model load failed");
        env->ReleaseStringUTFChars(model_path, path);
        return -1;
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = 2048;
    ctx_params.n_threads = 4;

    ctx = llama_init_from_model(model, ctx_params);

    env->ReleaseStringUTFChars(model_path, path);

    if (!ctx) {
        llama_model_free(model);
        model = nullptr;
        LOGE("Context creation failed");
        return -2;
    }

    LOGI("Model loaded successfully");
    return 0;
}

JNIEXPORT void JNICALL
Java_com_airi_core_NativeBridge_freeModel(JNIEnv*, jobject) {

    std::lock_guard<std::mutex> lock(engineMutex);

    if (ctx) {
        llama_kv_cache_clear(ctx);
        llama_free(ctx);
        ctx = nullptr;
    }

    if (model) {
        llama_model_free(model);
        model = nullptr;
    }

    LOGI("Model and context freed");
}

JNIEXPORT void JNICALL
Java_com_airi_core_NativeBridge_deinitEngine(JNIEnv*, jobject) {

    std::lock_guard<std::mutex> lock(engineMutex);

    if (backendInitialized) {
        llama_backend_deinit();
        backendInitialized = false;
    }

    LOGI("Backend deinitialized");
}

JNIEXPORT jstring JNICALL
Java_com_airi_core_NativeBridge_generateResponse(JNIEnv* env, jobject, jstring prompt) {

    std::lock_guard<std::mutex> lock(engineMutex);

    if (!ctx || !model) {
        return env->NewStringUTF("Model not loaded.");
    }

    const char* prompt_chars = env->GetStringUTFChars(prompt, nullptr);
    std::string promptStr(prompt_chars);
    env->ReleaseStringUTFChars(prompt, prompt_chars);

    const llama_vocab* vocab = llama_model_get_vocab(model);

    // Tokenization
    std::vector<llama_token> tokens;
    tokens.resize(promptStr.length() + 2);
    int n_tokens = llama_tokenize(vocab, promptStr.c_str(), promptStr.length(), tokens.data(), tokens.size(), true, true);
    if (n_tokens < 0) {
        tokens.resize(-n_tokens);
        n_tokens = llama_tokenize(vocab, promptStr.c_str(), promptStr.length(), tokens.data(), tokens.size(), true, true);
    }
    tokens.resize(n_tokens);

    if (tokens.empty()) {
        return env->NewStringUTF("Tokenization failed.");
    }

    llama_batch batch = llama_batch_get_one(tokens.data(), tokens.size());

    if (llama_decode(ctx, batch) != 0) {
        return env->NewStringUTF("Decode failed.");
    }

    std::string output;
    // Simple greedy sampling loop
    for (int i = 0; i < 64; i++) {
        auto n_vocab = llama_vocab_n_tokens(vocab);
        auto * logits = llama_get_logits_ith(ctx, batch.n_tokens - 1);

        llama_token id = 0;
        float max_logit = logits[0];
        for (llama_token t = 1; t < n_vocab; ++t) {
            if (logits[t] > max_logit) {
                max_logit = logits[t];
                id = t;
            }
        }

        if (id == llama_token_eos(vocab))
            break;

        char buf[128];
        int n = llama_token_to_piece(vocab, id, buf, sizeof(buf), 0, true);
        if (n > 0) {
            output.append(buf, n);
        }

        batch = llama_batch_get_one(&id, 1);
        if (llama_decode(ctx, batch) != 0)
            break;
    }

    return env->NewStringUTF(output.c_str());
}

}
