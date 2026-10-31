package com.nexus.agent.copilot

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.nexus.agent.accessibility.NexusAccessibilityService
import com.nexus.agent.agent.AgentRunner
import com.nexus.agent.agent.ScreenCapture
import com.nexus.agent.agent.tools.ToolRegistry
import com.nexus.agent.game.LocalScreenRecognizer
import com.nexus.agent.input.ShizukuManager
import com.nexus.agent.overlay.CopilotIslandOverlay
import com.nexus.agent.safety.SafetyPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * 副驾驶引擎 —— 一个大脑，两只手。
 *
 * ## 一、感知循环（情绪陪玩）
 *
 * 省电策略（不跑就不会耗电）：
 *  1. 每 [SAMPLE_INTERVAL_MS] 抓一帧（降采样到 720p）；
 *  2. 压成 32×32 灰度图，与上一帧算 **MAD（平均绝对差）**；
 *  3. MAD < [MAD_THRESHOLD] 视为画面没大变化 → 直接丢弃，**不做推理**；
 *  4. MAD ≥ 15（爆炸 / 死亡 / 大招）才唤醒本地 Qwen2-VL 生成弹幕；
 *  5. 触发一次后强制冷却 [COOLDOWN_MS]，避免刷屏与连续推理烧电。
 *
 * ## 二、对话 / 干活（[ask]）
 *
 * 用户在灵动岛或主控台发一句话 → 抓当前屏幕截图 → 图文一起问模型
 * （本地 Qwen2-VL 优先，云端视觉兜底）→ 模型回 `{"type":"chat|action|task"}`
 * → 聊天直接显示，动作交给 [ToolRegistry] / `AgentRunner` 执行，结果弹幕回传。
 *
 * 隐私熔断：前台应用命中银行 / 支付 / 密码类关键词时立即停止采样（连截图都不抓）。
 */
object CopilotEngine {

    private const val TAG = "CopilotEngine"

    /** 采样间隔（毫秒）：约 0.8 fps，足够捕捉突变又不太耗电 */
    private const val SAMPLE_INTERVAL_MS = 1200L

    /** 截图降采样最大边长 */
    private const val CAPTURE_MAX_DIM = 720

    /** 灰度缩略图边长 */
    private const val THUMB_SIZE = 32

    /** 画面突变阈值（0~255 灰度平均绝对差） */
    private const val MAD_THRESHOLD = 15

    /** 触发后冷却时间 */
    private const val COOLDOWN_MS = 5000L

    /** 弹幕最大字数（提示词要求 20 字，这里留点余量做兜底截断） */
    private const val MAX_BUBBLE_CHARS = 30

    /** 单轮请求总超时：超过就强制释放 isBusy 并红字报错，避免"永远在忙" */
    private const val REQUEST_TIMEOUT_MS = 90_000L

    /** 本地模型首次装载时的额外容忍（1.3GB 装载可能几十秒，不能算进请求超时） */
    private const val LOCAL_LOAD_TIMEOUT_MS = 180_000L

    /** 执行步骤推送到灵动岛的最小间隔：岛上一句要逐字播完，太密就只剩闪烁 */
    private const val STEP_PUSH_MS = 1200L

    /**
     * 赛博陪玩提示词（用户指定口径：毒舌网友、不带 AI 废话）。
     *
     * 名字读用户设置（0.7.11 起可自定义），未设置时回落默认。
     */
    private fun emotionPrompt(context: Context): String {
        val name = CopilotConfig.assistantName(context)
        return "你是 $name，玩家的赛博陪玩。用不超过20个字发弹幕。" +
            "如果玩家死了/受击，幸灾乐祸（如：'哈哈，被苦力怕炸飞了吧'）。" +
            "如果秀操作，疯狂夸赞（如：'666，这波走位无敌'）。" +
            "语气要像网友，不带AI废话。"
    }

    /** 隐私熔断关键词（包名或应用名命中即停） */
    private val PRIVATE_KEYWORDS = listOf(
        "银行", "支付", "证券", "基金", "理财", "保险", "贷款", "金融", "借贷",
        "钱包", "支付宝", "财付通", "云闪付", "密码", "安全中心",
        "wallet", "pay", "bank", "password", "keychain"
    )

    @Volatile
    var isRunning: Boolean = false
        private set

    /** 最近一次跳过的原因（供 UI 提示，如「本地模型未就绪」） */
    @Volatile
    var lastSkipReason: String? = null
        private set

    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private var appContext: Context? = null
    private var lastFrame: IntArray? = null
    private var lastTriggerAt = 0L

    // ---- 大脑（对话 / 干活）状态 ----

    /** 是否有一次对话/任务正在处理中（灵动岛据此决定"点击=中断"） */
    @Volatile
    var isBusy: Boolean = false
        private set

    /** 对话/执行协程作用域（与感知循环解耦：陪玩关掉也能对话） */
    private var brainScope: CoroutineScope? = null

