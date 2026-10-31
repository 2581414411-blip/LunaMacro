package com.nexus.agent.copilot

import android.util.Log
import com.nexus.agent.game.LocalScreenRecognizer

/**
 * 视觉路由 —— 「这一轮到底是谁在看屏幕」的唯一记账处。
 *
 * ## 为什么需要这个类
 *
 * 0.8.1 修好 so 之后，真机仍然表现成「AI 没看屏幕就瞎聊」。翻代码时发现
 * [PersonaEngine.respond]（双模型架构里的角色 LLM 阶段）**一次都没调用过**
 * [LocalScreenRecognizer] —— 它只认云端，云端模型不支持图片就直接纯文本，
 * 本地视觉模型全程被跳过。这属于「静默降级」：没有任何日志能证明它偷懒了。
 *
 * 所以把「选择哪条通道」这件事抽成一处：
 *  - 所有路径都走 [log] 打同一行格式，grep `[路由]` 就能看出每轮走了哪条腿；
 *  - [Choice] 三个取值对应三种真实后果（本地看图 / 云端看图 / 纯文本瞎聊），
 *    日志里出现 `CloudText` 就说明这一轮**没人看屏幕**，不需要再猜。
 *
 * ## 日志格式（0.8.2 起固定，改动前先想清楚排障脚本会不会断）
 *
 * ```
 * [路由] 本地模型状态=READY, 截图存在=true, 最终选择=Local
 * [路由] 本地模型状态=NOT_DOWNLOADED, 截图存在=false, 最终选择=CloudText
 * [路由] 截图失败，降级纯文本
 * [路由] 本地模型未就绪（FAILED），降级云端 —— 原因=native 库加载失败：...
 * ```
 *
 * 状态取值严格限定 READY / LOADING / NOT_DOWNLOADED / FAILED 四个，
 * 和 [LocalScreenRecognizer.State] 一一对应 —— 用户报障时只要念这一行就能定位。
 */
object VisionRoute {

    /** 和 [CopilotVision] 用同一个 TAG，logcat 过滤 `CopilotVision` 能一网打尽 */
    const val TAG = "CopilotVision"

    /** 这一轮最终选了哪条通道 */
    enum class Choice(val label: String) {
        /** 本地 Qwen2-VL 看懂了图（或至少在推理） */
        LOCAL("Local"),

        /** 云端多模态模型看图 */
        CLOUD_VISION("CloudVision"),

        /** 纯文本 —— **这一轮没人看屏幕**，日志里最该被盯着的取值 */
        CLOUD_TEXT("CloudText")
    }

    /**
     * 把截图翻成一句话描述的提示词。
     *
     * 本地 2B 模型不像云端那样能一边看图一边演人设，所以分两步：
     * 先让它老老实实描述画面（本提示词），再把这段描述塞进角色 LLM 的文本上下文。
     * 这样人格语气由大模型负责，看图由本地模型负责，各干各擅长的事。
     */
    const val SCREEN_DESCRIBE_PROMPT =
        "请用中文描述这张手机截图里正在显示什么：这是什么应用或界面、主要内容是什么、" +
            "用户大概在做什么。只输出两三句描述，不要输出 JSON，不要列坐标，不要逐个分析按钮。"

    /** 描述结果注入提示词前的长度上限（再长会把角色 LLM 的上下文挤走） */
    const val SCREEN_DESC_MAX = 240

    /** 本地模型状态 → 日志用字符串（严格四态） */
    fun stateLabel(state: LocalScreenRecognizer.State): String = when (state) {
        LocalScreenRecognizer.State.READY -> "READY"
        LocalScreenRecognizer.State.LOADING -> "LOADING"
        LocalScreenRecognizer.State.NOT_DOWNLOADED -> "NOT_DOWNLOADED"
        LocalScreenRecognizer.State.FAILED -> "FAILED"
    }

    /**
     * 拼一行路由日志（不打印）。
     *
     * 纯字符串入参是刻意的：单测里不需要初始化 [LocalScreenRecognizer] 就能验格式，
     * 而格式一旦漂了，用户报障时念的那句话就对不上日志了。
     */
    internal fun routeLine(state: String, hasShot: Boolean, choice: String): String =
        "[路由] 本地模型状态=$state, 截图存在=$hasShot, 最终选择=$choice"

    /**
     * 打一条完整路由日志并原样返回（调用方想顺手塞进排障报告时用）。
     *
     * @param detail 附加说明（模型名 / 降级原因），非必填
     */
    fun log(
        state: LocalScreenRecognizer.State,
        hasShot: Boolean,
        choice: Choice,
        detail: String? = null
    ): String {
        val line = routeLine(stateLabel(state), hasShot, choice.label)
        val full = if (detail.isNullOrBlank()) line else "$line ｜ $detail"
        Log.i(TAG, full)
        return full
    }

    /** 截图失败：这一轮注定纯文本，先把原因摆出来 */
    fun logShotFailed(reason: String? = null): String {
        val line = "[路由] 截图失败，降级纯文本"
        Log.w(TAG, if (reason.isNullOrBlank()) line else "$line —— 原因=$reason")
        return line
    }

    /**
     * 本地模型没就绪。
     *
     * 带原因很关键：`NOT_DOWNLOADED`（去下载模型）和 `FAILED`（native 挂了，去导出日志）
     * 是两种完全不同的修法，糊成一句「未就绪」用户只能干瞪眼。
     */
    fun logLocalNotReady(state: LocalScreenRecognizer.State, reason: String? = null): String {
        val line = "[路由] 本地模型未就绪（${stateLabel(state)}），降级"
        Log.w(TAG, if (reason.isNullOrBlank()) line else "$line —— 原因=$reason")
        return line
    }

    /**
     * 截干净模型输出，作为「你看到的画面」注入角色 LLM。
     *
     * 本地小模型爱加前缀（「这张图片显示的是…」）和换行，这里统一压成一段短文本。
     */
    fun cleanScreenDescription(raw: String?): String? {
        var s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        listOf(
            "这张图片显示的是", "这张图片显示", "这张截图显示的是", "这张截图显示",
            "图片内容：", "图片内容:", "描述：", "描述:", "画面描述：", "画面描述:"
        ).forEach { s = s.removePrefix(it) }
        s = s.replace(Regex("\\s+"), " ").trim()
        // 前缀剥掉后常剩下一个「：」，不收拾干净注入提示词时会出现「【你看到的画面】：xxx」
        s = s.trimStart('：', ':', '，', ',', ' ').trim('"', '\'', '“', '”', '「', '」', '：', ':')
        if (s.isEmpty()) return null
        return if (s.length > SCREEN_DESC_MAX) s.take(SCREEN_DESC_MAX) + "…" else s
    }
}
