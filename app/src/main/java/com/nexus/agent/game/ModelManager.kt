package com.nexus.agent.game

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.DocumentsContract
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * 模型下载管理（瘦身版 APK 用）。
 *
 * 瘦身版 APK 不内置本地大模型（视觉 Qwen2-VL-2B 1.3GB + SenseVoice 229MB），
 * 首次需要时从 GitHub Releases 下载到外部存储，之后直接加载。
 *
 * 下载策略：
 *  - 多源尝试：GitHub 加速镜像（国内快）→ GitHub 直连兜底
 *  - 断点续传：下载中断保留 .part，重试时用 Range 从断点继续
 *  - 长超时：大文件下载 readTimeout 300s，避免慢网络被误判超时
 *  - 外部下载器兜底：内置下载太慢时，用户可用 IDM/迅雷/aria2 下好 zip，
 *    再用 [importZip] / [importFolder] 从本地直接导入（不走网络）
 */
object ModelManager {

    private const val TAG = "ModelManager"

    /** GitHub Release 下载源（release tag = "models"） */
    private const val GITHUB_RELEASE =
        "https://github.com/2581414411-blip/LunaMacro/releases/download/models"

    /** 视觉模型包名（给 UI / 外部下载器展示） */
    const val VISION_ZIP_NAME = "models.zip"

    /** 语音模型包名 */
    const val SENSEVOICE_ZIP_NAME = "sensevoice.zip"

    /**
     * 手动下载地址（供外部下载器使用）：镜像在前，官方源兜底。
     * @return zip 文件名 → 候选 URL 列表（第一项通常是国内最快的镜像）
     */
    fun manualUrls(zipName: String = VISION_ZIP_NAME): List<String> =
        MIRRORS.map { it + GITHUB_RELEASE + "/" + zipName } + (GITHUB_RELEASE + "/" + zipName)

    /** 官方直链（GitHub），给电脑端下载器用 */
    fun officialUrl(zipName: String): String = GITHUB_RELEASE + "/" + zipName

    /**
     * GitHub 加速镜像前缀（国内下载更快，逐个尝试）。
     *
     * 2026-10-30 国内直连实测（models.zip 前 8MB）：
     *  - ghfast.top     206 可续传，≈1.2MB/s ← 主力
     *  - gh-proxy.com   206 可续传，但慢（≈100KB/s）且偶发忽略 Range
     *  - ghproxy.net    206 可续传，握手就要 15s ← 最后兜底
     *  - GitHub 官方直连：models.zip 直接超时（1.3GB 压根下不动）
     *  - gh.llkk.cc / github.moeyy.xyz / gh.xxooo.cf / ghps.cc / gh.ddlc.top 均已失效
     * 所以内置下载只当兜底，大文件建议外部下载器 + 本地导入。
     */
    private val MIRRORS = listOf(
        "https://ghfast.top/",
        "https://gh-proxy.com/",
        "https://ghproxy.net/",
    )

    private const val VISION_ZIP = VISION_ZIP_NAME
    private const val SENSEVOICE_ZIP = SENSEVOICE_ZIP_NAME

    /** 各 zip 大致体积（字节，用于下载前空间预检） */
    private const val VISION_ZIP_BYTES = 1_389_000_000L      // ≈1.29GB
    private const val SENSEVOICE_ZIP_BYTES = 239_600_000L    // ≈228MB

    const val VISION_DIR = "models"
    const val SENSEVOICE_DIR = "sensevoice"

    /** 视觉模型必需文件 */
    private val VISION_FILES = listOf(
        "llm.mnn", "llm.mnn.weight",
        "visual.mnn", "visual.mnn.weight",
        "tokenizer.mtok", "config.json"
    )

    // ------------------------------------------------------------------ 魔搭（ModelScope）源

    /**
     * 魔搭上的 **MNN 官方** Qwen2-VL-2B 包（0.8.13-alpha3 新增）。
     *
     * 为什么加这个源：GitHub Release 在国内基本下不动（1.3GB 走镜像也只有 1MB/s 左右），
     * 而魔搭是阿里自己的站，实测 tokenizer 3MB 只用 0.76s，整包走的是国内 CDN。
     *
     * 注意这套文件和 LunaMacro 的 models 包**不是同一份**（权重 869MB vs 965MB），
     * 不能混用：要么整包换，要么只换词表（我们只换词表）。
     */
    const val MODELSCOPE_REPO = "MNN/Qwen2-VL-2B-Instruct-MNN"

    private const val MODELSCOPE_BASE =
        "https://modelscope.cn/models/" + MODELSCOPE_REPO + "/resolve/master"

    /**
     * 魔搭包的文件对应关系（官方用 tokenizer.txt，落到本地仍叫 tokenizer.mtok）。
     *
     * **必须 9 个，一个都不能少。** 0.8.13-alpha7 的清单只有 7 个，漏了
     * `embeddings_bf16.bin` 和 `llm.mnn.json` —— 真机上直接崩：
     *
     * ```
     * llm->load() done                      ← 装得像模像样
     * recognizeText enter                   ← 一推理就死
     * F/libc: FORTIFY: fseeko: null FILE*
     * ```
     *
     * 原因在 MNN 的 `DiskEmbedding` 构造函数：
     * ```cpp
     * if (tie_embeddings.size() == 5) { fp_ = fopen(llm_weight); }   // 内嵌
     * else                            { fp_ = fopen(embeddings_bf16.bin); }
     * ```
     * 魔搭这份 `llm_config.json` 没有 `tie_embeddings`，于是它去开
     * `embeddings_bf16.bin`；文件不存在 → `fp_` 是 null → 后面 `fseeko` 被
     * FORTIFY 拦下 → abort。**装载阶段完全不报错，只有真正推理才炸。**
     */
    private val MODELSCOPE_FILES = listOf(
        "config.json" to "config.json",
        "llm_config.json" to "llm_config.json",
        "llm.mnn" to "llm.mnn",
        "llm.mnn.weight" to "llm.mnn.weight",
        "embeddings_bf16.bin" to "embeddings_bf16.bin",
        "llm.mnn.json" to "llm.mnn.json",
        "visual.mnn" to "visual.mnn",
        "visual.mnn.weight" to "visual.mnn.weight",
        "tokenizer.txt" to "tokenizer.mtok"
    )

