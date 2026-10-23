#include <jni.h>
#include <string>
#include <sstream>
#include <memory>

#include "llm.hpp"

using namespace MNN::Transformer;

static std::unique_ptr<Llm> gLlm(nullptr);
static std::ostringstream gBuffer;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_nexus_agent_game_LocalRecognizerNative_init(JNIEnv* env, jobject /*thiz*/, jstring modelDir) {
    if (gLlm.get()) {
        return JNI_TRUE;
    }
    const char* dir = env->GetStringUTFChars(modelDir, nullptr);
    try {
        gLlm.reset(Llm::createLLM(dir));
        if (gLlm.get()) {
            gLlm->load();
        }
    } catch (...) {
        gLlm.reset(nullptr);
    }
    env->ReleaseStringUTFChars(modelDir, dir);
    return gLlm.get() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_nexus_agent_game_LocalRecognizerNative_recognize(
    JNIEnv* env, jobject /*thiz*/, jstring imagePath, jstring prompt) {
    if (!gLlm.get()) {
        return nullptr;
    }
    const char* img = env->GetStringUTFChars(imagePath, nullptr);
    const char* p = env->GetStringUTFChars(prompt, nullptr);
    std::string content = std::string("<img>") + img + "</img>" + p;
    gBuffer.str("");
    gBuffer.clear();
    gLlm->response(content, &gBuffer, nullptr, 256);
    std::string result = gBuffer.str();
    env->ReleaseStringUTFChars(imagePath, img);
    env->ReleaseStringUTFChars(prompt, p);
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT void JNICALL
Java_com_nexus_agent_game_LocalRecognizerNative_release(JNIEnv* env, jobject /*thiz*/) {
    gLlm.reset(nullptr);
}

} // extern "C"
