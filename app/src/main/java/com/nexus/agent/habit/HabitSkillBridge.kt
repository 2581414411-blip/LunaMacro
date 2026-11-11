package com.nexus.agent.habit

import android.util.Log
import com.nexus.agent.macro.Macro
import com.nexus.agent.macro.MacroPlayer
import com.nexus.agent.macro.MacroStep
import com.nexus.agent.macro.StepType

/**
 * 把「她学到的动作」接上真正的手（0.9.12）。
 *
 * 这一层做的事很少，但每一件都不能省：
 *  - 技能 → [Macro]：把文字步骤翻成 CLICK_TEXT，**不带任何坐标**；
 *  - 回放：stopOnFail = true，一步找不到就停；
 *  - 提示词：只注入用户已「收下」的技能，且写成「你会做」而不是「我知道你会」。
 */
object HabitSkillBridge {

    private const val TAG = "HabitSkillBridge"

    /** 每步点击后等一下界面反应。太快会因为动画没结束而点到旧界面 */
    private const val STEP_DELAY_MS = 900L

    /**
     * 技能 → 宏。
     *
     * 刻意**不**在第一步塞 OPEN_APP：如果她本来就在这个应用里（用户刚打开），
     * 再启动一次会把界面重置回首页，反而把用户当前的位置弄丢。
     * 前置启动交给 [MacroPlayer] 的 targetPackage 检查（前台不符才启动）。
     */
    fun toMacro(skill: HabitSkill): Macro {
        val steps = skill.steps.map { s ->
            MacroStep(
                type = StepType.CLICK_TEXT,
                params = mapOf(
                    "text" to s.label,
                    "index" to 0,
                    "expectPkg" to skill.pkg
                ),
                delayAfterMs = STEP_DELAY_MS
            )
        }
        return Macro(
            name = skill.name,
            description = "从你平时的操作里学到的：" + skill.describe(),
            targetPackage = skill.pkg,
            steps = steps
        )
    }

    /**
     * 执行一次。
     *
     * 调用方必须先让用户确认 —— 这个函数自己不做任何二次询问，
     * 因为它也可能被「定时任务」这类没有 UI 的路径调用。
     * 唯一的安全边界是：**没有 active 状态就调不到这里**（见 [runIfActive]）。
     */
    suspend fun run(skill: HabitSkill): MacroPlayer.PlayResult {
        val macro = toMacro(skill)
        Log.i(TAG, "开始执行「${skill.name}」，${macro.steps.size} 步")
        val r = MacroPlayer.play(macro, stopOnFail = true)
        Log.i(TAG, "执行结束：${r.summary()}")
        return r
    }

    /**
     * 只跑 active 的技能，其余状态一律拒绝 —— 防止 UI 传错对象。
     *
     * 顺带再核一次开关：开关关掉之后，已经「收下」的动作也必须立刻失去执行资格。
     * 这条不能只写在 UI 里 —— 定时任务、聊天里被调用，都不经过设置页。
     */
    suspend fun runIfActive(context: android.content.Context, skill: HabitSkill): MacroPlayer.PlayResult? {
        if (!com.nexus.agent.copilot.CopilotConfig.isHabitLearningEnabled(context)) {
            Log.w(TAG, "拒绝执行：自我学习开关已关闭")
            return null
        }
        if (!skill.isActive) {
            Log.w(TAG, "拒绝执行非 active 技能：${skill.name} (${skill.state})")
            return null
        }
        return run(skill)
    }

    /** 结果转成人话，直接进聊天气泡 */
    fun resultText(skill: HabitSkill, r: MacroPlayer.PlayResult): String = when {
        r.aborted -> "我在「${skill.app}」里做到第 ${r.completed} 步就停了 —— ${r.abortedReason}。" +
            "界面跟平时不一样，剩下的我没敢乱点。"
        r.failed > 0 -> "在「${skill.app}」里跑完了，但有 ${r.failed} 步没成功，你自己看一眼。"
        else -> "在「${skill.app}」里弄好了，${r.success} 步都走完了。"
    }

    /**
     * 写进提示词的能力自述。
     *
     * 三点是刻意的：
     *  1. 只写 active 的 —— candidate 她还没被允许做，写进提示词只会让她空口答应；
     *  2. 措辞是「你会做」，并且明确要求**不要说已经做完**；
     *  3. 动手的开关在用户手里（设置页），不在她手里。这一版刻意没给 LLM
     *     一个「自己调起来跑」的工具 —— 从「我点了按钮她才动」到「她自己开应用」，
     *     中间差的是另一套权限设计，不能顺手塞进来。
     */
    fun promptBlock(active: List<HabitSkill>): String {
        if (active.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("【你学会的几个动作（他确认过、你确实会做的）】\n")
        for (s in active.take(5)) {
            sb.append("- ").append(s.name)
            sb.append("（").append(s.describe()).append("）")
            if (s.risk == HabitTrace.RISK_CAUTION) sb.append(" ⚠ 会改动内容，做之前一定先问")
            sb.append('\n')
        }
        sb.append("- 他说「帮我弄一下」这类话时，你可以说自己会做这几件事、并说清步骤，")
        sb.append("然后让他去设置页点一下让你做 —— 你这边没有自己动手的权限。\n")
        sb.append("- 绝对不要说「已经帮你弄好了」。你只能说会做，不能说做完了。\n")
        return sb.toString()
    }
}
