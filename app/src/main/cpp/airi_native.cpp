#include <jni.h>
#include <string>
#include <mutex>
#include <vector>
#include <android/log.h>

extern "C" {
#include "llama.h"
}

#define LOG_TAG "AIRI_NATIVE"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static std::mutex engineMutex;

static llama_model* model = nullptr;
static llama_context* ctx = nullptr;

static const int DEFAULT_N_CTX = 2048;
static const int DEFAULT_N_THREADS = 4;

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
        llama_free_model(model);
        model = nullptr;
    }

    llama_backend_init();

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0; // CPU only (آمن على Android)

    model = llama_load_model_from_file(path, model_params);

    if (!model) {
        result = "Failed to load model.";
        LOGE("Model load failed");
    } else {

        llama_context_params ctx_params = llama_context_default_params();
        ctx_params.n_ctx = DEFAULT_N_CTX;
        ctx_params.n_threads = DEFAULT_N_THREADS;

        ctx = llama_new_context_with_model(model, ctx_params);

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

    if (!ctx || !model) {
        return env->NewStringUTF("Error: Model not loaded.");
    }

    const char* input = env->GetStringUTFChars(prompt, nullptr);
    std::string user_prompt(input);

    // تحويل النص إلى توكنات
    // ملاحظة: llama_tokenize في الإصدارات الحديثة قد تتطلب بارامترات مختلفة قليلاً
    // سنستخدم الهيكل العام المتوافق مع أغلب الإصدارات الحديثة
    std::vector<llama_token> tokens(user_prompt.length() + 1);
    int n_tokens = llama_tokenize(model, user_prompt.c_str(), user_prompt.length(), tokens.data(), tokens.size(), true, false);
    if (n_tokens < 0) {
        tokens.resize(-n_tokens);
        n_tokens = llama_tokenize(model, user_prompt.c_str(), user_prompt.length(), tokens.data(), tokens.size(), true, false);
    }
    tokens.resize(n_tokens);

    if (tokens.empty()) {
        env->ReleaseStringUTFChars(prompt, input);
        return env->NewStringUTF("Tokenization failed.");
    }

    // إنشاء batch للاستدلال
    llama_batch batch = llama_batch_init(tokens.size(), 0, 1);
    for (size_t i = 0; i < tokens.size(); ++i) {
        llama_batch_add(batch, tokens[i], i, { 0 }, i == tokens.size() - 1);
    }

    if (llama_decode(ctx, batch) != 0) {
        llama_batch_free(batch);
        env->ReleaseStringUTFChars(prompt, input);
        return env->NewStringUTF("Decode failed.");
    }

    std::string output;
    const int max_tokens = 128;

    // هنا نحتاج إلى منطق Sampling بسيط (Greedy)
    for (int i = 0; i < max_tokens; ++i) {
        auto n_vocab = llama_n_vocab(model);
        auto * logits = llama_get_logits_ith(ctx, batch.n_tokens - 1);

        llama_token id = 0;
        float max_logit = logits[0];
        for (llama_token v = 1; v < n_vocab; ++v) {
            if (logits[v] > max_logit) {
                max_logit = logits[v];
                id = v;
            }
        }

        if (id == llama_token_eos(model)) {
            break;
        }

        char buf[128];
        int n = llama_token_to_piece(model, id, buf, sizeof(buf), 0, false);
        if (n > 0) {
            output.append(buf, n);
        }

        llama_batch_clear(batch);
        llama_batch_add(batch, id, tokens.size() + i, { 0 }, true);

        if (llama_decode(ctx, batch) != 0) {
            break;
        }
    }

    llama_batch_free(batch);
    llama_kv_cache_clear(ctx);
    llama_reset_timings(ctx);

    env->ReleaseStringUTFChars(prompt, input);

    return env->NewStringUTF(output.c_str());
}
