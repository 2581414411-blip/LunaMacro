package com.nexus.agent.copilot

import android.content.Context
import android.util.Log
import com.nexus.agent.agent.LlmClient
import com.nexus.agent.agent.LlmClientFactory
import com.nexus.agent.agent.tools.ToolRegistry
import com.nexus.agent.util.MiniJson
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 探针 LLM（0.8.0 双模型架构的「前半段」）。
 *
 * ## 为什么要拆成两个模型
 *
 * 0.7.x 是**一个模型干两件事**：既要判断「这句话是要干活还是闲聊」，又要用宏月的人设说话。
 * 这两件事的提示词天然互相打架：
 *  - 判断意图需要的是**短上下文 + 强格式约束**（只许吐 JSON，不许有情绪）；
 *  - 人格化回复需要的是**长人设 + 自由表达**（越像人越好，最好别有一句 JSON）。
 *
 * 塞在一个 prompt 里，结果是两边都不稳：小模型被几千字人设一冲，JSON 契约就丢；
 * 大模型被 JSON 契约一压，说话就开始像客服。
 *
 * 所以这里只做**第一件事**：给一句用户原话，输出一个极短的 JSON。
 *  - 不注入任何人设（[PERSONA_FREE_PROMPT] 里明确写了禁止扮演）；
 *  - 不带聊天历史（意图判断只看当下这句话 + 前台应用）；
 *  - 不带截图（省一次视觉推理，也避免隐私面扩大）；
 *  - 超时极短（[TIMEOUT_MS]），探针慢就等于没有。
 *
 * 判定结果交给 [CopilotEngine]：Action/Task 走工具，再把**工具结果**丢给
 * [PersonaEngine.respond]（角色 LLM）用人格化语气说出来。
 *
 * ## 失败语义（重要）
 *
 * [classify] 返回 null 表示「探针不可用」——没配 Key、超时、输出不是合法 JSON、工具名不认识。
 * **null 不是错误，是降级信号**：调用方会退回 0.7.x 的单模型路径，
 * 保证「探针挂了但功能还在」。绝对不要在探针失败时直接给用户报错。
 */
object ProbeLLM {

    private const val TAG = "ProbeLLM"

    /** 探针超时：它只是一个分类器，8 秒还没回来说明这条路今天不通 */
    private const val TIMEOUT_MS = 8_000L

    /** 用户原话最长截断（探针不需要长文，超长的多半是粘贴了一篇文章） */
    private const val MAX_INPUT_CHARS = 300

    /** 便宜的优先（和长期记忆抽取保持同一套选型策略） */
    private const val PREFERRED_PROVIDER = "deepseek"

    /** 同一时刻只跑一个探针，避免连点时并发打接口 */
    private val busy = AtomicBoolean(false)

    /**
     * 探针判定结果。
     *
     * 刻意做成 sealed class 而不是复用 [CopilotIntent]：
     * 探针**不产生回复文案**（那是角色 LLM 的活），复用会逼着它编一句 reply，
     * 反而把「只做分类」这件事又污染了。
     */
    sealed class Verdict {
        /** 闲聊 / 提问 / 吐槽：不需要动手机 */
        data object Chat : Verdict()

        /** 单步工具调用 */
        data class Action(val tool: String, val params: Map<String, Any?>) : Verdict()

        /** 多步任务，交给 AgentRunner */
        data class Task(val goal: String) : Verdict()
    }

    /**
     * 判断用户这句话要干什么。
     *
     * @return 判定结果；null = 探针不可用（没 Key / 超时 / 输出非法），调用方应降级
     */
    suspend fun classify(
        context: Context,
        userText: String,
        foregroundApp: String?
    ): Verdict? {
        val text = userText.trim().take(MAX_INPUT_CHARS)
        if (text.isEmpty()) return null
        if (!busy.compareAndSet(false, true)) {
            Log.d(TAG, "上一次探针还没结束，本轮降级到单模型路径")
            return null
        }
        try {
            val provider = pickProvider(context)
            val (key, _, model) = LlmClientFactory.getConfig(context, provider)
            if (key.isBlank() && provider != "ollama") {
                Log.i(TAG, "未配置 $provider 的 Key，跳过探针")
                return null
            }
            val client = LlmClientFactory.create(provider, context)
            val raw = withTimeoutOrNull(TIMEOUT_MS) {
                client.chat(
                    listOf(
                        LlmClient.Message("system", buildPrompt(foregroundApp)),
                        LlmClient.Message("user", text)
                    )
                )
            }
            if (raw.isNullOrBlank()) {
                Log.i(TAG, "探针超时或无返回（provider=$provider model=$model）")
                return null
            }
            val verdict = parse(raw, ToolRegistry.names())
            if (verdict == null) {
                Log.i(TAG, "探针输出无法解析，降级：${raw.take(80)}")
            } else {
                Log.i(TAG, "探针判定：$verdict")
            }
            return verdict
        } catch (e: Throwable) {
            // 探针是增强路径，任何异常都必须静默降级，不能打断用户这一句话
            Log.w(TAG, "探针失败：${e.javaClass.simpleName} ${e.message}")
            return null
        } finally {
            busy.set(false)
        }
    }

