#include <jni.h>

#include <memory>
#include <mutex>
#include <sstream>
#include <string>

#include "llm/llm.hpp"

using MNN::Transformer::Llm;
using MNN::Transformer::LlmContext;
using MNN::Transformer::LlmStatus;

namespace {

JavaVM* g_vm = nullptr;
std::mutex g_mutex;
Llm* g_llm = nullptr;
std::string g_config_path;
int g_threads = 0;
std::string g_asr_language;

std::string jstringToUtf8(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string out = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return out;
}

jstring utf8ToJstring(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

void destroyLocked() {
    if (g_llm != nullptr) {
        Llm::destroy(g_llm);
        g_llm = nullptr;
    }
    g_config_path.clear();
    g_threads = 0;
    g_asr_language.clear();
}

jstring throwIllegal(JNIEnv* env, const char* message) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls != nullptr) {
        env->ThrowNew(cls, message);
    }
    return nullptr;
}

void applyAsrLanguageLocked(const std::string& asrLanguage) {
    if (g_llm == nullptr || asrLanguage.empty() || asrLanguage == g_asr_language) {
        return;
    }
    char buf[256];
    snprintf(buf, sizeof(buf), "{\"asr_language\":\"%s\"}", asrLanguage.c_str());
    g_llm->set_config(buf);
    g_asr_language = asrLanguage;
}

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_djh_localasr_qwenasr_QwenAsrNative_nativeRelease(JNIEnv* env, jobject) {
    if (g_vm == nullptr && env != nullptr) {
        env->GetJavaVM(&g_vm);
    }
    std::lock_guard<std::mutex> lock(g_mutex);
    destroyLocked();
}

extern "C" JNIEXPORT void JNICALL
Java_com_djh_localasr_qwenasr_QwenAsrNative_nativeEnsureLoaded(
    JNIEnv* env,
    jobject,
    jstring configPath_,
    jstring tmpPath_,
    jint threads,
    jstring asrLanguage_
) {
    if (g_vm == nullptr && env != nullptr) {
        env->GetJavaVM(&g_vm);
    }
    const std::string configPath = jstringToUtf8(env, configPath_);
    const std::string tmpPath = jstringToUtf8(env, tmpPath_);
    const std::string asrLanguage = jstringToUtf8(env, asrLanguage_);
    const int threadCount = threads < 1 ? 1 : threads;

    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_llm != nullptr && g_config_path == configPath && g_threads == threadCount &&
        g_asr_language == asrLanguage) {
        return;
    }

    destroyLocked();
    std::unique_ptr<Llm> llm(Llm::createLLM(configPath));
    if (llm == nullptr) {
        throwIllegal(env, "Llm::createLLM returned null");
        return;
    }

    char cfg[512];
    snprintf(
        cfg,
        sizeof(cfg),
        "{\"tmp_path\":\"%s\",\"async\":false,\"backend_type\":\"cpu\","
        "\"thread_num\":%d,\"precision\":\"low\",\"memory\":\"low\","
        "\"asr_language\":\"%s\"}",
        tmpPath.c_str(),
        threadCount,
        asrLanguage.c_str()
    );
    llm->set_config(cfg);
    if (!llm->load()) {
        throwIllegal(env, "Qwen3-ASR MNN load() failed");
        return;
    }

    g_llm = llm.release();
    g_config_path = configPath;
    g_threads = threadCount;
    g_asr_language = asrLanguage;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_djh_localasr_qwenasr_QwenAsrNative_nativeRecognize(
    JNIEnv* env,
    jobject,
    jstring wavPath_,
    jint maxNewTokens
) {
    if (g_vm == nullptr && env != nullptr) {
        env->GetJavaVM(&g_vm);
    }
    const std::string wavPath = jstringToUtf8(env, wavPath_);
    const int maxTokens = maxNewTokens < 32 ? 32 : maxNewTokens;

    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_llm == nullptr) {
        return throwIllegal(env, "Qwen3-ASR runtime is not loaded");
    }

    applyAsrLanguageLocked(g_asr_language);

    const std::string userContent = std::string("<audio>") + wavPath + "</audio>";
    std::ostringstream oss;
    g_llm->response(userContent, &oss, nullptr, maxTokens);

    const LlmContext* context = g_llm->getContext();
    if (context == nullptr || context->status == LlmStatus::INTERNAL_ERROR) {
        return throwIllegal(env, "Qwen3-ASR inference failed");
    }

    std::string text = context->generate_str;
    if (text.empty()) {
        text = oss.str();
    }
    return utf8ToJstring(env, text);
}
