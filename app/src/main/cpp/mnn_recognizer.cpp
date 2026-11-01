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
#include <sys/stat.h>
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
// 配置路径归一化（0.8.9）
//
// MNN 的 Llm::createLLM(path) -> LlmConfig(path) 对 path 只有两种理解：
//   · 带 .json 后缀 → 读这个文件，base_dir = 它所在目录（自动补结尾 "/"）
//   · 其他（含目录）→ 原样当成 base_dir，**不补斜杠**
// 而所有模型文件都是 `base_dir + 文件名` 拼出来的。
//
// 我们以前一直传目录：/storage/emulated/0/Android/data/<pkg>/files/models
// 于是 base_dir 就是这串（没有结尾斜杠），
//   llm_config() = ".../modelsllm_config.json"   ← 多了 "models"
// 配置读空、llm.mnn / llm.mnn.weight 路径全错。
// 而 jniLibs 里这份 libllm.so 没有「文件不存在就 return false」的保护
// （strings 里搜不到 checkFile 的错误串），Module::load 拿到不存在的文件后
// 返回空 module，紧接着被解引用 → SIGSEGV。
//
// 这就是「create 成功、load 必崩」的根因，也是为什么文件大小全对、so 全加载成功。
// 这里统一归一化：传目录就补 config.json，传文件原样用，并校验文件真在。
// ---------------------------------------------------------------------------
static std::string gConfigPath;   // 归一化后的 config.json 绝对路径
static std::string gLastError;    // 最近一次失败原因（给 Java 侧展示）

static void setError(const std::string& msg) {
    gLastError = msg;
    LOGE("%s", msg.c_str());
}

static bool fileExists(const std::string& path) {
    struct stat st;
    return ::stat(path.c_str(), &st) == 0 && S_ISREG(st.st_mode);
}

static bool hasJsonSuffix(const std::string& p) {
    return p.size() > 5 && p.compare(p.size() - 5, 5, ".json") == 0;
}

static std::string normalizeConfigPath(const std::string& raw) {
    std::string p = raw;
    while (p.size() > 1 && p.back() == '/') p.pop_back();
    if (hasJsonSuffix(p)) return p;
    return p + "/config.json";
}

/** config.json 所在目录（结尾一定带 "/"），与 MNN 的 base_dir 对齐 */
static std::string configDir() {
    size_t pos = gConfigPath.find_last_of('/');
    return (pos == std::string::npos) ? std::string("./") : gConfigPath.substr(0, pos + 1);
}

/**
 * 打一行「词表实际长什么样」（0.8.13-alpha4）。
 *
 * 排查「到底是不是词表格式不对」时，这一行是唯一能一锤定音的证据：
 *  - `430 3` → 旧格式文本词表，我们的 libllm 认；
 *  - `430 4` → 新版 PIPELINE 二进制词表，`createTokenizer` 会返回 nullptr 然后崩。
 *
 * 之前只能靠主进程去 stat 文件，但那份可能是替换前的；这里读的是**native 真正要用的那一份**。
 */
static void logTokenizerHead() {
    const std::string path = configDir() + "tokenizer.mtok";
    FILE* f = fopen(path.c_str(), "rb");
    if (f == nullptr) {
        LOGI("tokenizer: 打不开 %s", path.c_str());
        return;
    }
    char buf[17];
    memset(buf, 0, sizeof(buf));
    const size_t n = fread(buf, 1, 16, f);
    fclose(f);
    // 只打到第一个换行，避免日志里出现半个二进制
    for (size_t i = 0; i < n; i++) {
        if (buf[i] == '\n' || buf[i] == '\r') { buf[i] = 0; break; }
    }
    LOGI("tokenizer: %s ← \"%s\"（%zu 字节头）", path.c_str(), buf, n);
}

