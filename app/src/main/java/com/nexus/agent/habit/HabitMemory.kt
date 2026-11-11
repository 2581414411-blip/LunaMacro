package com.nexus.agent.habit

import android.content.Context
import android.os.SystemClock

/**
 * 习惯画像的内存缓存（0.9.11）。
 *
 * 为什么不让 [PersonaEngine.buildReplyPrompt] 直接读文件：
 * 它是同步函数，而且是在拼提示词这种「已经在赶时间」的路径上，
 * 每轮多一次磁盘 IO 不划算；更要紧的是它偶尔会在主线程被调用，
 * 那里读文件是会卡帧的。
 *
 * 所以：学习完成时写一次缓存，提示词只读缓存。
 */
object HabitMemory {

    /** 缓存多久重新读一次盘（用户可能在设置页清空了数据） */
    private const val TTL_MS = 5 * 60 * 1000L

    @Volatile
    private var brief: String? = null

    @Volatile
    private var loadedAt = 0L

    /**
     * 取「习惯画像」提示词块。
     *
     * 返回空串表示这一轮不该注入：开关关着、从没学过、或者读不到。
     * **开关关着就一定不注入** —— 用户把功能关了，她还念叨着你的作息，
     * 那比不学会吓人。
     */
    fun block(context: Context?): String {
        val ctx = context ?: return ""
        if (!com.nexus.agent.copilot.CopilotConfig.isHabitLearningEnabled(ctx)) return ""
        val text = current(ctx) ?: return ""
        if (text.isBlank()) return ""
        return HabitProfile(brief = text).promptBlock()
    }

    /** 缓存里的画像正文（不带提示词包装），UI 展示用 */
    fun current(context: Context?): String? {
        val ctx = context ?: return null
        val now = SystemClock.elapsedRealtime()
        val hit = brief
        if (hit != null && now - loadedAt < TTL_MS) return hit
        return reload(ctx)
    }

    /** 强制重读（学习完成 / 清除数据 / 开关切换后调用） */
    fun reload(context: Context?): String? {
        val ctx = context ?: return null
        val text = HabitStore.loadBrief(ctx)
        brief = text
        loadedAt = SystemClock.elapsedRealtime()
        return text
    }

    /** 清空缓存（数据被删后必须调，否则提示词里还会念叨已经删掉的习惯） */
    fun clear() {
        brief = null
        loadedAt = 0L
    }
}