    /** 魔搭包总体积（≈1.72GB，含 466MB 的 embeddings_bf16.bin） */
    private const val MODELSCOPE_BYTES = 1_713_000_000L

    /**
     * 只补 embedding 文件（0.8.13-alpha8）。
     *
     * 修的就是上面那个 `fseeko: null FILE*`：已经有 llm/visual 的用户
     * 只差这一个 466MB 的文件，不用把 869MB 权重重下一遍。
     */
    private val MODELSCOPE_EMBEDDING_FILES = listOf(
        "embeddings_bf16.bin" to "embeddings_bf16.bin",
        "llm.mnn.json" to "llm.mnn.json"
    )

    /** embedding 两件套的体积（约 469MB，空间预检用） */
    private const val MODELSCOPE_EMBEDDING_BYTES = 469_000_000L

    /**
     * 只补视觉编码器要下的两个文件（0.8.13-alpha5）。
     *
     * 换完还崩就说明 llm.mnn 也不配套，那时再走整包。
     */
    private val MODELSCOPE_VISUAL_FILES = listOf(
        "visual.mnn" to "visual.mnn",
        "visual.mnn.weight" to "visual.mnn.weight"
    )

    /** 视觉编码器两个文件的体积（约 376MB，空间预检用） */
    private const val MODELSCOPE_VISUAL_BYTES = 377_000_000L

    /**
     * 从魔搭下载整套视觉模型（国内直连，逐文件断点续传）。
     *
     * 只下视觉那一套；语音模型仍走原来的 GitHub 源。
     *
     * @param onProgress 0~1 + 阶段文字；-1 表示失败（文字就是原因）
     */
    suspend fun ensureModelsFromModelScope(
        context: Context,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Boolean {
        val ok = downloadModelScopeFiles(
            context = context,
            files = MODELSCOPE_FILES,
            needBytes = MODELSCOPE_BYTES,
            label = "视觉模型",
            onProgress = onProgress
        )
        if (ok) {
            // 官方包的 config.json 没写 tokenizer_file，而官方词表叫 tokenizer.txt、
            // 我们落盘叫 tokenizer.mtok，这里补一个字段指过去。
            runCatching { patchTokenizerFile(File(visionDir(context), "config.json")) }
                .onFailure { Log.w(TAG, "补 tokenizer_file 失败：${it.message}") }
        }
        return ok
    }

    /**
     * 只从魔搭补视觉编码器（visual.mnn + visual.mnn.weight，约 376MB）。
     *
     * 真机栈证明崩溃在 `Mllm::vision_process -> Module::forward -> Variable::prepareCompute`，
     * 分词已经过了（日志里 `tokenizer: ... <- "430 3"`），死在把图片喂进 visual.mnn 那一步。
     * 那就只换它，不必为了 614KB 的图把 869MB 的 llm 权重也重下一遍。
     */
    suspend fun ensureVisualEncoderFromModelScope(
        context: Context,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Boolean = downloadModelScopeFiles(
        context = context,
        files = MODELSCOPE_VISUAL_FILES,
        needBytes = MODELSCOPE_VISUAL_BYTES,
        label = "视觉编码器",
        onProgress = onProgress
    )

    /**
     * 魔搭逐文件下载的通用实现（整包 / 只补视觉共用）。
     *
     * @param files     (魔搭上的文件名, 本地落盘名)
     * @param needBytes 空间预检用的总体积
     */
    private suspend fun downloadModelScopeFiles(
        context: Context,
        files: List<Pair<String, String>>,
        needBytes: Long,
        label: String,
        onProgress: (Float, String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        if (downloading) return@withContext false
        downloading = true
        try {
            val target = visionDir(context).apply { mkdirs() }
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            val available = freeBytes(base)
            if (available in 0 until needBytes) {
                val msg = "存储空间不足：$label 约 ${needBytes / 1024 / 1024}MB，" +
                    "当前可用 ${available / 1024 / 1024}MB"
                Log.e(TAG, msg)
                emit(onProgress, -1f, msg)
                return@withContext false
            }

            val total = files.size
            for ((index, pair) in files.withIndex()) {
                val (remote, local) = pair
                val dst = File(target, local)
                val tmp = File(target, "$local.part")
                val weight = index.toFloat() / total
                emit(onProgress, weight, "魔搭 $remote（${index + 1}/$total）")

                val url = "$MODELSCOPE_BASE/$remote"
                Log.i(TAG, "魔搭下载 $url -> $local")
                val ok = downloadOne(url, tmp, "魔搭") { p, st ->
                    onProgress(weight + p / total, "$remote：$st")
                }
                if (!ok) {
                    emit(onProgress, -1f, "魔搭下载失败：$remote（已下部分保留，可重试续传）")
                    return@withContext false
                }
                if (dst.exists()) dst.delete()
                if (!tmp.renameTo(dst)) {
                    tmp.copyTo(dst, overwrite = true)
                    tmp.delete()
                }
                emit(onProgress, (index + 1f) / total, "完成 $local")
            }
            emit(onProgress, 1f, "$label 就绪（魔搭源）")
            Log.i(TAG, "$label 下载完成：${target.absolutePath}")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "$label 下载失败", e)
            emit(onProgress, -1f, "下载失败：${e.message ?: e.javaClass.simpleName}")
            false
        } finally {
            downloading = false
        }
    }

    /** SenseVoice 必需文件 */
    private val SENSEVOICE_FILES = listOf("model.int8.onnx", "tokens.txt")

    @Volatile
    var downloading = false
        private set

    /** 本地导入进行中（zip / 文件夹） */
    @Volatile
    var importing = false
        private set

    /** 当前阶段文字（供 UI 在重进页面时回显） */
    @Volatile
    var lastStage: String = ""
        private set

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun visionDir(context: Context): File =
        File(context.getExternalFilesDir(null), VISION_DIR)

    fun senseVoiceDir(context: Context): File =
        File(context.getExternalFilesDir(null), SENSEVOICE_DIR)

    /**
     * 临时下载目录：放在外部存储（与解压目标同一存储卷）。
     *
     * 不用 cacheDir：① 内部存储通常只有几 GB，1.3GB zip + 1.3GB 解压会直接写满；
     * ② cacheDir 会被系统在低存储时自动清理，下载到一半被清掉就永远续不上。
     */
    private fun tmpDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, ".download").apply { mkdirs() }
    }