    /**
     * 探针提示词。
     *
     * 三件事：**只输出 JSON**、**不扮演人格**、**给出可选工具名**。
     * 工具清单用 `ToolRegistry.briefList()`（一行一个）而不是完整 Schema：
     * 探针只需要知道「有哪些工具」，参数细节由工具层的默认值兜底，
     * 完整 Schema 会把提示词撑到几千字，拖慢且更容易跑偏。
     *
     * @param toolBrief 工具清单文本。做成参数是为了可测：`ToolRegistry` 的静态初始化
     *                  会实例化所有工具（碰到 Android 环境），纯 JVM 单测里会炸，
     *                  所以单测注入一份假清单。
     */
    internal fun buildPrompt(
        foregroundApp: String?,
        toolBrief: String = ToolRegistry.briefList()
    ): String = buildString {
        append("你是一个意图分类器，不是聊天机器人。\n")
        append("禁止任何人格扮演、禁止寒暄、禁止解释。只输出一个 JSON 对象。\n\n")
        append("三种输出，格式必须完全一致：\n")
        append("1) 用户只是聊天、提问、吐槽、要建议 → {\"type\":\"chat\"}\n")
        append("2) 需要操作手机，一步就能完成 → {\"type\":\"action\",\"tool\":\"工具名\",\"params\":{...}}\n")
        append("3) 需要连续多步才能完成（跨界面、要判断中间结果） → {\"type\":\"task\",\"goal\":\"一句话说清目标\"}\n\n")
        append("判断原则：\n")
        append("- 「打开微信」「返回」「往下翻」这类明确动作 → action\n")
        append("- 「帮我把设置里那个开发者选项打开」这类要走好几步的 → task\n")
        append("- 「你在吗」「今天好累」「这个功能怎么用」→ chat\n")
        append("- 拿不准时一律输出 chat：误判成 action 会替用户乱点屏幕，代价远大于少干一次活。\n\n")
        if (!foregroundApp.isNullOrBlank()) {
            append("当前前台应用：$foregroundApp\n")
        }
        append("可用工具（名字：用途）：\n")
        append(toolBrief)
        append("\n\n只输出 JSON，不要 markdown 代码块，不要任何前后缀文字。")
    }

    /**
     * 解析探针输出。
     *
     * 容忍模型加 ```json 围栏、前后废话（[MiniJson.parseObject] 会自己找第一个对象）。
     * 工具名必须在 [knownTools] 里，否则返回 null —— 让调用方降级，
     * 也好过执行一个「模型想象出来的工具」。
     */
    internal fun parse(raw: String, knownTools: Set<String>): Verdict? {
        val obj = MiniJson.parseObject(raw) ?: return null
        val type = MiniJson.str(obj, "type")?.lowercase()
            ?: MiniJson.str(obj, "intent")?.lowercase()
            ?: return null

        return when (type) {
            "chat", "reply", "talk" -> Verdict.Chat

            "action", "tool", "act" -> {
                val name = MiniJson.str(obj, "tool")
                    ?: MiniJson.str(obj, "action")
                    ?: return null
                val tool = name.lowercase().trim()
                // 工具名不认识就降级：宁可走老路径重新判断，也不要执行瞎编的工具
                if (tool !in knownTools) return null
                val params = MiniJson.obj(obj, "params")
                    ?: MiniJson.obj(obj, "args")
                    ?: MiniJson.obj(obj, "arguments")
                    ?: emptyMap()
                Verdict.Action(tool, params)
            }

            "task", "agent", "goal", "plan" -> {
                val goal = MiniJson.str(obj, "goal") ?: MiniJson.str(obj, "task")
                if (goal.isNullOrBlank()) return null
                Verdict.Task(goal.take(120))
            }

            // 模型自创类型（如 "query"）：当作闲聊，让它走角色 LLM 正常回答
            else -> Verdict.Chat
        }
    }

    private fun pickProvider(ctx: Context): String {
        val preferredKey = LlmClientFactory.getConfig(ctx, PREFERRED_PROVIDER).first
        return if (preferredKey.isNotBlank()) PREFERRED_PROVIDER else LlmClientFactory.getProvider(ctx)
    }
}
