#include <jni.h>
#include <string>
#include <sstream>
#include <memory>
#include <cstdio>
#include <cstring>
#include <csignal>
#include <unistd.h>
#include <fcntl.h>
#include <sys/syscall.h>
#include <dlfcn.h>
#include <unwind.h>
#include <cstdint>
#include <android/log.h>

#include "llm.hpp"

using namespace MNN::Transformer;

#define LOG_TAG "MNNJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static std::unique_ptr<Llm> gLlm(nullptr);
static std::ostringstream gBuffer;

// ---------------------------------------------------------------------------
// native 崩溃取证（0.8.8）
//
// 背景：视觉模型在 Llm::load() 里被内核打死，Java 的 try/catch 拦不住，
// CrashHandler 也写不出任何东西 —— 排查全靠猜。
// 这里装一个信号处理器，把「信号类型 / 出错地址 / 调用栈」写进文件再自杀，
// 相当于自己给自己留一份迷你 tombstone，不需要 adb。
//
// 两条纪律：
//  1. 处理器里只做 write/fsync 这类轻动作，绝不 malloc；
//  2. 写完恢复 SIG_DFL 再 raise，让系统照常生成它自己的 tombstone。
// ---------------------------------------------------------------------------

/** 崩溃日志落盘路径（由 Java 侧传入，通常是 filesDir/crash/native_crash.txt） */
static char gCrashPath[512] = {0};
static int gCrashFd = -1;

/** 备用信号栈：栈溢出导致的 SIGSEGV 必须靠它才能跑起来写日志 */
static char gAltStack[64 * 1024];

/** _Unwind_Backtrace 的收集状态 */
struct UnwindState {
    void** frames;
    size_t count;
    size_t max;
};

static _Unwind_Reason_Code unwindCallback(struct _Unwind_Context* ctx, void* arg) {
    UnwindState* st = (UnwindState*)arg;
    uintptr_t pc = (uintptr_t)_Unwind_GetIP(ctx);
    if (pc != 0 && st->count < st->max) {
        st->frames[st->count++] = (void*)pc;
    }
    return (st->count >= st->max) ? _URC_END_OF_STACK : _URC_NO_REASON;
}

static void writeStr(const char* s, size_t n) {
    if (gCrashFd >= 0 && n > 0) write(gCrashFd, s, n);
}

static void crashHandler(int sig, siginfo_t* info, void* /*ucontext*/) {
    if (gCrashFd < 0 && gCrashPath[0] != '\0') {
        gCrashFd = open(gCrashPath, O_WRONLY | O_CREAT | O_APPEND, 0644);
    }
    if (gCrashFd >= 0) {
        char head[320];
        const char* name = sig == SIGSEGV ? "SIGSEGV"
            : sig == SIGBUS ? "SIGBUS"
            : sig == SIGABRT ? "SIGABRT"
            : sig == SIGFPE ? "SIGFPE"
            : sig == SIGILL ? "SIGILL" : "SIG?";
        int n = snprintf(
            head, sizeof(head),
            "\n=== native crash: signal %d (%s) addr=%p pid=%d tid=%d ===\n",
            sig, name, info ? info->si_addr : nullptr,
            (int)getpid(), (int)syscall(SYS_gettid));
        if (n > 0) writeStr(head, (size_t)n);

        // 不用 backtrace()：这个 NDK 的 bionic 已经不声明它了。
        // _Unwind_Backtrace 是编译器运行时能力，稳定可用；再借 dladdr 把地址
        // 翻成「哪个 so + 偏移」，配上 addr2line 就能定位到行。
        void* frames[48];
        UnwindState st = {frames, 0, sizeof(frames) / sizeof(frames[0])};
        _Unwind_Backtrace(unwindCallback, &st);

        for (size_t i = 0; i < st.count; ++i) {
            Dl_info dl;
            char line[384];
            int m;
            if (dladdr(st.frames[i], &dl) != 0 && dl.dli_fname != nullptr) {
                uintptr_t off = (uintptr_t)st.frames[i] - (uintptr_t)dl.dli_fbase;
                m = snprintf(
                    line, sizeof(line),
                    "  #%02zu %s(+0x%zx) %s\n",
                    i,
                    dl.dli_fname,
                    (size_t)off,
                    dl.dli_sname != nullptr ? dl.dli_sname : "?");
            } else {
                m = snprintf(line, sizeof(line), "  #%02zu 0x%p ?\n", i, st.frames[i]);
            }
            if (m > 0) writeStr(line, (size_t)m);
        }
        writeStr("=== end ===\n", 12);
        fsync(gCrashFd);
    }
    // 交回系统，让它照常走自己的崩溃流程（系统 tombstone 仍然有）
    signal(sig, SIG_DFL);
    raise(sig);
}