    /** 目标存储卷可用空间（字节） */
    private fun freeBytes(dir: File): Long = try {
        val st = StatFs(dir.absolutePath)
        st.availableBytes
    } catch (e: Throwable) {
        Log.w(TAG, "读取可用空间失败", e)
        -1L
    }

    /** 视觉模型是否就绪 */
    fun isVisionModelReady(context: Context): Boolean {
        val dir = visionDir(context)
        return VISION_FILES.all { f -> File(dir, f).exists() && File(dir, f).length() > 0 }
    }

    /**
     * 视觉模型包属于哪一套（0.8.13-alpha6）。
     *
     * 为什么需要这个：`isVisionModelReady` 只回答「文件都下全了没」，
     * 而真机上崩得最惨的情况是**文件全在、版本不配套** —— 那时 UI 判定「已就绪」，
     * 用户既看不到修复按钮，也不知道自己该下哪一套。这里用文件大小指纹判来源。
     *
     * 两套已知包（0.8.13 实测）：
     *
     * | 文件 | LunaMacro 包 | 魔搭官方包 |
     * |---|---|---|
     * | llm.mnn | 453,608 | 448,032 |
     * | llm.mnn.weight | 965,665,010 | 869,204,210 |
     * | visual.mnn | 679,296 | 613,968 |
     * | visual.mnn.weight | 417,802,720 | 376,018,140 |
     */
    enum class VisionBundle(val label: String, val advice: String) {
        LUNAR(
            "LunaMacro 包（新版 MNN 导出的）",
            "词表已自动换成旧格式（这步有效），但 visual.mnn 与 3.0.3 引擎不配套 —— 点下面「补视觉编码器」"
        ),
        MODELSCOPE(
            "魔搭官方包（与 3.0.3 引擎配套）",
            "版本对得上；如果还崩，导出日志看新的调用栈"
        ),
        MIXED(
            "混装（两套文件混着用）",
            "visual 与 llm 不是同源的，最容易崩 —— 先点「补视觉编码器」，还不行就整包重下"
        ),
        UNKNOWN("未知来源", "大小与两套官方包都不匹配，建议整包重下"),
        MISSING("未下载", "点下面「从魔搭下载」")
    }

    /**
     * embedding 是内嵌在权重里，还是需要单独的 `embeddings_bf16.bin`（0.8.13-alpha8）。
     *
     * MNN 的 `DiskEmbedding` 构造函数是二选一：
     * ```cpp
     * if (tie_embeddings.size() == 5) { fp_ = fopen(llm_weight); }        // 内嵌
     * else                            { fp_ = fopen(embeddings_bf16.bin); }
     * ```
     * 魔搭那份 `llm_config.json` 没有 `tie_embeddings`，所以必须有那个 466MB 的独立文件。
     * 少了它 `fopen` 返回 null，后面 `fseeko` 被 FORTIFY 拦下 → SIGABRT，
     * 而且**装载阶段完全不报错**，只在真正推理时炸 —— 极难排查，所以单独做成可查状态。
     */
    enum class EmbeddingMode(val label: String) {
        /** 有 tie_embeddings → embedding 就在 llm.mnn.weight 里，不需要额外文件 */
        TIED("内嵌在权重里（LunaMacro 包是这种，不需要额外文件）"),

        /** 独立文件模式，文件已就位 */
        SEPARATE_OK("独立文件 embeddings_bf16.bin 已就位"),

        /** 独立文件模式但文件不见了 —— 一推理就 abort */
        SEPARATE_MISSING("缺 embeddings_bf16.bin —— 推理必崩（fseeko: null FILE*）"),

        /** 没有 llm_config.json，无从判断 */
        UNKNOWN("没有 llm_config.json，无法判断")
    }

    fun embeddingMode(context: Context): EmbeddingMode {
        val dir = visionDir(context)
        val cfg = File(dir, "llm_config.json")
        if (!cfg.isFile) return EmbeddingMode.UNKNOWN
        val text = runCatching { cfg.readText(Charsets.UTF_8) }.getOrNull()
            ?: return EmbeddingMode.UNKNOWN
        if (text.contains("\"tie_embeddings\"")) return EmbeddingMode.TIED
        val emb = File(dir, "embeddings_bf16.bin")
        // 官方这份是 466,747,392 B。阈值放 400MB 而不是「大于 1MB」——
        // 下到一半（比如 30MB）的文件同样会让 DiskEmbedding 读到垃圾数据，
        // 必须当成「没下完」处理，否则又变成一推理就崩。
        return if (emb.isFile && emb.length() > 400_000_000L) {
            EmbeddingMode.SEPARATE_OK
        } else {
            EmbeddingMode.SEPARATE_MISSING
        }
    }

    /**
     * 按文件大小指纹判断当前模型包来源。
     *
     * 大文件允许 2% 偏差（不同导出时间会差几字节），
     * visual.mnn 这种几百 KB 的图文件差异很显著，直接比。
     */
    fun detectVisionBundle(context: Context): VisionBundle {
        val dir = visionDir(context)
        val visual = File(dir, "visual.mnn").length()
        val visualWeight = File(dir, "visual.mnn.weight").length()
        val llmWeight = File(dir, "llm.mnn.weight").length()
        if (visual <= 0 || visualWeight <= 0 || llmWeight <= 0) return VisionBundle.MISSING

        fun near(actual: Long, expected: Long): Boolean =
            actual > 0 && kotlin.math.abs(actual - expected) <= expected / 50  // 2%

        val visualIsMs = near(visual, 613_968L) && near(visualWeight, 376_018_140L)
        val visualIsLm = near(visual, 679_296L) && near(visualWeight, 417_802_720L)
        val llmIsMs = near(llmWeight, 869_204_210L)
        val llmIsLm = near(llmWeight, 965_665_010L)

        return when {
            visualIsMs && llmIsMs -> VisionBundle.MODELSCOPE
            visualIsLm && llmIsLm -> VisionBundle.LUNAR
            (visualIsMs && llmIsLm) || (visualIsLm && llmIsMs) -> VisionBundle.MIXED
            else -> VisionBundle.UNKNOWN
        }
    }

