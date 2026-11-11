package com.nexus.agent.memory

import android.content.Context
import android.util.Log
import com.nexus.agent.accessibility.NexusAccessibilityService
import com.nexus.agent.agent.Executor
import com.nexus.agent.data.db.ActionMemory
import com.nexus.agent.safety.SafetyPolicy
import kotlinx.coroutines.delay

/**
 * 肌肉记忆回放（0.9.13）。
 *
 * ## 它到底省了什么
 *
 * 一次普通的「打开微信搜订阅号」，老路是：云端拆子目标（1 次模型）→
 * 每个子目标内部逐步 Observe/Think（3~8 次模型）→ 结果摘要（1 次）。
 * 这里全跳过，只按记下来的动作序列点，全是本地调用，毫秒级。
 *
 * ## 三条不能松的规矩
 *
 * 1. **不能绕过安全闸**。回放调的 [Executor.executeToolGuarded] 与正常执行
 *    走的是同一段代码：插件白名单、自动化总闸、安全策略、审计日志一个都不少。
 *    自己 `ToolRegistry.get().execute()` 会快一点点，也会把「关掉自动化还能被记忆代跑」
 *    这个洞开出来。
 *
 * 2. **只按文字找控件**。记忆里存的是 `click_text{text=搜索}`，
 *    不是坐标。坐标会漂（换分辨率/主题/布局），按坐标回放等于随机点击。
 *    找不到就停，宁可降级也不要瞎点。
 *
 * 3. **任何一步失败立刻中止**，不继续往下点。后面的步骤都是建立在前一步
 *    生效的前提上，前一步没成，后面就是一连串误点。
 */
object MemoryReplay {

    private const val TAG = "MemDebug"

    /** 每步之间等界面反应（太快会因为动画没结束而点到旧界面） */
    private const val STEP_DELAY_MS = 700L

    data class Result(
        val ok: Boolean,
        val completed: Int,
        val total: Int,
        val reason: String
    ) {
        fun brief(): String = if (ok) {
            "$total 步全部走完"
        } else {
            "在第 $completed/$total 步停下：$reason"
        }
    }

    /**
     * 回放一条记忆。
     *
     * 失败一律返回 `ok=false` + 人话原因，由调用方决定降级 ——
     * 这里不吞异常也不重试，重试交给「大模型重新规划」那条更聪明的路。
     */
    suspend fun run(ctx: Context?, memory: ActionMemory): Result {
        val steps = ActionStep.decodeAll(memory.steps)
        val total = steps.size
        if (total == 0) return Result(false, 0, 0, "记忆里没有动作")
        if (total > ActionMemory.MAX_STEPS) {
            return Result(false, 0, total, "记忆有 $total 步，超过回放上限 ${ActionMemory.MAX_STEPS} 步")
        }

        // 站在支付/银行界面上，一律不代跑（记忆也不行）
        val fg = try {
            NexusAccessibilityService.getInstance()?.getCurrentPackageName()
        } catch (e: Throwable) {
            null
        }
        if (ctx != null && SafetyPolicy.isSensitiveUi(ctx, fg)) {
            Log.i(TAG, "[回放] 拒绝：前台是敏感支付界面")
            return Result(false, 0, total, SafetyPolicy.SENSITIVE_UI_MSG)
        }

        if (MemoryGuard.isCaution(steps)) {
            Log.i(TAG, "[回放] ⚠ 这条记忆里有会改动内容的动作：${MemoryGuard.cautionHit(steps)}")
        }
        Log.i(TAG, "[回放] 开始 id=${memory.id} 「${memory.intent.take(20)}」 $total 步，包名=${memory.pkg}")

        for ((i, step) in steps.withIndex()) {
            // 界面被切走了（用户手动退出了应用 / 来电话 / 弹窗），立刻停
            val now = try {
                NexusAccessibilityService.getInstance()?.getCurrentPackageName()
            } catch (e: Throwable) {
                null
            }
            if (memory.pkg.isNotBlank() && !now.isNullOrBlank() && now != memory.pkg) {
                val why = "界面已经从「${memory.pkg}」切到「$now」"
                Log.i(TAG, "[回放] 第 ${i + 1}/$total 步前中止：$why")
                return Result(false, i, total, why)
            }

            val result = try {
                Executor.executeToolGuarded(step.tool, toAnyArgs(step))
            } catch (e: Throwable) {
                Log.w(TAG, "[回放] 第 ${i + 1} 步异常：${e.javaClass.simpleName} ${e.message}")
                return Result(false, i, total, "动作执行异常：${e.message}")
            }
            val ok = isSuccess(result)
            Log.i(
                TAG,
                "[回放] ${i + 1}/$total ${step.tool}${step.args.entries.joinToString("") { " ${it.key}=${it.value}" }}" +
                    " → ${if (ok) "成功" else "失败（$result）"}"
            )
            if (!ok) {
                return Result(false, i + 1, total, result.take(60))
            }
            if (i < total - 1) delay(STEP_DELAY_MS)
        }

        Log.i(TAG, "[回放] 完成 id=${memory.id}，$total 步全成功")
        return Result(true, total, total, "")
    }

    /**
     * 工具结果的成败判断。
     *
     * 与 `Executor.executeTool` 内部那套前缀判断保持一致 ——
     * 两边不一致会出现「执行器认为失败、回放认为成功」这种最难查的分歧。
     */
    private fun isSuccess(result: String): Boolean =
        !(result.startsWith("失败") || result.startsWith("未知") ||
            result.startsWith("工具") || result.startsWith("未找到") || result.startsWith("错误"))

    private fun toAnyArgs(step: ActionStep): Map<String, Any?> =
        step.args.entries.associate { it.key to (it.value as Any?) }
}