static void installCrashHandler(const char* logPath) {
    if (logPath != nullptr) {
        strncpy(gCrashPath, logPath, sizeof(gCrashPath) - 1);
        gCrashPath[sizeof(gCrashPath) - 1] = '\0';
    }
    stack_t ss;
    memset(&ss, 0, sizeof(ss));
    ss.ss_sp = gAltStack;
    ss.ss_size = sizeof(gAltStack);
    ss.ss_flags = 0;
    sigaltstack(&ss, nullptr);

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = crashHandler;
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigemptyset(&sa.sa_mask);
    sigaction(SIGSEGV, &sa, nullptr);
    sigaction(SIGBUS, &sa, nullptr);
    sigaction(SIGABRT, &sa, nullptr);
    sigaction(SIGFPE, &sa, nullptr);
    sigaction(SIGILL, &sa, nullptr);
    LOGI("native crash handler installed -> %s", gCrashPath);
}

extern "C" {

JNIEXPORT void JNICALL
Java_com_nexus_agent_game_LocalRecognizerNative_installCrashHandler(
    JNIEnv* env, jobject /*thiz*/, jstring logPath) {
    const char* p = (logPath != nullptr) ? env->GetStringUTFChars(logPath, nullptr) : nullptr;
    installCrashHandler(p);
    if (p != nullptr) env->ReleaseStringUTFChars(logPath, p);
}

/** 只负责「把模型对象建出来」，不加载权重。崩在这一步 = 配置/版本不匹配 */
JNIEXPORT jboolean JNICALL
Java_com_nexus_agent_game_LocalRecognizerNative_create(
    JNIEnv* env, jobject /*thiz*/, jstring modelDir) {
    if (gLlm.get() != nullptr) return JNI_TRUE;
    const char* dir = env->GetStringUTFChars(modelDir, nullptr);
    LOGI("createLLM(%s)", dir);
    try {
        gLlm.reset(Llm::createLLM(dir));
    } catch (...) {
        gLlm.reset(nullptr);
    }
    env->ReleaseStringUTFChars(modelDir, dir);
    if (gLlm.get() == nullptr) {
        LOGE("createLLM returned null (config/model 与 so 版本不匹配？)");
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

/** 加载权重 + 建计算图。崩在这一步 = 权重/内存/指令集问题 */
JNIEXPORT jboolean JNICALL
Java_com_nexus_agent_game_LocalRecognizerNative_load(JNIEnv* /*env*/, jobject /*thiz*/) {
    if (gLlm.get() == nullptr) return JNI_FALSE;
    LOGI("llm->load() begin");
    try {
        gLlm->load();
    } catch (...) {
        LOGE("llm->load() threw");
        return JNI_FALSE;
    }
    LOGI("llm->load() done");
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_nexus_agent_game_LocalRecognizerNative_init(
    JNIEnv* env, jobject thiz, jstring modelDir) {
    if (Java_com_nexus_agent_game_LocalRecognizerNative_create(env, thiz, modelDir) != JNI_TRUE) {
        return JNI_FALSE;
    }
    return Java_com_nexus_agent_game_LocalRecognizerNative_load(env, thiz);
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
Java_com_nexus_agent_game_LocalRecognizerNative_release(JNIEnv* /*env*/, jobject /*thiz*/) {
    gLlm.reset(nullptr);
}

} // extern "C"