    /** SenseVoice 是否就绪 */
    fun isSenseVoiceReady(context: Context): Boolean {
        val dir = senseVoiceDir(context)
        return SENSEVOICE_FILES.all { f -> File(dir, f).exists() && File(dir, f).length() > 0 }
    }

    /**
     * 下载缺失的模型（视觉 + 语音）。
     *
     * @param onProgress 进度回调（0~1 总进度，-1 表示未知；stage 为可读阶段文字）
     * @return 是否全部就绪
     */
    suspend fun ensureModels(
        context: Context,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Boolean = withContext(Dispatchers.IO) {
        if (downloading) return@withContext false
        downloading = true
        try {
            val needVision = !isVisionModelReady(context)
            val needSenseVoice = !isSenseVoiceReady(context)
            if (!needVision && !needSenseVoice) {
                emit(onProgress, 1f, "模型已就绪")
                return@withContext true
            }

            // 空间预检：zip + 解压后 ≈ 2 倍体积（已存在的 .part 可抵扣）
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            base.mkdirs()
            val available = freeBytes(base)
            var need = 0L
            if (needVision) {
                val part = File(tmpDir(context), VISION_ZIP + ".part").length()
                need += (VISION_ZIP_BYTES - part).coerceAtLeast(0) + VISION_ZIP_BYTES
            }
            if (needSenseVoice) {
                val part = File(tmpDir(context), SENSEVOICE_ZIP + ".part").length()
                need += (SENSEVOICE_ZIP_BYTES - part).coerceAtLeast(0) + SENSEVOICE_ZIP_BYTES
            }
            if (available in 0 until need) {
                val msg = "存储空间不足：还需约 ${need / 1024 / 1024 / 1024}GB，" +
                    "当前可用 ${available / 1024 / 1024 / 1024}GB"
                Log.e(TAG, msg)
                emit(onProgress, -1f, msg)
                return@withContext false
            }

            var ok = true
            if (needVision) {
                ok = downloadAndExtract(context, VISION_ZIP, visionDir(context)) { p, s ->
                    onProgress(p * 0.7f, s)
                }
            }
            if (ok && needSenseVoice) {
                ok = downloadAndExtract(context, SENSEVOICE_ZIP, senseVoiceDir(context)) { p, s ->
                    onProgress(0.7f + p * 0.3f, s)
                }
            }
            if (ok) emit(onProgress, 1f, "模型就绪") else emit(onProgress, -1f, lastStage)
            ok
        } catch (e: Throwable) {
            Log.e(TAG, "模型下载失败", e)
            emit(onProgress, -1f, "下载失败：${e.message ?: e.javaClass.simpleName}")
            false
        } finally {
            downloading = false
        }
    }

    /**
     * 给魔搭官方包的 `config.json` 补上 `tokenizer_file`。
     *
     * 官方那份只有 llm_model / llm_weight / backend_type…，词表文件名靠 so 的默认值；
     * 我们把 `tokenizer.txt` 落成了 `tokenizer.mtok`（后端只认这个名字），所以得显式指过去。
     * 已经有这个字段就不动。
     */
    private fun patchTokenizerFile(cfg: File) {
        if (!cfg.isFile) return
        val text = cfg.readText(Charsets.UTF_8)
        if (text.contains("\"tokenizer_file\"")) return
        val idx = text.lastIndexOf('}')
        if (idx <= 0) return
        val head = text.substring(0, idx).trimEnd().trimEnd(',')
        val patched = head + ",\n    \"tokenizer_file\": \"tokenizer.mtok\"\n" + text.substring(idx)
        cfg.writeText(patched, Charsets.UTF_8)
        Log.i(TAG, "已补 tokenizer_file=tokenizer.mtok（魔搭官方 config 没写）")
    }

    private fun emit(onProgress: (Float, String) -> Unit, p: Float, stage: String) {
        lastStage = stage
        try {
            onProgress(p, stage)
        } catch (e: Throwable) {
            Log.w(TAG, "进度回调异常", e)
        }
    }

    /**
     * 下载一个 zip（多源 + 断点续传）并解压到目标目录。
     * 进度回调 p 为 0~1（未知时 -1），s 为可读文字。
     */
    private fun downloadAndExtract(
        context: Context,
        zipName: String,
        targetDir: File,
        onProgress: (Float, String) -> Unit
    ): Boolean {
        targetDir.mkdirs()
        val tmp = File(tmpDir(context), "$zipName.part")
        val sources = MIRRORS.map { it + GITHUB_RELEASE + "/" + zipName } +
            (GITHUB_RELEASE + "/" + zipName)

        var downloaded = false
        for ((i, url) in sources.withIndex()) {
            val label = if (i < MIRRORS.size) "镜像${i + 1}" else "官方源"
            Log.i(TAG, "尝试下载 [$label] $url")
            emit(onProgress, -1f, "连接$label…")
            val ok = downloadOne(url, tmp, label) { p, s ->
                onProgress(p, s)
            }
            if (ok) {
                downloaded = true
                break
            }
            // 该源彻底失败且没下到任何字节才丢掉 .part（下到一半的保留以便续传）
            if (tmp.exists() && tmp.length() == 0L) tmp.delete()
        }
        if (!downloaded) {
            Log.e(TAG, "所有下载源均失败: $zipName")
            emit(onProgress, -1f, "所有下载源均失败（已下载部分已保留，可重试续传）")
            return false
        }

        return try {
            emit(onProgress, 0.95f, "解压$zipName…")
            extractZip(tmp, targetDir)
            tmp.delete()
            true
        } catch (e: Throwable) {
            Log.e(TAG, "解压失败 $zipName", e)
            tmp.delete()
            emit(onProgress, -1f, "解压失败：${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    /**
     * 从单个源下载（支持断点续传）。
     *
     * 进度回调做了节流：只有百分比整数变化才上报，避免 1.3GB 文件触发上万次
     * Compose 重组把 UI 卡住（这是「下载时界面像卡死」的主因）。
     */
    private fun downloadOne(
        url: String,
        tmp: File,
        label: String,
        onProgress: (Float, String) -> Unit
    ): Boolean {
        val startOffset = if (tmp.exists()) tmp.length() else 0L
        val reqBuilder = Request.Builder().url(url)
        if (startOffset > 0) reqBuilder.header("Range", "bytes=$startOffset-")
        return try {
            client.newCall(reqBuilder.build()).execute().use { resp ->
                val code = resp.code
                if (code != 200 && code != 206) {
                    Log.e(TAG, "下载失败 HTTP $code: $url")
                    emit(onProgress, -1f, "[$label] HTTP $code")
                    return false
                }
                // 206=按断点续传追加；200=服务端忽略 Range，必须从头覆盖写
                val append = code == 206
                val body = resp.body ?: return false
                val total = if (append) {
                    (body.contentLength() + startOffset).takeIf { it > 0 }
                } else {
                    body.contentLength().takeIf { it > 0 }
                }
                var done = if (append) startOffset else 0L
                var lastPct = -1
                var lastEmitAt = 0L
                FileOutputStream(tmp, append).use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val pct = if (total != null && total > 0) {
                                (done * 100 / total).toInt()
                            } else {
                                -1
                            }
                            val now = System.currentTimeMillis()
                            val changed = pct >= 0 && pct != lastPct
                            if (changed && (now - lastEmitAt >= 250L || pct == 100)) {
                                lastPct = pct
                                lastEmitAt = now
                                val s = if (total != null && total > 0) {
                                    "[$label] $pct% · ${done / 1024 / 1024}MB / " +
                                        "${total / 1024 / 1024}MB"
                                } else {
                                    "[$label] ${done / 1024 / 1024}MB"
                                }
                                onProgress(
                                    if (total != null && total > 0) done.toFloat() / total else -1f,
                                    s
                                )
                            }
                        }
                    }
                }
                Log.i(TAG, "下载完成: $url (${tmp.length() / 1024 / 1024} MB)")
                emit(onProgress, 1f, "[$label] 下载完成")
                true
            }
        } catch (e: Throwable) {
            Log.e(TAG, "下载异常 $url", e)
            val kept = if (tmp.exists()) tmp.length() / 1024 / 1024 else 0L
            emit(onProgress, -1f, "[$label] 中断（已缓存 ${kept}MB，可续传）")
            false
        }
    }

    // ───────────────────── 本地导入（外部下载器兜底，不走网络） ─────────────────────

    /**
     * 导入用户用外部下载器（IDM / 迅雷 / aria2 / 浏览器）下好的模型包 zip。
     *
     * 流程：全部解到暂存目录 → 按文件名归类搬进 models/ 与 sensevoice/。
     * 所以单包（models.zip）、双包（含 models/ 与 sensevoice/ 两个目录）、
     * 带一层外层目录的包都能吃。
     */
    suspend fun importZip(
        context: Context,
        uri: Uri,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Boolean = withContext(Dispatchers.IO) {
        runImport(context, onProgress) { staging, report ->
            val raw = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("无法读取所选文件，请重新选择（文件可能已被移动或删除）")
            var done = 0L
            var files = 0
            raw.buffered().use { buffered ->
                ZipInputStream(buffered).use { zis ->
                    while (true) {
                        val entry = zis.nextEntry ?: break
                        val name = entry.name.replace('\\', '/')
                        // 跳过目录、macOS 垃圾文件与路径穿越
                        if (entry.isDirectory || name.startsWith("__MACOSX/") ||
                            name.endsWith(".DS_Store") || name.contains("..")
                        ) {
                            zis.closeEntry()
                            continue
                        }
                        copyStream(zis, File(staging, name)) { n ->
                            done += n
                            report("正在解压：$files 个文件 / ${done / 1024 / 1024}MB")
                        }
                        files++
                        zis.closeEntry()
                    }
                }
            }
            files
        }
    }

    /**
     * 导入已解压的模型文件夹（用户在手机用 MT 管理器 / RAR 解压后直接选目录）。
     * 递归复制目录树，最后同样按文件名归类。
     */
    suspend fun importFolder(
        context: Context,
        treeUri: Uri,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Boolean = withContext(Dispatchers.IO) {
        runImport(context, onProgress) { staging, report ->
            val counter = IntArray(1)
            var done = 0L
            copyDocTree(context, treeUri, staging, { n ->
                done += n
                report("正在复制：${counter[0]} 个文件 / ${done / 1024 / 1024}MB")
            }, counter)
            counter[0]
        }
    }

    /**
     * 导入骨架：暂存 → 归类 → 校验。extract 返回导入的文件数量。
     */
    private fun runImport(
        context: Context,
        onProgress: (Float, String) -> Unit,
        extract: (staging: File, report: (String) -> Unit) -> Int
    ): Boolean {
        if (importing || downloading) {
            emit(onProgress, -1f, "已有下载或导入任务在进行中，请稍候")
            return false
        }
        importing = true
        val staging = File(tmpDir(context), "import_staging")
        try {
            emit(onProgress, -1f, "准备导入…")
            staging.deleteRecursively()
            if (!staging.mkdirs()) {
                emit(onProgress, -1f, "无法创建临时目录，请检查存储空间")
                return false
            }
            val available = freeBytes(context.getExternalFilesDir(null) ?: context.filesDir)
            if (available in 0 until 300_000_000L) {
                emit(onProgress, -1f, "存储空间不足：至少还需 300MB，当前可用 ${available / 1024 / 1024}MB")
                return false
            }

            var lastAt = 0L
            val report = { msg: String ->
                val now = System.currentTimeMillis()
                if (now - lastAt >= 600) {
                    lastAt = now
                    emit(onProgress, -1f, msg)
                }
            }
            val count = extract(staging, report)
            Log.i(TAG, "导入暂存完成：$count 个文件")
            if (count <= 0) {
                emit(onProgress, -1f, "导入失败：所选内容里没有文件")
                return false
            }
            return classifyAndInstall(context, staging, onProgress)
        } catch (e: Throwable) {
            Log.e(TAG, "导入失败", e)
            emit(onProgress, -1f, "导入失败：${e.message ?: e.javaClass.simpleName}")
            return false
        } finally {
            staging.deleteRecursively()
            importing = false
        }
    }

    /** 把暂存目录里的文件按名字归类搬进 models/ 与 sensevoice/，并校验完整性 */
    private fun classifyAndInstall(
        context: Context,
        staging: File,
        onProgress: (Float, String) -> Unit
    ): Boolean {
        emit(onProgress, -1f, "整理文件…")
        val all = staging.walkTopDown().filter { it.isFile }.toList()
        if (all.isEmpty()) {
            emit(onProgress, -1f, "导入失败：包里没有文件")
            return false
        }
        val hasVision = all.any { it.name in VISION_FILES }
        val hasVoice = all.any { it.name in SENSEVOICE_FILES }
        if (!hasVision && !hasVoice) {
            emit(
                onProgress, -1f,
                "这不是模型包：没找到 llm.mnn / visual.mnn / model.int8.onnx 等文件"
            )
            return false
        }

        val vision = visionDir(context).apply { mkdirs() }
        val voice = senseVoiceDir(context).apply { mkdirs() }
        val both = hasVision && hasVoice

        for (f in all) {
            val rel = f.relativeTo(staging).path.replace('\\', '/')
            val toVoice = when {
                f.name in SENSEVOICE_FILES -> true
                f.name in VISION_FILES -> false
                // 未知文件（tokens.txt 之外的附属文件）：双包按目录前缀判断
                both -> rel.startsWith("$SENSEVOICE_DIR/") || rel.contains("/$SENSEVOICE_DIR/")
                else -> hasVoice && !hasVision
            }
            val dest = File(if (toVoice) voice else vision, f.name)
            dest.parentFile?.mkdirs()
            if (dest.exists()) dest.delete()
            if (!f.renameTo(dest)) {
                f.copyTo(dest, overwrite = true)
                f.delete()
            }
        }

        val vReady = isVisionModelReady(context)
        val sReady = isSenseVoiceReady(context)
        val missing = ArrayList<String>()
        if (hasVision && !vReady) {
            VISION_FILES.filterNot { File(vision, it).exists() }.forEach { missing += "models/$it" }
        }
        if (hasVoice && !sReady) {
            SENSEVOICE_FILES.filterNot { File(voice, it).exists() }.forEach { missing += "sensevoice/$it" }
        }

        val summary = buildString {
            append("视觉模型：")
            append(if (vReady) "● 已就绪" else "○ 未就绪")
            append("；语音模型：")
            append(if (sReady) "● 已就绪" else "○ 未就绪")
        }
        return if (missing.isEmpty()) {
            Log.i(TAG, "导入成功：$summary")
            emit(onProgress, 1f, "导入完成 · $summary")
            true
        } else {
            val msg = "导入不完整，缺少：${missing.joinToString("、")}"
            Log.e(TAG, msg)
            emit(onProgress, -1f, msg)
            false
        }
    }

    /** 递归把 SAF 目录树里的文件复制到暂存目录 */
    private fun copyDocTree(
        context: Context,
        dirUri: Uri,
        outDir: File,
        onChunk: (Long) -> Unit,
        counter: IntArray
    ) {
        val resolver = context.contentResolver
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            dirUri, DocumentsContract.getDocumentId(dirUri)
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        resolver.query(childrenUri, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val mime = c.getString(2)
                val childUri = DocumentsContract.buildDocumentUriUsingTree(dirUri, id)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    val sub = File(outDir, name).apply { mkdirs() }
                    copyDocTree(context, childUri, sub, onChunk, counter)
                } else {
                    if (name.endsWith(".DS_Store")) continue
                    resolver.openInputStream(childUri)?.use { input ->
                        copyStream(input, File(outDir, name), onChunk)
                    }
                    counter[0]++
                }
            }
        } ?: Log.w(TAG, "无法列出目录：$dirUri")
    }

    /** 流复制（256KB 缓冲），逐块回调字节数用于进度显示 */
    private fun copyStream(input: java.io.InputStream, out: File, onChunk: (Long) -> Unit) {
        out.parentFile?.mkdirs()
        FileOutputStream(out).use { fos ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                fos.write(buf, 0, n)
                onChunk(n.toLong())
            }
        }
    }

