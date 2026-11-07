package com.nexus.agent.system

import android.content.Context
import android.util.Log
import com.nexus.agent.agent.tools.ToolRegistry
import com.nexus.agent.copilot.CopilotConfig
import com.nexus.agent.input.ShizukuManager

/**
 * 自动化能力闸门（0.9.0 批次三）。
 *
 * ## 为什么要把「能不能动手」拆出来
 *
 * 之前这个 App 是**一字型**的：能聊天 ⇒ 一定能操作手机。于是出现两种都不体面的情况：
 *  1. 用户只想把它当树洞，它却随时准备点开他的微信；
 *  2. 手机是 MIMU 这类保活地狱，Shizuku 怎么都起不来，每次请求动作都要走一遍
 *     超时 → 失败 → 报错，用户以为软件坏了。
 *
 * 现在把「动手」收成一个**单点开关**：
 *  - 关掉之后，**工具 Schema 一个字都不注入**（模型根本不知道有这些工具），
 *  - 而且**所有 action / task 在执行前被拦掉**（防止模型凭历史记忆硬吐一个动作），
 *  - 它只会用文字一步步教用户自己点。
 *
 * 双保险是刻意的：只靠提示词，模型偶尔会「猜」出工具名；只靠拦截，
 * 模型还是会浪费一次输出在 JSON 动作上。两边都做，用户看到的就是纯粹的对话。
 */
object AutomationPolicy {

    private const val TAG = "AutomationPolicy"

    /** 关掉开关后，提示词里给模型的行为约束 */
    const val PURE_CHAT_RULE =
        "【你现在没有动手能力】你只能说话，不能操作手机。用户让你做事时，" +
            "用一两句话把「他自己该怎么点」讲清楚（哪一屏、点哪里），别假装已经替他做了。"

    /** 拦截动作时兜底说的话（角色 LLM 也接不上时用） */
    const val ADVISE_FALLBACK =
        "我现在只能动嘴不能动手（自动化工具关了）。你说一下想做什么，我告诉你怎么点。"

    /** 这个 App 现在能不能操作手机 */
    fun isEnabled(context: Context): Boolean = CopilotConfig.isAutomationEnabled(context)

    fun setEnabled(context: Context, enabled: Boolean) {
        CopilotConfig.setAutomationEnabled(context, enabled)
        Log.i(TAG, "自动化工具开关 → $enabled")
    }

    /**
     * 工具 JSON Schema：关掉时返回空串。
     *
     * 返回空串而不是 null，是为了让调用方少写一个分支 ——
     * [com.nexus.agent.copilot.CopilotVision.buildFullSystemPrompt] 见到空串
     * 会自动改成「纯对话契约」。
     */
    fun toolSchema(context: Context): String =
        if (isEnabled(context)) ToolRegistry.toJsonSchema() else ""

    /** 工具一行式清单（本地小模型用）：关掉时给一句「没有工具」而不是空字符串 */
    fun briefTools(context: Context): String =
        if (isEnabled(context)) ToolRegistry.briefList() else "（无：现在只能聊天）"

    /**
     * 真要执行动作前的最后一道闸。
     *
     * 和 [isEnabled] 分开命名，是为了在代码里读起来就是「这里在拦」，
     * 而不是又一个布尔判断。
     */
    fun allowsAction(context: Context): Boolean = isEnabled(context)

    /**
     * MIMU / 和顺 机型 + Shizuku 彻底起不来 → 自动退化成「纯数字生命」。
     *
     * 「彻底」的定义：没装 Stellar、没授权、广播 + startService 都唤不醒。
     * 只要还有一条路能活，就不动用户的开关 —— 自动关掉一个可用功能比不关更讨厌。
     *
     * **必须在后台线程调用**（内部会 sleep 等唤醒结果）。
     *
     * @return true = 本次确实降级了（设置页要提示用户）
     */
    fun degradeIfTrappedOnCustomRom(context: Context): Boolean {
        if (!RomProfile.isMimu()) return false
        if (isEnabled(context).not()) return false

        val awake = runCatching { ShizukuManager.isShizukuAvailable() }.getOrDefault(false)
        if (awake) return false

        // 再试一次非 UI 唤醒：广播 + startForegroundService，不会抢前台
        val woke = runCatching { ShizukuManager.tryWakeUpShizuku(context, allowUi = false) }
            .getOrDefault(false)
        if (woke) {
            Log.i(TAG, "MIMU 机型：Shizuku 唤醒了，保留自动化能力")
            return false
        }

        setEnabled(context, false)
        Log.w(
            TAG,
            "MIMU 机型且 Shizuku 彻底无法激活 → 自动关闭自动化工具，" +
                "退化为纯数字生命（只能聊天）"
        )
        return true
    }
}
