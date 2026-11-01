package com.nexus.agent.game

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 视觉子进程的「分步留痕」（0.8.13-alpha1）。
 *
 * ## 为什么还要再加一层
 *
 * [VisionStage] 只能告诉我们「死在 `native:recognize`」—— 知道是推理阶段，
 * 但推理里其实是两件事：**纯文本解码**和**视觉编码**。这两件事的修法完全不同：
 *
 * | 崩在 | 说明 | 修法 |
 * |---|---|---|
 * | 纯文本（无图）也崩 | `response()` 本身 / 文本解码 / 线程配置 | 换参数、降线程数 |
 * | 只有看图才崩 | 视觉编码的中间张量 | 降截图尺寸、降 image_size、加内存门槛 |
 *
 * 而 native 崩溃时 Java 一个字都拿不到（进程直接就没了）。所以在**每一步前后**
 * 往同一个文件里追加一行 —— 崩掉的那一步就只有 `▶` 没有 `✔/✘`，
 * 主进程据此就能说出「它死在②纯文本」。比等用户导出日志快得多。
 *
 * ## 和 VisionStage 的分工
 *
 * - [VisionStage]：跨进程的「上次崩在哪一步」，用于**阻止崩溃循环**；
 * - [VisionSteps]：人可读的分步流水，用于**排障展示**（自检弹窗 / 导出报告 / 聊天提示）。
 *
 * 两者都写 `filesDir`，两个进程共享同一份 —— 子进程写、主进程读。
 */
object VisionSteps {

    private const val TAG = "VisionSteps"

    private const val FILE_NAME = "vision_steps.txt"

    /** 最多留多少行（一次四步测试约 10 行，够存十几轮） */
    private const val MAX_LINES = 200

    /** 单文件超过这个大小就先砍一半，避免长期使用后无限膨胀 */
    private const val MAX_BYTES = 32 * 1024

    private fun file(context: Context?): File? {
        val ctx = context ?: return null
        return File(ctx.filesDir, FILE_NAME)
    }

    private fun stamp(): String =
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    /** 某一步开始（**必须在进入危险调用之前写**，否则崩了就看不到） */
    fun start(context: Context?, step: String) {
        append(context, "▶ $step")
    }

    /** 某一步正常完成 */
    fun ok(context: Context?, step: String, detail: String? = null) {
        append(context, "✔ $step" + (detail?.let { "（$it）" } ?: ""))
    }

    /** 某一步失败（进程还活着，只是没成） */
    fun fail(context: Context?, step: String, reason: String) {
        append(context, "✘ $step：$reason")
    }

    private fun append(context: Context?, line: String) {
        val f = file(context) ?: return
        runCatching {
            if (f.length() > MAX_BYTES) trim(f)
            f.appendText("[${stamp()}] $line\n", Charsets.UTF_8)
        }.onFailure { Log.w(TAG, "写分步流水失败：${it.message}") }
    }

    /** 文件太长就只留后半段 */
    private fun trim(f: File) {
        val lines = runCatching { f.readLines() }.getOrDefault(emptyList())
        runCatching { f.writeText(lines.takeLast(MAX_LINES).joinToString("\n") + "\n") }
    }

    /** 全部流水（排障报告 / 自检弹窗用） */
    fun read(context: Context?): String {
        val f = file(context) ?: return ""
        if (!f.isFile) return ""
        return runCatching { f.readText(Charsets.UTF_8).trim() }.getOrDefault("")
    }

    /**
     * 最后一行 —— 崩掉时它就是「死在哪」的唯一线索。
     *
     * 例：`[08:20:55] ▶ ②纯文本探针`（没有对应的 ✔/✘ 就是死在这一步）
     */
    fun lastLine(context: Context?): String? =
        read(context).lineSequence().lastOrNull { it.isNotBlank() }

    /** 从最后一行的 `▶ xxx` 里抠出步骤名，给聊天提示用 */
    fun stalledStep(context: Context?): String? {
        val last = lastLine(context) ?: return null
        val body = last.substringAfter("] ", "").trim()
        return body.removePrefix("▶ ").takeIf { body.startsWith("▶") && it.isNotBlank() }
    }

    /** 开新一轮测试前清空，免得上一轮的残留把人带偏 */
    fun clear(context: Context?) {
        runCatching { file(context)?.takeIf { it.exists() }?.delete() }
    }
}