    /** 当前对话/任务 Job（供中断） */
    private var actionJob: Job? = null

    /** 灵动岛回调是否已接线（幂等保护） */
    @Volatile
    private var islandBound = false

    /**
     * 开始感知循环（幂等）。
     */
    fun start(context: Context) {
        if (isRunning) return
        val ctx = context.applicationContext
        appContext = ctx
        if (!CopilotConfig.isCompanionEnabled(ctx)) {
            Log.d(TAG, "情绪陪玩开关未开，不启动")
            return
        }
        isRunning = true
        lastFrame = null
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        job = s.launch {
            Log.i(TAG, "情绪陪玩引擎启动（MAD ≥ $MAD_THRESHOLD 才推理）")
            try {
                loop(ctx)
            } catch (e: Throwable) {
                Log.e(TAG, "感知循环异常退出", e)
            } finally {
                isRunning = false
                Log.i(TAG, "情绪陪玩引擎停止")
            }
        }
    }

    /**
     * 停止感知循环。
     */
    fun stop() {
        if (!isRunning && job == null) return
        isRunning = false
        job?.cancel()
        job = null
        scope = null
        lastFrame = null
        Log.i(TAG, "已请求停止情绪陪玩引擎")
    }

    // ---------------------------------------------------------------- 大脑：对话

    /**
     * 把灵动岛的输入框接给副驾驶大脑（幂等）。
     *
     * 之前灵动岛自己调 LLM 聊天（不带截图、没有工具），接线后统一走 [ask]。
     */
    fun bindIsland(context: Context) {
        val ctx = context.applicationContext
        if (appContext == null) appContext = ctx
        if (islandBound) return
        islandBound = true
        CopilotIslandOverlay.onUserMessage = { text -> ask(ctx, text) }
        CopilotIslandOverlay.isBusyProvider = { isBusy }
        CopilotIslandOverlay.onInterrupt = { interrupt() }
        Log.i(TAG, "灵动岛已接上副驾驶大脑（图文问答 + 工具执行 + 点击中断）")
    }

