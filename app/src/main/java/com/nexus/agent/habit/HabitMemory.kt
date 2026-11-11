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
    private var skills: String? = null

    @Volatile
    private var briefAt = 0L

    @Volatile
    private var skillsAt = 0L

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
        val text = current(ctx).orEmpty()
        // 画像为空不代表技能为空（可能刚清过流水、或者只学到动作还没成形像），
        // 所以这里不能提前 return，两段各自判空。
        val profilePart = if (text.isBlank()) "" else HabitProfile(brief = text).promptBlock()
        return profilePart + skillsBlock(ctx)
    }

    /**
     * 「她会做的动作」那一段（0.9.12）。
     *
     * 与画像分开取：用户可能关了画像展示却留着技能，反过来也一样。
     * 但两者共用同一个开关和同一份 TTL —— 开关关掉，两个都不注入。
     */
    fun skillsBlock(context: Context?): String {
        val ctx = context ?: return ""
        if (!com.nexus.agent.copilot.CopilotConfig.isHabitLearningEnabled(ctx)) return ""
        val now = SystemClock.elapsedRealtime()
        val hit = skills
        val text = if (hit != null && now - skillsAt < TTL_MS) hit else reloadSkills(ctx) ?: return ""
        if (text.isBlank()) return ""
        return text
    }

    /** 读 active 技能并缓存成提示词块（IO 线程调用；顺便把缓存刷新一遍） */
    fun reloadSkills(context: Context?): String? {
        val ctx = context ?: return null
        val block = try {
            HabitSkillBridge.promptBlock(HabitSkillStore.load(ctx).filter { it.isActive })
        } catch (e: Throwable) {
            ""
        }
        skills = block
        skillsAt = SystemClock.elapsedRealtime()
        return block
    }

    /** 缓存里的画像正文（不带提示词包装），UI 展示用 */
    fun current(context: Context?): String? {
        val ctx = context ?: return null
        val now = SystemClock.elapsedRealtime()
        val hit = brief
        if (hit != null && now - briefAt < TTL_MS) return hit
        return reload(ctx)
    }

    /** 强制重读（学习完成 / 清除数据 / 开关切换后调用） */
    fun reload(context: Context?): String? {
        val ctx = context ?: return null
        val text = HabitStore.loadBrief(ctx)
        brief = text
        briefAt = SystemClock.elapsedRealtime()
        return text
    }

    /** 清空缓存（数据被删后必须调，否则提示词里还会念叨已经删掉的习惯） */
    fun clear() {
        brief = null
        skills = null
        briefAt = 0L
        skillsAt = 0L
    }
}