    /** 解压 zip 到目标目录（zip 根目录文件直接落 targetDir） */
    private fun extractZip(zip: File, targetDir: File) {
        ZipInputStream(zip.inputStream()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.isDirectory) continue
                val outFile = File(targetDir, entry.name)
                outFile.parentFile?.mkdirs()
                FileOutputStream(outFile).use { out ->
                    zis.copyTo(out)
                }
                zis.closeEntry()
            }
        }
    }

    // ═══════════════ 模型中心：选方案 → 产出 → 安装（0.8.13-alpha7） ═══════════════
    //
    // 以前点一下按钮就直接往 models/ 里覆盖，中途失败会留下半新半旧的混合包 ——
    // 那是最难排查的状态（真机上「混装」就是这么来的，崩了还找不到原因）。
    //
    // 现在分两段：
    //   ① 产出：只写暂存区 `.staging/<方案>/`，正式目录一个字节不动，失败可以重来；
    //   ② 安装：先备份旧的 → 再逐个换 → 校验 → 失败立刻回滚。
    // 任何时刻 models/ 里都是一个**完整**的包，不会再出现混装。

    /**
     * 可选的模型方案。
     *
     * @param webFiles 魔搭逐文件方案的「源文件名 to 本地文件名」；null = 走 GitHub 的 models.zip
     */
    enum class ModelPlan(
        val id: String,
        val title: String,
        val summary: String,
        /** 大致体积（UI 展示 + 空间预检） */
        val bytes: Long,
        val webFiles: List<Pair<String, String>>?
    ) {
        MODELSCOPE_FULL(
            id = "ms_full",
            title = "魔搭官方整包",
            summary = "9 个文件全部换成与 3.0.3 引擎配套的版本，一劳永逸",
            bytes = MODELSCOPE_BYTES,
            webFiles = MODELSCOPE_FILES
        ),
        MODELSCOPE_EMBEDDING(
            id = "ms_embed",
            title = "只补 embedding 文件",
            summary = "补 embeddings_bf16.bin（466MB），修「fseeko: null FILE*」那类崩溃",
            bytes = MODELSCOPE_EMBEDDING_BYTES,
            webFiles = MODELSCOPE_EMBEDDING_FILES
        ),
        VISUAL_ONLY(
            id = "ms_visual",
            title = "只补视觉编码器",
            summary = "只换 visual.mnn + visual.mnn.weight，llm 权重不动（最小改动）",
            bytes = MODELSCOPE_VISUAL_BYTES,
            webFiles = MODELSCOPE_VISUAL_FILES
        ),
        LUNAR_FULL(
            id = "lunar_full",
            title = "LunaMacro 官方包",
            summary = "GitHub Release 的 models.zip，回退或首次安装用，国内较慢",
            bytes = 1_300_000_000L,
            webFiles = null
        )
    }

    /** 暂存目录：产出落这里，不碰正式目录 */
    fun stagingDir(context: Context, plan: ModelPlan): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, ".staging/" + plan.id).apply { mkdirs() }
    }

    /** 暂存就绪标记（内容：每行「文件名\t字节数」） */
    private fun manifestOf(dir: File) = File(dir, ".ready")

    /** 暂存区状态 */
    sealed class Staged {
        /** 还没产出（partialBytes > 0 表示下到一半，可续传） */
        data class Empty(val partialBytes: Long = 0L) : Staged()

        /** 已产出，等安装 */
        data class Ready(val bytes: Long, val at: Long, val fileCount: Int) : Staged()
    }

    /**
     * 读暂存区状态。
     *
     * 有 `.ready` 标记且清单里每个文件都还在、大小没缩水，才算「已产出」——
     * 清单对不上（被系统清理、被截断）就当没产出，避免装一个残缺的包。
     */
    fun stagedState(context: Context, plan: ModelPlan): Staged {
        val dir = stagingDir(context, plan)
        val marker = manifestOf(dir)
        if (marker.isFile) {
            val entries = runCatching { marker.readLines(Charsets.UTF_8) }
                .getOrDefault(emptyList())
                .filter { it.isNotBlank() }
            val ok = entries.isNotEmpty() && entries.all { line ->
                val parts = line.split('\t')
                if (parts.size < 2) return@all false
                val f = File(dir, parts[0])
                f.isFile && f.length() >= (parts[1].toLongOrNull() ?: -1L)
            }
            if (ok) {
                val total = entries.sumOf { it.split('\t').getOrNull(1)?.toLongOrNull() ?: 0L }
                return Staged.Ready(total, marker.lastModified(), entries.size)
            }
            runCatching { marker.delete() }
        }
        val partial = runCatching {
            dir.listFiles()?.sumOf { it.length() } ?: 0L
        }.getOrDefault(0L)
        return Staged.Empty(partial)
    }

    /**
     * ① 产出：把方案的文件下载/解压到暂存区。
     *
     * **不碰正式目录**。失败随便重试，最多多占点暂存空间。
     */
    suspend fun producePlan(
        context: Context,
        plan: ModelPlan,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Boolean = withContext(Dispatchers.IO) {
        if (downloading) return@withContext false
        downloading = true
        try {
            val dir = stagingDir(context, plan).apply { mkdirs() }
            // 先删旧清单：中途失败绝不能被当成「已产出」
            manifestOf(dir).delete()

            val base = context.getExternalFilesDir(null) ?: context.filesDir
            val available = freeBytes(base)
            if (available in 0 until plan.bytes) {
                emit(
                    onProgress, -1f,
                    "空间不足：${plan.title} 约 ${plan.bytes / 1_000_000}MB，" +
                        "当前可用 ${available / 1_000_000}MB"
                )
                return@withContext false
            }

            val ok = if (plan.webFiles != null) {
                downloadFilesInto(plan.webFiles, dir, onProgress)
            } else {
                downloadAndExtract(context, VISION_ZIP, dir, onProgress)
            }
            if (!ok) return@withContext false

            // 魔搭官方 config.json 没写 tokenizer_file，这里补上
            File(dir, "config.json").takeIf { it.isFile }?.let { patchTokenizerFile(it) }

            val list = dir.listFiles()
                ?.filter { it.isFile && it.name != ".ready" && !it.name.endsWith(".part") }
                .orEmpty()
            if (list.isEmpty()) {
                emit(onProgress, -1f, "产出目录是空的，下载可能没成功")
                return@withContext false
            }
            manifestOf(dir).writeText(
                list.joinToString("\n") { it.name + "\t" + it.length() } + "\n",
                Charsets.UTF_8
            )
            emit(
                onProgress, 1f,
                "已产出：${plan.title}，${list.size} 个文件 / ${list.sumOf { it.length() } / 1_000_000}MB"
            )
            Log.i(TAG, "产出完成 ${plan.id}: " + list.joinToString { it.name + "=" + it.length() })
            true
        } catch (e: Throwable) {
            Log.e(TAG, "产出失败 ${plan.id}", e)
            emit(onProgress, -1f, "产出失败：${e.message ?: e.javaClass.simpleName}")
            false
        } finally {
            downloading = false
        }
    }

    /** 逐文件下载到指定目录（魔搭源），支持断点续传 */
    private fun downloadFilesInto(
        files: List<Pair<String, String>>,
        target: File,
        onProgress: (Float, String) -> Unit
    ): Boolean {
        val total = files.size
        for ((index, pair) in files.withIndex()) {
            val (remote, local) = pair
            val dst = File(target, local)
            val tmp = File(target, local + ".part")
            val weight = index.toFloat() / total
            emit(onProgress, weight, "魔搭 $remote（${index + 1}/$total）")
            val ok = downloadOne(MODELSCOPE_BASE + "/" + remote, tmp, "魔搭") { p, st ->
                onProgress(weight + p / total, remote + "：" + st)
            }
            if (!ok) {
                emit(onProgress, -1f, "下载失败：$remote（已下部分保留，可续传）")
                return false
            }
            if (dst.exists()) dst.delete()
            if (!tmp.renameTo(dst)) {
                tmp.copyTo(dst, overwrite = true)
                tmp.delete()
            }
            emit(onProgress, (index + 1f) / total, "完成 $local")
        }
        return true
    }

    /** 安装结果 */
    sealed class InstallResult {
        data class Ok(val replaced: Int, val backupName: String?) : InstallResult()
        data class Failed(val reason: String) : InstallResult()
    }

    /**
     * ② 安装：把暂存区的文件装进正式目录。
     *
     * 顺序：备份旧的 → 逐个替换（先 .installing 再 rename）→ 校验 7 个文件 → 失败回滚。
     * 保证任何时刻 models/ 里都是完整的一套。
     */
    suspend fun installStaged(
        context: Context,
        plan: ModelPlan,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): InstallResult = withContext(Dispatchers.IO) {
        val staged = stagedState(context, plan)
        if (staged !is Staged.Ready) {
            return@withContext InstallResult.Failed("暂存区还没有可安装的文件，先点「下载到暂存区」")
        }
        val src = stagingDir(context, plan)
        val dst = visionDir(context).apply { mkdirs() }
        val incoming = src.listFiles()
            ?.filter { it.isFile && it.name != ".ready" && !it.name.endsWith(".part") }
            .orEmpty()
        if (incoming.isEmpty()) {
            return@withContext InstallResult.Failed("暂存目录是空的，需要重新下载")
        }

        // ---- ① 先备份将被覆盖的旧文件 ----
        val stamp = SimpleDateFormat("MMdd_HHmmss", Locale.US).format(Date())
        val backup = File(dst, ".backup_" + stamp)
        val toBackup = incoming.map { File(dst, it.name) }.filter { it.isFile }
        var backupName: String? = null
        try {
            if (toBackup.isNotEmpty()) {
                backup.mkdirs()
                toBackup.forEach { f -> f.copyTo(File(backup, f.name), overwrite = true) }
                backupName = backup.name
                Log.i(TAG, "已备份 " + toBackup.size + " 个旧文件 → " + backup.absolutePath)
            }
            emit(onProgress, 0.1f, "已备份 ${toBackup.size} 个旧文件")
        } catch (e: Throwable) {
            Log.e(TAG, "备份失败，中止安装", e)
            runCatching { backup.deleteRecursively() }
            return@withContext InstallResult.Failed("备份旧文件失败，已中止（正式目录没动）：${e.message}")
        }

        // ---- ② 逐个替换 ----
        val total = incoming.size
        try {
            for ((i, f) in incoming.withIndex()) {
                val target = File(dst, f.name)
                val mid = File(dst, f.name + ".installing")
                f.copyTo(mid, overwrite = true)
                if (target.exists()) target.delete()
                if (!mid.renameTo(target)) {
                    mid.copyTo(target, overwrite = true)
                    mid.delete()
                }
                emit(onProgress, 0.1f + 0.85f * (i + 1) / total, "安装 ${f.name}（${i + 1}/$total）")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "安装失败，开始回滚", e)
            backupName?.let { runCatching { restoreFromBackup(dst, it) } }
            return@withContext InstallResult.Failed("安装中断，已回滚到原状态：${e.message}")
        }

        // ---- ③ 校验 ----
        val missing = VISION_FILES.filter {
            val f = File(dst, it)
            !f.isFile || f.length() == 0L
        }.toMutableList()
        // 魔搭包是「独立 embedding 文件」模式（llm_config.json 没有 tie_embeddings）：
        // 少了 embeddings_bf16.bin 装载照样成功，一推理就 fseeko(null) 崩，
        // 所以在安装这一步就把它拦下来，而不是等用户点测试才崩。
        if (embeddingMode(context) == EmbeddingMode.SEPARATE_MISSING) {
            missing.add("embeddings_bf16.bin")
        }
        if (missing.isNotEmpty()) {
            Log.e(TAG, "安装后校验失败，缺 $missing")
            backupName?.let { runCatching { restoreFromBackup(dst, it) } }
            return@withContext InstallResult.Failed(
                "装完还缺 ${missing.joinToString()}，已回滚。" +
                    if (missing.contains("embeddings_bf16.bin")) {
                        "缺 embedding 文件的话，选「只补 embedding 文件」补上再装。"
                    } else ""
            )
        }

        // ---- ④ 成功：清暂存、备份只留最近一份 ----
        runCatching { src.deleteRecursively() }
        pruneBackups(dst, keep = 1)
        emit(onProgress, 1f, "安装完成（${incoming.size} 个文件），去点「测试视觉模型」")
        Log.i(TAG, "安装完成 ${plan.id}: ${incoming.size} 个文件，备份=$backupName")
        InstallResult.Ok(replaced = incoming.size, backupName = backupName)
    }

    /** 正式目录里现有的备份（新→旧） */
    private fun backups(dst: File): List<File> =
        dst.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith(".backup_") }
            ?.sortedByDescending { it.name }
            .orEmpty()

    /** 最近一次安装的备份名（没有则 null） */
    fun lastBackup(context: Context): String? = backups(visionDir(context)).firstOrNull()?.name

    /**
     * 回滚到最近一份备份。
     *
     * 用途：换完模型发现更糟（比如从「崩在分词」变成「崩在更前面」），一键退回去。
     */
    suspend fun rollbackLastInstall(context: Context): Boolean = withContext(Dispatchers.IO) {
        val dst = visionDir(context)
        val name = lastBackup(context) ?: return@withContext false
        restoreFromBackup(dst, name)
        pruneBackups(dst, keep = 0)
        Log.w(TAG, "已回滚到备份 $name，并清掉该备份")
        true
    }

    private fun restoreFromBackup(dst: File, backupName: String) {
        val backup = File(dst, backupName)
        if (!backup.isDirectory) return
        backup.listFiles()?.forEach { f ->
            if (!f.isFile) return@forEach
            runCatching { f.copyTo(File(dst, f.name), overwrite = true) }
                .onFailure { Log.e(TAG, "回滚 ${f.name} 失败", it) }
        }
        Log.w(TAG, "已从 $backupName 回滚 ${backup.listFiles()?.size ?: 0} 个文件")
    }

    private fun pruneBackups(dst: File, keep: Int) {
        backups(dst).drop(keep).forEach { d ->
            runCatching { d.deleteRecursively() }
                .onSuccess { Log.i(TAG, "清理旧备份 ${d.name}") }
        }
    }
}