    /**
     * 副驾驶处理用户一句话：**看屏幕 → 想 → 说 / 做**。
     *
     * 全流程都在后台协程里跑，任何异常都转成一句人话弹幕，绝不抛给悬浮窗。
     *
     * @param context 任意 Context（内部取 applicationContext）
     * @param userText 用户原话
     * @param onReply  文字回复回调（主控台用来显示气泡；灵动岛走弹幕不依赖它）
     * @param onDone   本轮全部结束回调（含失败/被中断；语音链路靠它在结束时播报）
     */
    fun ask(
        context: Context,
        userText: String,
        onReply: ((String) -> Unit)? = null,
        onDone: (() -> Unit)? = null,
        preferCloud: Boolean = false,
        /**
         * 人格聊天模式（PersonaTab 用）。
         *
         * true 时会：带最近 6 轮历史、用放宽长度的提示词、把每轮写进聊天流。
         * false（默认）= 原来的单轮弹幕行为，灵动岛和首页指挥台完全不受影响。
         */
        chatty: Boolean = false
    ) {
        val text = userText.trim()
        if (text.isBlank()) return
        val ctx = context.applicationContext
        if (appContext == null) appContext = ctx
        bindIsland(ctx)

        if (isBusy) {
            say(ctx, "我这边还在忙，点一下灵动岛可以中断", onReply, toChat = chatty)
            return
        }

        val s = brainScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO).also { brainScope = it }
        actionJob = s.launch {
            isBusy = true
            val startedAt = SystemClock.elapsedRealtime()
            fun elapsedMs(): Long = SystemClock.elapsedRealtime() - startedAt
            var bitmap: Bitmap? = null
            try {
                val pkg = NexusAccessibilityService.currentForegroundPackage()
                val label = appLabel(ctx, pkg)
                val sensitive = isPrivateApp(ctx, pkg)

                // 敏感应用不截图（隐私熔断优先于"看得见"）
                if (!sensitive) {
                    bitmap = try {
                        ScreenCapture.captureBitmap(CAPTURE_MAX_DIM)
                    } catch (e: Throwable) {
                        Log.w(TAG, "对话截图失败：${e.message}")
                        null
                    }
                }
                if (bitmap == null && !sensitive) {
                    // 把真实原因带出去（无障碍服务没连 / 系统拒绝截图 code N / Shizuku 没连），
                    // 否则用户只看到「没截到屏幕」，既不知道原因也不知道该修哪里。
                    lastSkipReason = ScreenCapture.lastFailureReason
                        ?: "没抓到屏幕（无障碍 / Shizuku 未就绪）"
                    Log.w(TAG, "对话截图失败：$lastSkipReason")
                } else {
                    lastSkipReason = null
                }

                // ---- 0.8.0 双模型：探针判意图 → 工具执行 → 角色 LLM 人格化交付 ----
                // 返回 true 表示这一轮已经处理完（可能已经产生副作用），不要再走老路径。
                // 返回 false 只在「探针不可用 / 角色 LLM 没接上」时发生，此时必须完整退回
                // 0.7.x 的单模型路径 —— 宁可少拆一层，也不能让对话没反应。
                if (chatty && runDualBrain(ctx, text, label, bitmap, sensitive, onReply)) {
                    return@launch
                }

                // 名字取用户设置（0.7.11 可自定义），三套提示词保持同一自称
                val assistantName = CopilotConfig.assistantName(context)
                // 人格模式走 PersonaEngine（人设 + 记忆摘要），否则保持原来的精简提示词
                val systemPrompt = if (chatty) {
                    PersonaEngine.systemPrompt(ctx, label, chatty = true)
                } else {
                    CopilotVision.buildFullSystemPrompt(
                        ToolRegistry.toJsonSchema(),
                        label,
                        assistantName
                    )
                }
                val localPromptBase = CopilotVision.buildLocalPrompt(
                    ToolRegistry.briefList(),
                    text,
                    label,
                    assistantName
                )
                // 本地 2B 模型不吃 messages 数组，把历史压成文本塞进 prompt
                val localPrompt = if (chatty) {
                    val memo = PersonaEngine.historyText()
                    if (memo.isBlank()) localPromptBase
                    else "前几轮聊过：\n$memo\n\n$localPromptBase"
                } else {
                    localPromptBase
                }
                val localTextPrompt = CopilotVision.buildLocalTextPrompt(
                    ToolRegistry.briefList(),
                    text,
                    label
                )

                CopilotIslandOverlay.showStatus("思考中", CopilotIslandOverlay.StatusKind.THINKING)

                // 总超时兜底：模型慢 / 云端挂住时，isBusy 绝不能被永久占住（否则后面每条消息都只回"我还在忙"）。
                // 本地 1.3GB 模型正在装载时单独放宽，装载不算「网络慢」。
                val budgetMs = if (LocalScreenRecognizer.state == LocalScreenRecognizer.State.LOADING) {
                    LOCAL_LOAD_TIMEOUT_MS
                } else {
                    REQUEST_TIMEOUT_MS
                }
                val history = if (chatty) PersonaEngine.historyMessages() else emptyList()
                val answered = withTimeoutOrNull(budgetMs) {
                    CopilotVision.ask(
                        context = ctx,
                        systemPrompt = systemPrompt,
                        userText = text,
                        bitmap = bitmap,
                        localPrompt = localPrompt,
                        localTextPrompt = localTextPrompt,
                        preferCloud = preferCloud,
                        history = history,
                        noShotHint = if (bitmap == null) lastSkipReason else null,
                        onStage = { stage ->
                            CopilotIslandOverlay.showStatus(stage, CopilotIslandOverlay.StatusKind.THINKING)
                        }
                    )
                }
                if (answered == null) {
                    // 走到这里只会是「真超时」；用户点岛中断走的是 CancellationException，不会落到这
                    Log.i(
                        TAG,
                        "[Copilot] 收到指令=$text, 模型选择=超时, 耗时=${elapsedMs()}ms, " +
                            "状态=超时(${budgetMs}ms)"
                    )
                    say(
                        ctx,
                        "请求超时（${budgetMs / 1000} 秒），请检查网络或换个模型",
                        onReply,
                        error = true,
                        toChat = chatty
                    )
                    return@launch
                }
                var answer = answered

                // 本地模型没下、云端也没配 Key：如实说，别装懂
                if (!answer.ok) {
                    val hint = if (sensitive) {
                        "这个界面比较敏感，我就不看了；要问什么直接说"
                    } else {
                        failureHint(answer, bitmap != null)
                    }
                    Log.i(
                        TAG,
                        "[Copilot] 收到指令=$text, 模型选择=none, 耗时=${elapsedMs()}ms, " +
                            "状态=失败(${answer.error?.label ?: "未知"})"
                    )
                    say(ctx, hint, onReply, error = true, toChat = chatty)
                    return@launch
                }

                var intent = CopilotIntentParser.parse(answer.raw, text, ToolRegistry.names())

                // 本地小模型常常把「帮我打开抖音」答成闲聊：有明确动作意图就升云端再问一次
                if (intent is CopilotIntent.Chat &&
                    answer.source == "local" &&
                    looksLikeActionRequest(text)
                ) {
                    Log.i(TAG, "本地输出不像动作，升云端重试")
                    val cloud = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                        CopilotVision.ask(
                            context = ctx,
                            systemPrompt = systemPrompt,
                            userText = text,
                            bitmap = bitmap,
                            localPrompt = localPrompt,
                            localTextPrompt = localTextPrompt,
                            preferCloud = true,
                            onStage = { stage ->
                                CopilotIslandOverlay.showStatus(stage, CopilotIslandOverlay.StatusKind.THINKING)
                            }
                        )
                    }
                    if (cloud != null && cloud.source == "cloud") {
                        answer = cloud
                        intent = CopilotIntentParser.parse(cloud.raw, text, ToolRegistry.names())
                    }
                }

                Log.i(
                    TAG,
                    "[Copilot] 收到指令=$text, 模型选择=${answer.source}(${answer.modelLabel}), " +
                        "耗时=${elapsedMs()}ms, 状态=${intent::class.simpleName}, 读屏=${answer.usedVision}"
                )
                Log.d(TAG, "模型原文=${answer.raw?.take(160)}")

                when (intent) {
                    is CopilotIntent.Chat -> {
                        val reply = intent.reply + answer.notice
                        say(ctx, reply, onReply)
                        if (chatty) {
                            PersonaEngine.recordTurn(text, reply)
                            PersonaEngine.addAssistantItem(reply)
                        }
                    }
                    is CopilotIntent.Action -> {
                        val reply = intent.reply + answer.notice
                        say(ctx, reply, onReply)
                        if (chatty) PersonaEngine.addAssistantItem(reply)
                        // 动作气泡：先报「要做什么」，跑完再报「做成了没」
                        if (chatty) {
                            PersonaEngine.addActionItem(
                                "执行动作 · ${ToolRegistry.describe(intent.tool)}",
                                describeParams(intent.params)
                            )
                        }
                        val result = executeAction(ctx, intent)
                        say(ctx, result, onReply)
                        if (chatty) {
                            PersonaEngine.addActionItem(
                                "动作结果",
                                result,
                                ok = !result.startsWith("没办成") && !result.startsWith("执行失败")
                            )
                            PersonaEngine.recordTurn(text, "$reply $result")
                        }
                    }
                    is CopilotIntent.Task -> {
                        val reply = intent.reply + answer.notice
                        say(ctx, reply, onReply)
                        if (chatty) {
                            PersonaEngine.addAssistantItem(reply)
                            PersonaEngine.addActionItem("开始执行任务", intent.goal)
                        }
                        val taskResult = runAgentTask(ctx, intent.goal, onReply, chatty)
                        if (chatty && taskResult != null) {
                            PersonaEngine.addActionItem(
                                "任务结束",
                                taskResult,
                                ok = !taskResult.startsWith("已中断") && !taskResult.startsWith("执行出错")
                            )
                            PersonaEngine.recordTurn(text, "$reply $taskResult")
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.i(TAG, "对话被取消")
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "对话处理异常", e)
                Log.i(
                    TAG,
                    "[Copilot] 收到指令=$text, 模型选择=unknown, 耗时=${elapsedMs()}ms, " +
                        "状态=异常(${e.javaClass.simpleName})"
                )
                say(
                    ctx,
                    "我这边出错了：${e.message ?: e.javaClass.simpleName}",
                    onReply,
                    error = true,
                    toChat = chatty
                )
            } finally {
                if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
                isBusy = false
                actionJob = null
                CopilotIslandOverlay.endThinking()
                onDone?.invoke()
            }
        }
    }

