#include <jni.h>
#include <string>
#include <mutex>
#include <vector>
#include <android/log.h>
#include "llama.h"

#define LOG_TAG "AIRI_NATIVE"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// --- [متغيرات الـ JNI العالمية للـ Progress] ---
static JavaVM* g_vm = nullptr;
static jobject g_callback = nullptr;
static jmethodID g_onProgress = nullptr;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

static bool llama_progress_callback(float progress, void* user_data) {
    if (g_vm && g_callback && g_onProgress) {
        JNIEnv* env;
        jint res = g_vm->GetEnv((void**)&env, JNI_VERSION_1_6);
        if (res == JNI_EDETACHED) {
            g_vm->AttachCurrentThread(&env, nullptr);
        }
        int percent = (int)(progress * 100);
        env->CallVoidMethod(g_callback, g_onProgress, percent);
    }
    return true;
}

// --- [محرك الـ Llama] ---
static std::mutex engineMutex;
static llama_model* model = nullptr;
static llama_context* ctx   = nullptr;
static const llama_vocab* vocab = nullptr;

static const int DEFAULT_N_CTX     = 2048;
static const int DEFAULT_N_THREADS = 4;

static inline void batch_add_token(llama_batch& batch, llama_token token_id, llama_pos pos, llama_seq_id seq_id, bool compute_logits) {
    batch.token[batch.n_tokens] = token_id;
    batch.pos[batch.n_tokens] = pos;
    batch.n_seq_id[batch.n_tokens] = 1;
    batch.seq_id[batch.n_tokens][0] = seq_id;
    batch.logits[batch.n_tokens] = compute_logits ? 1 : 0;
    batch.n_tokens++;
}

extern "C" {

// 1. الدالة الجديدة: تحميل مع Progress
JNIEXPORT void JNICALL
Java_com_airi_assistant_LlamaNative_loadModelWithProgress(JNIEnv* env, jobject thiz, jstring model_path, jobject callback) {
    std::lock_guard<std::mutex> lock(engineMutex);
    
    g_callback = env->NewGlobalRef(callback);
    jclass callbackClass = env->GetObjectClass(callback);
    g_onProgress = env->GetMethodID(callbackClass, "onProgress", "(I)V");

    const char* path = env->GetStringUTFChars(model_path, nullptr);

    if (ctx) { llama_free(ctx); ctx = nullptr; }
    if (model) { llama_model_free(model); model = nullptr; vocab = nullptr; }

    llama_backend_init();
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0; 
    model_params.progress_callback = llama_progress_callback;

    model = llama_model_load_from_file(path, model_params);

    if (model) {
        vocab = llama_model_get_vocab(model);
        llama_context_params ctx_params = llama_context_default_params();
        ctx_params.n_ctx = DEFAULT_N_CTX;
        ctx_params.n_threads = DEFAULT_N_THREADS;
        ctx = llama_init_from_model(model, ctx_params);
        LOGI("Model loaded successfully via Progress API");
    }

    env->ReleaseStringUTFChars(model_path, path);
    env->DeleteGlobalRef(g_callback);
    g_callback = nullptr;
}

// 2. الدالة القديمة: تحميل صامت (للتوافق)
JNIEXPORT jstring JNICALL
Java_com_airi_assistant_LlamaNative_loadModel(JNIEnv* env, jobject, jstring model_path) {
    std::lock_guard<std::mutex> lock(engineMutex);
    const char* path = env->GetStringUTFChars(model_path, nullptr);
    
    if (ctx) { llama_free(ctx); ctx = nullptr; }
    if (model) { llama_model_free(model); model = nullptr; }

    llama_backend_init();
    llama_model_params m_params = llama_model_default_params();
    model = llama_model_load_from_file(path, m_params);

    std::string result = model ? "Success" : "Failed";
    if (model) vocab = llama_model_get_vocab(model);

    env->ReleaseStringUTFChars(model_path, path);
    return env->NewStringUTF(result.c_str());
}

// 3. الدالة الأهم: توليد الرد (Inference)
JNIEXPORT jstring JNICALL
Java_com_airi_assistant_LlamaNative_generateResponse(JNIEnv* env, jobject, jstring prompt) {
    std::lock_guard<std::mutex> lock(engineMutex);
    if (!ctx || !model || !vocab) return env->NewStringUTF("Error: Model not loaded.");

    const char* input = env->GetStringUTFChars(prompt, nullptr);
    std::vector<llama_token> tokens(strlen(input) + 1);
    int n_tokens = llama_tokenize(vocab, input, strlen(input), tokens.data(), tokens.size(), true, false);
    if (n_tokens < 0) { tokens.resize(-n_tokens); n_tokens = llama_tokenize(vocab, input, strlen(input), tokens.data(), tokens.size(), true, false); }
    tokens.resize(n_tokens);

    llama_batch batch = llama_batch_init(tokens.size(), 0, 1);
    for (size_t i = 0; i < tokens.size(); ++i) {
        batch_add_token(batch, tokens[i], i, 0, i == tokens.size() - 1);
    }

    if (llama_decode(ctx, batch) != 0) {
        llama_batch_free(batch);
        env->ReleaseStringUTFChars(prompt, input);
        return env->NewStringUTF("Decode failed.");
    }

    std::string output;
    for (int i = 0; i < 128; ++i) {
        auto n_vocab = llama_vocab_n_tokens(vocab);
        auto* logits = llama_get_logits_ith(ctx, batch.n_tokens - 1);
        llama_token id = 0; float max_logit = logits[0];
        for (llama_token v = 1; v < n_vocab; ++v) { if (logits[v] > max_logit) { max_logit = logits[v]; id = v; } }

        if (id == llama_vocab_eos(vocab)) break;
        char buf[128];
        int n = llama_token_to_piece(vocab, id, buf, sizeof(buf), 0, false);
        if (n > 0) output.append(buf, n);

        batch.n_tokens = 0;
        batch_add_token(batch, id, tokens.size() + i, 0, true);
        if (llama_decode(ctx, batch) != 0) break;
    }

    llama_batch_free(batch);
    env->ReleaseStringUTFChars(prompt, input);
    return env->NewStringUTF(output.c_str());
}

} // extern "C"
