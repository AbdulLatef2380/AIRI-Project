#include <jni.h>
#include <string>

#ifdef USE_LLAMA
#include "llama.h"
#endif

extern "C" JNIEXPORT jstring JNICALL
Java_com_airi_assistant_LlamaNative_loadModel(JNIEnv* env, jobject thiz, jstring model_path) {
    const char* path = env->GetStringUTFChars(model_path, nullptr);
    
    std::string result;
#ifdef USE_LLAMA
    // هنا سيتم وضع كود تحميل النموذج باستخدام llama.cpp
    result = "تم تحميل عقل AIRI (Native) من المسار: ";
    result += path;
#else
    result = "محرك AIRI (Llama) غير مفعل حالياً في هذا البناء. المسار: ";
    result += path;
#endif

    env->ReleaseStringUTFChars(model_path, path);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_airi_assistant_LlamaNative_generateResponse(JNIEnv* env, jobject thiz, jstring prompt) {
    const char* input = env->GetStringUTFChars(prompt, nullptr);
    
    std::string response;
#ifdef USE_LLAMA
    // هنا سيتم وضع كود الاستدلال (Inference)
    response = "AIRI (Native) تفكر في: ";
    response += input;
#else
    response = "AIRI (Simulated) تفكر في: ";
    response += input;
#endif

    env->ReleaseStringUTFChars(prompt, input);
    return env->NewStringUTF(response.c_str());
}