    /**
     * 双模型主流程（0.8.0）：**探针判意图 → 工具执行 → 角色 LLM 交付结果**。
     *
     * 为什么要拆：意图判断要的是「短上下文 + 强格式」，人格化回复要的是
     * 「长人设 + 自由表达」，塞进一个 prompt 里两边都不稳（详见 [ProbeLLM]）。
     *
     * 分工：
     *  1. [ProbeLLM.classify] 用便宜模型只吐一个 JSON，不带人设、不带历史、不带截图；
     *  2. 判定为动作/任务就**先干活**（安全策略、Shizuku/无障碍前置检查全在下面这层，
     *     一行都没绕过）；
     *  3. 把**工具结果 + 召回的记忆**交给 [PersonaEngine.respond]（角色 LLM），
     *     让它用自己的语气交付，而不是照抄「工具返回：成功点击…」。
     *
     * @return true  = 这一轮已处理完（含「已经动过手」的情况，调用方别再走老路径重跑一遍）；
     *         false = 探针不可用 / 角色 LLM 没接上，调用方应退回单模型路径。
     *
     * 特别注意：动作/任务分支一旦开始执行就**必须返回 true**，哪怕角色 LLM 失败了——
     * 否则老路径会把同一个动作再执行一次（比如打开应用两次、点两次确认按钮）。
     */
    private suspend fun runDualBrain(
        ctx: Context,
        text: String,
        label: String?,
        bitmap: Bitmap?,
        sensitive: Boolean,
        onReply: ((String) -> Unit)?
    ): Boolean {
        val verdict = ProbeLLM.classify(ctx, text, label) ?: return false
        Log.i(TAG, "[DualBrain] 探针判定=$verdict")

        return when (verdict) {
            is ProbeLLM.Verdict.Chat -> {
                // 敏感界面不把截图交给角色模型：隐私熔断对两条链路一视同仁
                val shot = if (sensitive) null else bitmap
                CopilotIslandOverlay.showStatus("想一下", CopilotIslandOverlay.StatusKind.THINKING)
                val reply = PersonaEngine.respond(ctx, text, null, shot, label) ?: return false
                say(ctx, reply, onReply)
                PersonaEngine.addAssistantItem(reply)
                PersonaEngine.recordTurn(text, reply)
                true
            }

            is ProbeLLM.Verdict.Action -> {
                val des = ToolRegistry.describe(verdict.tool)
                PersonaEngine.addActionItem("执行动作 · $des", describeParams(verdict.params))
                CopilotIslandOverlay.showStatus(
                    "正在${des.take(12)}",
                    CopilotIslandOverlay.StatusKind.THINKING
                )
                val result = executeAction(
                    ctx,
                    CopilotIntent.Action(verdict.tool, verdict.params, "")
                )
                val ok = !result.startsWith("没办成") && !result.startsWith("执行失败")
                PersonaEngine.addActionItem("动作结果", result, ok = ok)

                // 角色 LLM 没接上时退回工具原话：动作已经做了，不能装作没发生
                val reply = PersonaEngine.respond(ctx, text, result, null, label)
                    ?: if (ok) result else "没办成：${result.take(60)}"
                say(ctx, reply, onReply)
                PersonaEngine.addAssistantItem(reply)
                PersonaEngine.recordTurn(text, reply)
                true
            }

            is ProbeLLM.Verdict.Task -> {
                PersonaEngine.addActionItem("开始执行任务", verdict.goal)
                CopilotIslandOverlay.showStatus("开始干活", CopilotIslandOverlay.StatusKind.THINKING)
                // announceResult=false：最终那句交给角色 LLM 说，避免「系统播报 + 人格复述」两遍
                val result = runAgentTask(
                    ctx,
                    verdict.goal,
                    onReply = null,
                    chatty = true,
                    announceResult = false
                )
                if (result.isNullOrBlank()) return true
                val ok = !result.startsWith("已中断") && !result.startsWith("执行出错")
                PersonaEngine.addActionItem("任务结束", result, ok = ok)
                val reply = PersonaEngine.respond(ctx, text, result, null, label) ?: result
                say(ctx, reply, onReply)
                PersonaEngine.addAssistantItem(reply)
                PersonaEngine.recordTurn(text, reply)
                true
            }
        }
    }

