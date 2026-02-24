#include <jni.h>
#include <string>
#include <mutex>
#include <vector>

#ifdef USE_LLAMA
#include "llama.h"
#endif

// Global state for llama.cpp
static std::mutex engineMutex;

#ifdef USE_LLAMA
static llama_model* model = nullptr;
static llama_context* ctx = nullptr;
static llama_batch batch;
#endif

extern "C" JNIEXPORT jstring JNICALL
Java_com_airi_assistant_LlamaNative_loadModel(JNIEnv* env, jobject thiz, jstring model_path) {
    std::lock_guard<std::mutex> lock(engineMutex);
    const char* path = env->GetStringUTFChars(model_path, nullptr);
    
    std::string result;
#ifdef USE_LLAMA
    // Clean up existing context if any
    if (ctx) {
        llama_free(ctx);
        ctx = nullptr;
    }
    if (model) {
        llama_free_model(model);
        model = nullptr;
    }

    llama_model_params model_params = llama_model_default_params();
    model = llama_load_model_from_file(path, model_params);
    
    if (!model) {
        result = "Failed to load model from: ";
        result += path;
    } else {
        llama_context_params ctx_params = llama_context_default_params();
        ctx = llama_new_context_with_model(model, ctx_params);
        if (!ctx) {
            result = "Failed to create context for model: ";
            result += path;
        } else {
            result = "AIRI Core Loaded Successfully: ";
            result += path;
        }
    }
#else
    result = "AIRI (Llama) Engine is disabled in this build. Path: ";
    result += path;
#endif

    env->ReleaseStringUTFChars(model_path, path);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_airi_assistant_LlamaNative_generateResponse(JNIEnv* env, jobject thiz, jstring prompt) {
    std::lock_guard<std::mutex> lock(engineMutex);
    const char* input = env->GetStringUTFChars(prompt, nullptr);
    
    std::string response;
#ifdef USE_LLAMA
    if (!ctx || !model) {
        response = "Error: Model not loaded.";
    } else {
        // Placeholder for actual inference logic
        // In a real implementation, this would involve tokenization, 
        // llama_decode, and sampling.
        response = "AIRI (Native) processed: ";
        response += input;
        
        // Reset timings and clear KV cache if needed to prevent context explosion
#ifdef USE_LLAMA
        llama_kv_cache_clear(ctx);
        llama_reset_timings(ctx);
#endif
    }
#else
    response = "AIRI (Simulated) thinking: ";
    response += input;
#endif

    env->ReleaseStringUTFChars(prompt, input);
    return env->NewStringUTF(response.c_str());
}
