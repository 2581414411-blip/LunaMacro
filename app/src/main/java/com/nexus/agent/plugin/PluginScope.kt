package com.nexus.agent.plugin

import android.util.Log

/**
 * 插件运行的「能力作用域」—— 工具白名单的唯一裁决处（0.8.12）。
 *
 * ## 为什么需要它
 *
 * 插件是声明式的（只有 `plugin.json`），但跑起来借的是**完整的 Agent 能力**：
 * 点击、滑动、启动应用、读屏……一旦目标写歪，模型会拿着这套能力在用户手机上乱跑。
 * `PluginManifest.allowedTools` 让二创作者把「最多能干到哪」写进清单，
 * 但清单是数据，**必须有人在动手前真的拦**。那个"人"就是本对象 + [com.nexus.agent.agent.Executor]。
 *
 * ## 怎么接线
 *
 * ```
 * PluginManager.run()  →  PluginScope.begin(manifest)   // 开域
 *        ↓
 * CopilotEngine.ask()  →  AgentRunner → Executor.executeTool()
 *        ↓                                   ↓
 *        │                    PluginScope.blockReason(tool)  // 越界就拒绝
 *        ↓
 * onDone               →  PluginScope.end()             // 关域
 * ```
 *
 * ## 三个关键取舍
 *
 * 1. **null 表示不限**：老插件没写 `allowedTools`，行为必须和以前完全一致，
 *    否则升级一次全废。
 * 2. **`finish` 永远放行**：它是"我干完了"的收尾信号，不是对外动作；
 *    拦掉它会让任务卡在最后一步反复重试。
 * 3. **带 TTL**：[CopilotEngine.ask] 在忙的时候会直接返回、不回调 `onDone`，
 *    万一把「开域」留在那儿，之后所有对话都会被当成插件在跑、什么工具都用不了。
 *    所以作用域 10 分钟自动失效 —— 宁可少拦一会儿，也不能把 App 锁死。
 */
object PluginScope {

    private const val TAG = "PluginScope"

    /** 作用域最长存活时间（防止 onDone 没触发时把后续对话锁死） */
    private const val TTL_MS = 10 * 60_000L

    /** 允许的工具集合；null = 不限（老插件 / 非插件流程） */
    @Volatile
    private var allowed: Set<String>? = null

    /** 当前插件名（报错文案用） */
    @Volatile
    private var pluginLabel: String = ""

    /** 作用域失效时刻 */
    @Volatile
    private var expiresAt: Long = 0

    /** 有人正处在插件作用域里 */
    fun isActive(): Boolean {
        if (allowed == null) return false
        if (System.currentTimeMillis() > expiresAt) {
            clear()
            return false
        }
        return true
    }

    /**
     * 开域。必须在真正开始跑任务**之前**调用。
     *
     * @param manifest 插件清单（`allowedTools` 为 null 时本作用域不产生任何限制）
     */
    fun begin(manifest: PluginManifest) {
        val list = manifest.allowedTools
        pluginLabel = manifest.name
        expiresAt = System.currentTimeMillis() + TTL_MS
        allowed = list?.toSet()
        if (list == null) {
            Log.i(TAG, "插件「${manifest.name}」未声明 allowedTools，不限制工具")
        } else {
            Log.i(
                TAG,
                "插件「${manifest.name}」工具白名单生效（${list.size} 个）：" +
                    if (list.isEmpty()) "（空：禁止一切动作）" else list.joinToString("、")
            )
        }
    }

    /** 关域。幂等；必须在任务结束（含失败/中断）时调用。 */
    fun end() {
        if (allowed != null) Log.i(TAG, "插件「$pluginLabel」作用域结束")
        clear()
    }

    private fun clear() {
        allowed = null
        pluginLabel = ""
        expiresAt = 0
    }

    /**
     * 这个工具能不能用。
     *
     * @return null = 放行（没有作用域 / 在白名单内 / 是 finish）；非空 = 拒绝原因
     */
    fun blockReason(tool: String): String? {
        val a = allowed ?: return null
        if (System.currentTimeMillis() > expiresAt) {
            Log.w(TAG, "插件作用域已超时（${TTL_MS / 60_000} 分钟），自动解除")
            clear()
            return null
        }
        if (tool == "finish") return null
        if (a.contains(tool)) return null
        Log.w(TAG, "拒绝越权工具调用：$tool（插件「$pluginLabel」只允许 $a）")
        return "该插件无权使用此工具：$tool（「$pluginLabel」的工具白名单里没有它）"
    }

    /** 仅供单测 */
    internal fun resetForTest() = clear()
}