    /**
     * 中断当前对话 / 任务 / 回放。
     *
     * @return true = 确实中断了什么（false 表示本来就没在忙，点击可继续当作"提问"）
     */
    fun interrupt(): Boolean {
        val running = actionJob
        if (!isBusy || running == null) return false
        Log.i(TAG, "中断副驾驶当前任务")
        try {
            AgentRunner.stop()
        } catch (e: Throwable) {
            Log.w(TAG, "停止 Agent 失败：${e.message}")
        }
        running.cancel()
        actionJob = null
        isBusy = false
        val ctx = appContext ?: ShizukuManager.appContext()
        if (ctx != null) say(ctx, "已中断当前任务")
        return true
    }

    /**
     * 副驾驶有话要说 —— 统一出口：弹幕 / 红字错误 / 最近弹幕 / UI 回调。
     *
     * @param error true = 红底白字错误态（停留 5 秒），false = 普通逐字弹幕
     */
    private fun say(
        ctx: Context,
        text: String,
        onReply: ((String) -> Unit)? = null,
        error: Boolean = false,
        /** 人格模式：把这句话也写进聊天流（失败/超时提示不能只飘在灵动岛上） */
        toChat: Boolean = false
    ) {
        val t = text.trim()
        if (t.isEmpty()) return
        CopilotConfig.setLastBubble(ctx, t)
        try {
            if (error) {
                CopilotIslandOverlay.showStatus(t, CopilotIslandOverlay.StatusKind.ERROR)
            } else {
                CopilotIslandOverlay.speak(t)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "弹幕显示失败：${e.message}")
        }
        if (toChat) PersonaEngine.addAssistantItem(t)
        onReply?.invoke(t)
    }

