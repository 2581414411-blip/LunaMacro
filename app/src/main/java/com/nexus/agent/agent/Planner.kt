package com.nexus.agent.agent

import android.util.Log
import com.nexus.agent.input.ShizukuManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Planner —— 任务拆解层。
 *
 * 把用户的自然语言任务拆成一组可执行的原子步骤，交给 Executor 逐步执行。
 * 与 Executor 的"单步内动作决策"解耦，避免一个大循环里上下文越滚越大、
 * token 膨胀、迷路后不会调整的问题。
 *
 * 两个入口：
 * - [plan]    首次拆解：任务 + 初始屏幕状态 → 步骤列表
 * - [replan]  失败重规划：已完成步骤 + 当前状态 → 替换剩余步骤
 *
 * 输出契约：
 * - LLM 严格返回 JSON 数组（["步骤1","步骤2",...]）
 * - 步骤粒度：每步 1~3 个原子操作能完成的事
 * - 上限 [MAX_STEPS] 步；超过则要求合并
 */
object Planner {

    private const val TAG = "Planner"

    /** 步骤上限 */
    const val MAX_STEPS = 8

    /** 重规划上限（由 AgentRunner 控制，这里仅作常量说明） */
    const val MAX_REPLANS = 2

    private val provider = "deepseek"

    /**
     * 首次规划：把任务拆成步骤列表。
     *
     * @param task         用户任务（自然语言）
     * @param initialState 首次 Observe 的精简结果（前台包名 + 可交互元素）
     * @return 步骤列表；失败返回空列表
     */
    suspend fun plan(task: String, initialState: String): List<String> {
        val prompt = buildString {
            append("你是任务规划器。把下面的任务拆成可执行的步骤列表。\n\n")
            append("## 任务\n$task\n\n")
            append("## 当前屏幕状态\n$initialState\n\n")
            append("## 要求\n")
            append("1. 每步是「1~3 个原子操作」能完成的事（如：打开设置 / 点击 WLAN / 读取 WiFi 名称）\n")
            append("2. 步骤用自然语言描述，但必须可被手机自动化执行（打开应用、点击、滑动、读取、返回等）\n")
            append("3. 最多 $MAX_STEPS 步；如果自然拆解超过 $MAX_STEPS，把相近步骤合并\n")
            append("4. 严格输出 JSON 数组，不要任何解释、前后缀、markdown 代码块\n")
            append("5. 数组元素是字符串，例如：[\"打开设置\", \"点击 WLAN\", \"读取当前连接的 WiFi 名称\"]\n")
        }
        return callAndParse(prompt, task)
    }

    /**
     * 失败重规划：基于已完成步骤和当前状态，生成替换剩余步骤的新列表。
     *
     * @param task         原始任务
     * @param completed    已完成的步骤（文字描述）
     * @param currentState 当前屏幕状态
     * @return 新的剩余步骤列表；失败返回空列表
     */
    suspend fun replan(task: String, completed: List<String>, currentState: String): List<String> {
        val doneText = if (completed.isEmpty()) "（无）" else completed.joinToString(" → ")
        val prompt = buildString {
            append("你是任务规划器。之前的执行卡住了，需要你重新规划剩余步骤。\n\n")
            append("## 原始任务\n$task\n\n")
            append("## 已完成步骤\n$doneText\n\n")
            append("## 当前屏幕状态\n$currentState\n\n")
            append("## 要求\n")
            append("1. 结合已完成进度，只输出「还没完成」的剩余步骤\n")
            append("2. 如果之前卡住是因为某步做错了，换一条可达路径\n")
            append("3. 每步 1~3 个原子操作，最多 $MAX_STEPS 步，超了要合并\n")
            append("4. 严格输出 JSON 数组，不要任何解释、前后缀、markdown 代码块\n")
            append("5. 如果任务其实已经完成，输出空数组 []\n")
        }
        return callAndParse(prompt, task)
    }

    /**
     * 调用 LLM 并解析 JSON 数组。
     */
    private suspend fun callAndParse(prompt: String, task: String): List<String> {
        val client = LlmClientFactory.create(provider, ShizukuManager.appContext())
        val messages = listOf(
            LlmClient.Message("system", buildPlannerSystemPrompt()),
            LlmClient.Message("user", prompt)
        )
        val raw = try {
            client.chat(messages)
        } catch (e: Throwable) {
            Log.e(TAG, "planner LLM failed: ${e.message}", e)
            return emptyList()
        }

        val steps = parseSteps(raw)
        Log.i(TAG, "plan result (${steps.size} steps): $steps")
        return steps
    }

    /**
     * 解析 LLM 输出为步骤列表。
     *
     * 容忍 markdown 代码块、多余文字，只要能从文本里抠出一个 JSON 数组。
     */
    private fun parseSteps(raw: String): List<String> {
        val trimmed = raw.trim()
        return try {
            val jsonStr = extractJsonArray(trimmed) ?: return emptyList()
            val arr = JSONArray(jsonStr)
            val steps = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val s = arr.optString(i).trim()
                if (s.isNotEmpty()) steps.add(s)
            }
            steps.take(MAX_STEPS)
        } catch (e: Throwable) {
            Log.e(TAG, "parseSteps failed: ${e.message}", e)
            // 兜底：按换行拆，去掉序号/符号前缀
            fallbackSplit(trimmed)
        }
    }

    /**
     * 从文本中提取第一个 JSON 数组字符串。
     */
    private fun extractJsonArray(text: String): String? {
        // 去掉 markdown 代码块围栏
        val fenced = Regex("```(?:json)?\\s*(\\[.*?\\])\\s*```", RegexOption.DOT_MATCHES_ALL)
            .find(text)?.groupValues?.getOrNull(1)
        if (fenced != null) return fenced
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        return if (start in 0 until end) text.substring(start, end + 1) else null
    }

    /**
     * 兜底：按行拆分，剥离序号与符号。
     */
    private fun fallbackSplit(text: String): List<String> {
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("```") }
            .map { line ->
                line
                    .replace(Regex("^\\s*[-*•]\\s*"), "")
                    .replace(Regex("^\\s*\\d+[\\.、\\)]\\s*"), "")
                    .trim()
            }
            .filter { it.isNotEmpty() }
            .take(MAX_STEPS)
            .toList()
    }

    /**
     * Planner 专用 system prompt。
     */
    private fun buildPlannerSystemPrompt(): String {
        return """
        你是一个手机自动化任务规划器。你的唯一职责是把任务拆成可执行步骤，
        严格输出 JSON 数组，元素为字符串步骤描述。

        规则：
        - 只输出 JSON 数组，不要任何额外文字。
        - 步骤要能被手机 Agent 执行（打开应用、点击文本、滑动、读取、返回等）。
        - 每步 1~3 个原子操作。
        - 总步数不超过 ${MAX_STEPS}，超了合并。
        - 不要规划支付、银行、密码、验证码等敏感操作。
        """.trimIndent()
    }
}
