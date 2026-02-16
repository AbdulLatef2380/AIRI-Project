#include <jni.h>
#include <string>
#include "llama.h"

extern "C" JNIEXPORT jstring JNICALL
Java_com_airi_assistant_LlamaNative_loadModel(JNIEnv* env, jobject thiz, jstring model_path) {
    const char* path = env->GetStringUTFChars(model_path, nullptr);
    
    // هنا سيتم وضع كود تحميل النموذج باستخدام llama.cpp
    // حالياً سنعيد رسالة تأكيد للبدء
    std::string result = "تم تحميل عقل AIRI من المسار: ";
    result += path;

    env->ReleaseStringUTFChars(model_path, path);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_airi_assistant_LlamaNative_generateResponse(JNIEnv* env, jobject thiz, jstring prompt) {
    const char* input = env->GetStringUTFChars(prompt, nullptr);
    
    // هنا سيتم وضع كود الاستدلال (Inference)
    // AIRI ستفكر هنا وتعيد الرد
    std::string response = "AIRI تفكر في: ";
    response += input;

    env->ReleaseStringUTFChars(prompt, input);
    return env->NewStringUTF(response.c_str());
}