    /**
     * 单步动作执行：模型给的 `tool` + `params` 直接落到 [ToolRegistry]。
     *
     * 工具内部自己做 UI 树解析 / 坐标换算，这里只负责调度与"说人话"的反馈。
     */
    private suspend fun executeAction(ctx: Context, action: CopilotIntent.Action): String {
        // 安全总闸：敏感界面（支付宝/银行）→ 危险目标应用 → 危险参数词
        val fg = NexusAccessibilityService.currentForegroundPackage()
        val block = SafetyPolicy.checkCopilotAction(ctx, action.tool, action.params, fg)
        if (block.blocked) {
            Log.w(TAG, "动作被安全策略拦截：${action.tool} → ${block.reason}")
            return block.reason
        }

        val tool = ToolRegistry.get(action.tool)
            ?: return "我还不认识「${action.tool}」这个操作"
        return try {
            val result = withContext(Dispatchers.IO) { tool.execute(action.params) }
            Log.i(TAG, "工具 ${action.tool} 返回：$result")
            humanize(action.tool, result)
        } catch (e: Throwable) {
            Log.e(TAG, "工具 ${action.tool} 执行异常", e)
            "执行失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /**
     * 多步任务：交给 [AgentRunner]（云端拆子目标 + 本地执行 + 状态机校验）。
     *
     * 执行过程中只在「子目标切换」和「最终结果」两处打扰用户，
     * 中间细节交给审计日志，避免灵动岛刷屏。
     */
    private suspend fun runAgentTask(
        ctx: Context,
        goal: String,
        onReply: ((String) -> Unit)?,
        /** 人格模式：把每一步也写进聊天流，让用户看到它在干什么 */
        chatty: Boolean = false,
        /**
         * 是否由本函数直接播报结果。
         *
         * 双模型路径（[runDualBrain]）传 false：结果要交给角色 LLM 用宏月的语气说，
         * 这里再播一遍就是「系统播报 + 人格复述」两遍，用户会以为干了两件事。
         */
        announceResult: Boolean = true
    ): String? {
        if (!ShizukuManager.isShizukuAvailable()) {
            val msg = "要动手得先把 Shizuku / Stellar 连上"
            if (announceResult) say(ctx, msg, onReply)
            return msg
        }
        if (!NexusAccessibilityService.isServiceRunning(ctx)) {
            val msg = "无障碍没开，我看不到界面，先去首页开启"
            if (announceResult) say(ctx, msg, onReply)
            return msg
        }
        // 站在支付/银行界面上，多步任务一律不代跑（无法保证不点到"确认/付款"）
        val fg = NexusAccessibilityService.currentForegroundPackage()
        if (SafetyPolicy.isSensitiveUi(ctx, fg)) {
            if (announceResult) say(ctx, SafetyPolicy.SENSITIVE_UI_MSG, onReply)
            return SafetyPolicy.SENSITIVE_UI_MSG
        }

        var summary: String? = null
        // 执行步骤上岛的节流时间戳：Executor 每一步都会回调，不节流岛根本播不完一句
        var lastStepPushAt = 0L
        try {
            AgentRunner.runTask(
                goal,
                onThinking = { line ->
                    // 0.7.11：多步任务的执行步骤（观察屏幕 / 点击 / 打开）直接推灵动岛，
                    // 用户不用点开任何页面就能看到它正在干什么。
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastStepPushAt >= STEP_PUSH_MS) {
                        lastStepPushAt = now
                        runCatching {
                            CopilotIslandOverlay.showStatus(
                                humanizeStep(line),
                                CopilotIslandOverlay.StatusKind.THINKING
                            )
                        }
                    }
                }
            ).collect { event ->
                val msg = event.message
                when {
                    msg.startsWith("任务完成：") -> summary = msg.removePrefix("任务完成：")
                    msg.startsWith("子目标 ") && msg.contains("：") -> {
                        // 形如「子目标 1/3：打开委托界面」——只在换目标时说一句
                        val step = msg.substringAfter("：").take(30)
                        say(ctx, step, null)
                        if (chatty) PersonaEngine.addActionItem("执行中", step)
                    }
                    else -> {
                        Log.d(TAG, "[${event.level}] $msg")
                        // 失败/中止这类事件不受节流限制，红底直接顶上，让用户立刻知道出事
                        if (msg.contains("❌") || msg.contains("中止")) {
                            runCatching {
                                CopilotIslandOverlay.showStatus(
                                    msg.replace("❌", "").trim().take(40),
                                    CopilotIslandOverlay.StatusKind.ERROR
                                )
                            }
                        }
                    }
                }
            }
            val finalText = summary?.takeIf { it.isNotBlank() } ?: "任务跑完了，没拿到结果"
            if (announceResult) say(ctx, finalText, onReply)
            return finalText
        } catch (e: kotlinx.coroutines.CancellationException) {
            Log.i(TAG, "任务被中断")
            if (announceResult) say(ctx, "已中断当前任务", onReply)
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "任务执行异常", e)
            val msg = "执行出错：${e.message ?: e.javaClass.simpleName}"
            if (announceResult) say(ctx, msg, onReply)
            return msg
        }
    }