/** 装载前逐个确认文件在，缺了直接返回 false —— 别让旧版 libllm 崩在 load 里 */
static bool checkModelFiles(std::string* missing) {
    static const char* names[] = {
        "llm.mnn", "llm.mnn.weight", "visual.mnn",
        "visual.mnn.weight", "tokenizer.mtok", "llm_config.json"
    };
    const std::string dir = configDir();
    for (const char* n : names) {
        const std::string p = dir + n;
        if (!fileExists(p)) {
            if (missing != nullptr) *missing = p;
            return false;
        }
    }
    return true;
}

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
    const char* raw = env->GetStringUTFChars(modelDir, nullptr);
    const std::string cfg = normalizeConfigPath(raw == nullptr ? "" : raw);
    if (raw != nullptr) env->ReleaseStringUTFChars(modelDir, raw);

    LOGI("createLLM(config=%s)", cfg.c_str());
    if (!fileExists(cfg)) {
        setError("配置文件不存在：" + cfg +
                 "（MNN 只认 config.json 路径；传目录会拼出 base_dir+文件名的错路径）");
        return JNI_FALSE;
    }
    gConfigPath = cfg;
    gLastError.clear();
    try {
        gLlm.reset(Llm::createLLM(cfg));
    } catch (...) {
        gLlm.reset(nullptr);
    }
    if (gLlm.get() == nullptr) {
        setError("createLLM 返回空对象：模型配置与 libllm.so 版本可能不匹配");
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

/** 加载权重 + 建计算图。崩在这一步 = 权重/内存/指令集问题 */
JNIEXPORT jboolean JNICALL
Java_com_nexus_agent_game_LocalRecognizerNative_load(JNIEnv* /*env*/, jobject /*thiz*/) {
    if (gLlm.get() == nullptr) {
        setError("load 前 llm 对象为空（create 没成功）");
        return JNI_FALSE;
    }
    // 这份 libllm.so 的 load() 不检查文件存在性，缺文件会解引用空 module 直接崩。
    // 我们自己先查一遍，把「文件不在」变成一句可读的失败原因。
    std::string missing;
    if (!checkModelFiles(&missing)) {
        setError("模型文件不存在，已在进入 load 前拦下：" + missing);
        return JNI_FALSE;
    }
    LOGI("llm->load() begin (dir=%s)", configDir().c_str());
    try {
        gLlm->load();
    } catch (...) {
        setError("llm->load() 抛异常：权重格式或内存不足");
        return JNI_FALSE;
    }
    LOGI("llm->load() done");
    gLastError.clear();
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
    // GetStringUTFChars 在内存吃紧时**会返回 nullptr**，直接拼进 std::string 就是
    // std::string(nullptr) → UB → SIGSEGV，而且崩在 native 里 Java 抓不到。
    // 0.8.13-alpha1：这里显式挡一道，至少能返回一句人话。
    if (img == nullptr || p == nullptr) {
        if (img != nullptr) env->ReleaseStringUTFChars(imagePath, img);
        if (p != nullptr) env->ReleaseStringUTFChars(prompt, p);
        setError("GetStringUTFChars 返回空（内存不足？）");
        return nullptr;
    }
    // 0.8.13-alpha4：进出各打一行。崩在 response() 里就只有 enter 没有 done，
    // 配合 logcat 的 libsigchain 调用栈能直接把范围钉死。
    const size_t promptLen = strlen(p);
    LOGI("recognize enter: prompt=%zu 字", promptLen);
    logTokenizerHead();
    std::string content = std::string("<img>") + img + "</img>" + p;
    gBuffer.str("");
    gBuffer.clear();
    LOGI("recognize → response() 开始（content=%zu 字节）", content.size());
    gLlm->response(content, &gBuffer, nullptr, 256);
    std::string result = gBuffer.str();
    LOGI("recognize done: 输出 %zu 字节", result.size());
    env->ReleaseStringUTFChars(imagePath, img);
    env->ReleaseStringUTFChars(prompt, p);
    return env->NewStringUTF(result.c_str());
}

/**
 * 纯文本推理（0.8.13-alpha1）：**故意不带 `<img>`**。
 *
 * 为什么要单独开一个入口：视觉推理崩掉时，「模型根本没起来」和
 * 「模型起来了、崩在视觉编码」的修法完全相反，而 Java 层只能看到「进程没了」。
 * 有了这条纯文本路径，分步自检就能把两者分开：
 *  - 纯文本也崩 → response() 本身 / 文本解码 / 线程配置的问题；
 *  - 纯文本正常、看图才崩 → 图像尺寸 / 视觉编码的中间张量。
 *
 * max_new_tokens 给 32：这里只是探针，不需要长篇大论。
 */
JNIEXPORT jstring JNICALL
Java_com_nexus_agent_game_LocalRecognizerNative_recognizeText(
    JNIEnv* env, jobject /*thiz*/, jstring prompt) {
    if (!gLlm.get()) {
        setError("模型未创建（create 没成功）");
        return nullptr;
    }
    const char* p = env->GetStringUTFChars(prompt, nullptr);
    if (p == nullptr) {
        setError("GetStringUTFChars 返回空（内存不足？）");
        return nullptr;
    }
    std::string content(p);
    env->ReleaseStringUTFChars(prompt, p);
    gBuffer.str("");
    gBuffer.clear();
    LOGI("recognizeText enter: prompt=%zu 字", content.size());
    logTokenizerHead();
    gLlm->response(content, &gBuffer, nullptr, 32);
    std::string result = gBuffer.str();
    LOGI("recognizeText done: 输出 %zu 字节", result.size());
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT void JNICALL
Java_com_nexus_agent_game_LocalRecognizerNative_release(JNIEnv* /*env*/, jobject /*thiz*/) {
    gLlm.reset(nullptr);
}

/** 最近一次 native 失败原因（空串 = 上一步成功）。失败时直接给 UI 看，不用猜。 */
JNIEXPORT jstring JNICALL
Java_com_nexus_agent_game_LocalRecognizerNative_lastError(JNIEnv* env, jobject /*thiz*/) {
    return env->NewStringUTF(gLastError.c_str());
}

} // extern "C"