    /**
     * 把 Executor 的步骤文案翻成人话再送上灵动岛。
     *
     * 原始格式是 `[3] 执行 click_text {text=确定}`，贴在岛上太技术；
     * 这里只做「去序号 + 动词汉化」，不改细节，避免把有用信息抹掉。
     */
    private fun humanizeStep(raw: String): String {
        var s = raw.trim().replace(Regex("^\\[\\d+\\]\\s*"), "")
        s = s.replace("执行 click_text", "正在点击")
            .replace("执行 click_id", "正在点击")
            .replace("执行 input_text", "正在输入")
            .replace("执行 open_app", "正在打开")
            .replace("执行 press_back", "正在返回")
            .replace("执行 press_home", "正在回桌面")
            .replace("执行 scroll", "正在滑动")
            .replace("执行 long_press", "正在长按")
        return s.take(40)
    }

    /**
     * 动作参数的可读摘要（放进「系统动作」气泡的副标题）。
     *
     * 只取前 3 个键值、每项截 24 字：参数里可能有长文本（要输入的内容），
     * 全量展示会把气泡撑爆。
     */
    private fun describeParams(params: Map<String, Any?>): String {
        if (params.isEmpty()) return ""
        return params.entries.take(3).joinToString("，") { (k, v) ->
            "$k=${v?.toString()?.take(24) ?: "null"}"
        }
    }

    /**
     * 工具返回 → 用户听得懂的一句话。
     *
     * 工具层的成功/失败文案是给 Agent 看的（"成功点击\"确定\" @ (120, 88)"），
     * 直接弹给用户太生硬，这里做一层收敛。
     */
    private fun humanize(tool: String, result: String): String {
        val r = result.trim()
        val failed = r.startsWith("错误") || r.contains("失败") || r.startsWith("未找到") ||
            r.contains("越界") || r.startsWith("无法")
        if (failed) return "没办成：${r.take(48)}"

        return when (tool) {
            "open_app" -> {
                val app = Regex("成功启动\\s*(.+?)[，,]").find(r)?.groupValues?.getOrNull(1)
                if (app != null) "已经帮你打开了「$app」" else "应用已经起来了"
            }
            "press_back" -> "回去了"
            "press_home" -> "回桌面了"
            "wait" -> "等好了"
            else -> "搞定了：${r.take(48)}"
        }
    }

    /**
     * 本地 + 云端都没接上时，如实说明卡在哪一环。
     *
     * 之前统一回一句「模型没接上：本地模型没下载、云端 Key 也没配」，
     * 把「没下载」「装载失败」「Key 没配」「401」「断网」全部糊成一句，
     * 用户根本不知道该修哪个。现在两侧原因分开说。
     */
    private fun failureHint(answer: CopilotVision.Answer, hasShot: Boolean): String {
        val localPart = when (LocalScreenRecognizer.state) {
            LocalScreenRecognizer.State.NOT_DOWNLOADED -> "本地模型没下载（设置 → 下载模型）"
            LocalScreenRecognizer.State.LOADING -> "本地模型还在加载"
            LocalScreenRecognizer.State.FAILED ->
                "本地模型加载失败：${(LocalScreenRecognizer.lastError ?: "未知原因").take(36)}"
            LocalScreenRecognizer.State.READY ->
                if (hasShot) "本地模型没答上来" else "本地模型没答上来（这轮没有截图）"
        }
        val cloudPart = when (answer.error) {
            CopilotVision.CloudError.NoKey -> "云端 Key 没配（设置 → 配置 LLM）"
            CopilotVision.CloudError.AuthError -> "云端 401 授权失败，检查 API Key"
            CopilotVision.CloudError.NetworkError -> "云端连不上（网络或代理）"
            CopilotVision.CloudError.EmptyReply -> "云端返回空内容"
            null -> null
        }
        return if (cloudPart.isNullOrBlank()) localPart else "$localPart；$cloudPart"
    }

    /** 用户话里是否有明确"要动手"的动词（用来决定要不要升云端重问一次） */
    private fun looksLikeActionRequest(text: String): Boolean {        val verbs = Regex("(打开|启动|运行|点击|点一下|按返回|返回|退出|滑动|下滑|上滑|签到|领取|帮我.{0,6}(做|弄|搞|开|点))")
        return verbs.containsMatchIn(text)
    }

    /** 包名 → 应用名（拿不到就用包名本身） */
    private fun appLabel(ctx: Context, pkg: String?): String? {
        if (pkg.isNullOrBlank()) return null
        return try {
            val info = ctx.packageManager.getApplicationInfo(pkg, 0)
            ctx.packageManager.getApplicationLabel(info).toString()
        } catch (e: Throwable) {
            pkg
        }
    }

    // ---------------------------------------------------------------- 内部实现

    private suspend fun loop(ctx: Context) {
        while (scope?.isActive == true && isRunning) {
            delay(SAMPLE_INTERVAL_MS)

            if (!CopilotConfig.isCompanionEnabled(ctx)) {
                Log.i(TAG, "开关被关闭，退出循环")
                return
            }

            // ---- 隐私熔断：命中敏感应用连截图都不做 ----
            val pkg = NexusAccessibilityService.currentForegroundPackage()
            if (isPrivateApp(ctx, pkg)) {
                lastFrame = null
                lastSkipReason = "检测到敏感应用，已暂停读屏"
                continue
            }
            if (pkg != null && pkg == ctx.packageName) {
                // 自己的界面没必要吐槽
                lastFrame = null
                continue
            }

            val bitmap = try {
                ScreenCapture.captureBitmap(CAPTURE_MAX_DIM)
            } catch (e: Throwable) {
                Log.w(TAG, "截图失败：${e.message}")
                null
            } ?: continue

            val gray = toGrayThumb(bitmap)

            val prev = lastFrame
            lastFrame = gray
            if (prev == null) {
                bitmap.recycle()
                continue
            }

            val mad = mad(prev, gray)
            if (mad < MAD_THRESHOLD) {
                bitmap.recycle()
                continue
            }

            val now = SystemClock.elapsedRealtime()
            if (now - lastTriggerAt < COOLDOWN_MS) {
                bitmap.recycle()
                Log.d(TAG, "突变 mad=${"%.1f".format(mad)} 但仍在冷却，跳过")
                continue
            }
            lastTriggerAt = now
            Log.i(TAG, "画面突变 mad=${"%.1f".format(mad)} → 触发本地推理")

            // 推理用原始 720p 彩色帧（32×32 灰度只用于差分判定）
            val bubble = try {
                generateBubble(ctx, bitmap)
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
            if (bubble.isNullOrBlank()) continue

            CopilotConfig.setLastBubble(ctx, bubble)
            withContext(Dispatchers.Main) {
                if (!CopilotIslandOverlay.isShowing()) {
                    CopilotIslandOverlay.show(ctx)
                }
                CopilotIslandOverlay.speak(bubble)
            }
        }
    }

    /**
     * 调本地 Qwen2-VL 生成一句弹幕。
     *
     * 模型没下载 / 未就绪时返回 null（不降级云端：吐槽这类高频调用走云端既慢又费钱）。
     *
     * @param frame 触发突变的那一帧原图（720p 彩色），不是 32×32 灰度缩略图
     */
    private suspend fun generateBubble(context: Context, frame: Bitmap): String? {
        val raw = LocalScreenRecognizer.infer(frame, emotionPrompt(context))
        if (raw.isNullOrBlank()) {
            lastSkipReason = "本地模型未就绪（设置 → 下载本地模型）"
            Log.w(TAG, "本地推理无输出，跳过本次吐槽")
            return null
        }
        lastSkipReason = null
        return cleanBubble(raw)
    }

    /** 清洗模型输出：去引号/换行/前缀，截断到合理长度 */
    private fun cleanBubble(raw: String): String {
        var s = raw.trim()
        // 去掉常见的模型自述前缀
        listOf("弹幕：", "弹幕:", "输出：", "输出:", "回答：", "回答:").forEach { s = s.removePrefix(it) }
        s = s.replace("\r", "").replace("\n", " ").trim()
        s = s.trim('"', '\'', '“', '”', '「', '」', '《', '》', ' ')
        if (s.length > MAX_BUBBLE_CHARS) s = s.take(MAX_BUBBLE_CHARS)
        return s
    }

    /** 降采样到 32×32 灰度（亮度加权，与肉眼观感更接近） */
    private fun toGrayThumb(src: Bitmap): IntArray {
        val small = Bitmap.createScaledBitmap(src, THUMB_SIZE, THUMB_SIZE, true)
        val pixels = IntArray(THUMB_SIZE * THUMB_SIZE)
        small.getPixels(pixels, 0, THUMB_SIZE, 0, 0, THUMB_SIZE, THUMB_SIZE)
        if (small !== src && !small.isRecycled) small.recycle()
        val out = IntArray(pixels.size)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            out[i] = (r * 299 + g * 587 + b * 114) / 1000
        }
        return out
    }

    /** 平均绝对差（MAD） */
    private fun mad(a: IntArray, b: IntArray): Double {
        if (a.size != b.size) return 255.0
        var sum = 0L
        for (i in a.indices) sum += abs(a[i] - b[i])
        return sum.toDouble() / a.size
    }

    /** 前台应用是否为银行 / 支付 / 密码类（命中即暂停读屏） */
    private fun isPrivateApp(ctx: Context, pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        val lower = pkg.lowercase()
        if (PRIVATE_KEYWORDS.any { lower.contains(it.lowercase()) }) return true
        val label = try {
            val info = ctx.packageManager.getApplicationInfo(pkg, 0)
            ctx.packageManager.getApplicationLabel(info).toString()
        } catch (e: Throwable) {
            ""
        }
        return label.isNotEmpty() && PRIVATE_KEYWORDS.any { label.contains(it) }
    }
}
