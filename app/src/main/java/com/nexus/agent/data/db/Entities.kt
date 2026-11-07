package com.nexus.agent.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 人格档案 —— 「它是谁」。
 *
 * 只有一行（[DEFAULT_ID]），升级人格/换名字就是改这一行。
 * 0.7.11 之前人设是硬编码常量，好感度和心情根本没有落地的地方；
 * 这里给它们留了正式字段，0.8.0 的情感系统直接读写这张表。
 *
 * @param name     助手名字（设置页「助手身份」改的就是它）
 * @param soul     「灵魂」：核心性格、说话方式、底线，变化很慢
 * @param identity 「身份」：和用户的关系设定、称呼、背景故事
 * @param affinity 好感度 0~100，抬升靠互动质量，下降靠被无视/被凶
 * @param mood     当前心情（一句话，如「有点困」「被夸了挺高兴」）
 * @param catchphrase     专属口癖（「喵」「哼」），空 = 没有口癖
 * @param extroversion    0 极度内向 ~ 100 极度外向
 * @param sensibility     0 极度理性 ~ 100 极度感性
 * @param rebelliousness  0 极其顺从 ~ 100 非常叛逆
 *
 * 后四个字段是 0.8.0 加的（版本 1 → 2，见 [AppDatabase] 的迁移）：
 * 之前「性格」只写在提示词常量里，用户没法调；现在变成可编辑的三维数值 + 口癖，
 * 由 [com.nexus.agent.copilot.PersonaEngine] 翻成自然语言塞进 System Prompt。
 */
@Entity(tableName = "persona_profile")
data class PersonaProfile(
    @PrimaryKey val id: Long = DEFAULT_ID,
    val name: String = "宏月",
    val soul: String = "",
    val identity: String = "",
    val affinity: Int = DEFAULT_AFFINITY,
    val mood: String = DEFAULT_MOOD,
    val catchphrase: String = "",
    val extroversion: Int = DEFAULT_TRAIT,
    val sensibility: Int = DEFAULT_TRAIT,
    val rebelliousness: Int = DEFAULT_TRAIT,
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        /** 单例档案固定 ID */
        const val DEFAULT_ID = 1L

        const val DEFAULT_AFFINITY = 50
        const val AFFINITY_MIN = 0
        const val AFFINITY_MAX = 100

        const val DEFAULT_MOOD = "平静"

        /** 性格三维的中性值 */
        const val DEFAULT_TRAIT = 50
        const val TRAIT_MIN = 0
        const val TRAIT_MAX = 100

        /** 口癖最长字数（太长了每句话都塞进去会很吵） */
        const val CATCHPHRASE_MAX = 6
    }

    /** 把好感度夹到合法区间，避免 LLM 给出 130 这种值写坏数据 */
    fun clampedAffinity(): Int = affinity.coerceIn(AFFINITY_MIN, AFFINITY_MAX)

    /** 三个性格维度统一夹取 */
    fun clampedTraits(): PersonaProfile = copy(
        extroversion = extroversion.coerceIn(TRAIT_MIN, TRAIT_MAX),
        sensibility = sensibility.coerceIn(TRAIT_MIN, TRAIT_MAX),
        rebelliousness = rebelliousness.coerceIn(TRAIT_MIN, TRAIT_MAX)
    )
}

/**
 * 对话历史 —— 一问一答的**原话**。
 *
 * 0.7.11 存在 SharedPreferences 的一坨 JSON 里，只能整存整取，
 * 「最近 10 轮」这种查询要在内存里遍历，而且一多就顶到 prefs 1MB 的上限。
 *
 * 除了 user/assistant，还存 action（工具动作气泡），
 * 这样 PersonaTab 杀掉进程重启后，聊天流能原样恢复。
 *
 * @param role      [ROLE_USER] / [ROLE_ASSISTANT] / [ROLE_ACTION]
 * @param content   用户原话 / 助手原话 / 动作标题
 * @param detail    仅 action 用：动作参数摘要
 * @param ok        仅 action 用：成功还是失败
 */
@Entity(tableName = "chat_history", indices = [Index("createdAt")])
data class ChatHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,
    val content: String,
    val detail: String = "",
    val ok: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        const val ROLE_ACTION = "action"
    }
}

/**
 * 长期记忆 —— 从对话里提取出来的**结构化事实**。
 *
 * 与 [ChatHistory] 的区别：聊天记录是「说过什么」（原始流水，越长越没用），
 * 这里是「记住了什么」（「主人喜欢猫」「他讨厌早起」）。
 *
 * @param fact       一句话事实，越短越好（塞进 prompt 用）
 * @param category   分类：preference(喜好) / habit(习惯) / relation(关系) / event(事件) / general
 * @param weight     权重 0~1，越大越优先被召回
 * @param lastHitAt  最后一次被用到的时刻（以后做遗忘曲线用）
 */
@Entity(tableName = "long_term_memory", indices = [Index("category"), Index("weight")])
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fact: String,
    val category: String = CATEGORY_GENERAL,
    val weight: Float = 1f,
    val createdAt: Long = System.currentTimeMillis(),
    val lastHitAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val CATEGORY_PREFERENCE = "preference"
        const val CATEGORY_HABIT = "habit"
        const val CATEGORY_RELATION = "relation"
        const val CATEGORY_EVENT = "event"
        const val CATEGORY_GENERAL = "general"
    }
}

/**
 * 错题流水 —— 每一次「办砸了」的原始记录（0.8.0）。
 *
 * 与 [MistakeNote] 的分工，和 [ChatHistory] 与 [MemoryEntity] 完全同构：
 *  - 这里是**流水**：每次工具报错、任务中止、模型输出解析失败都记一条，量大且会裁剪；
 *  - 那里是**提炼**：同类错误攒够次数后让 LLM 总结出的一条经验，长期保留。
 *
 * @param kind      [KIND_TOOL] 工具失败 / [KIND_TASK] 任务失败 / [KIND_PARSE] 解析失败
 * @param signature 错误指纹（同类错误归并成同一个字符串，详见 `MistakeBook.normalizeSignature`）
 * @param detail    当时的上下文（工具返回、异常信息、模型原文片段），生成教训时给 LLM 看
 */
@Entity(tableName = "mistake_log", indices = [Index("signature"), Index("createdAt")])
data class MistakeLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val signature: String,
    val detail: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val KIND_TOOL = "tool"
        const val KIND_TASK = "task"
        const val KIND_PARSE = "parse"
    }
}

/**
 * 错题本 —— 从流水里提炼出的「经验教训」（0.8.0 反思进化）。
 *
 * 一个 [signature] 只有一条：同类错误再犯时累加 [hits]，并让 LLM 重写这条教训
 * （第二、第三次往往能看到更完整的失败模式，比第一次瞎猜准得多）。
 *
 * [lesson] 会以「以前踩过的坑」的形式注入 Executor / 探针的提示词，
 * 所以必须写成**祈使句 + 具体做法**（「click_text 找不到时先 scroll 再试」），
 * 写成「注意失败」这种废话等于没写。
 */
@Entity(
    tableName = "mistake_note",
    indices = [Index(value = ["signature"], unique = true), Index("updatedAt")]
)
data class MistakeNote(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val signature: String,
    val kind: String,
    val lesson: String,
    /** 同类错误累计次数（达到 [REFLECT_THRESHOLD] 才生成，之后每次再犯都 +1） */
    val hits: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        /** 同类错误攒够 3 次才值得让 LLM 总结一次 */
        const val REFLECT_THRESHOLD = 3

        /** 一条教训的字数上限（要塞进提示词，长篇大论会挤掉工具清单） */
        const val LESSON_MAX_CHARS = 60
    }
}

/**
 * 话题种子 —— **「她为什么现在开口」**（0.9.4）。
 *
 * 0.9.2/0.9.3 的心跳只有一个触发条件：时间。时间到了，就从记忆里临时捞一条出来说。
 * 后果很具体：昨天你说「明天要体检」，今天她并不会问体检；明天她又问「在忙吗」。
 * 用户的原话是「她主动找我时没有理由，会反复问同一个问题」。
 *
 * 这张表补的就是那个「理由」：把一次对话里提到的**计划**和**身体/情绪状况**存成
 * 有状态的种子，心跳时先看有没有到期的 —— 有就顺着那件事开口，没有才泛泛问候。
 *
 * @param content     一句话，如「明天要体检」「最近头疼」
 * @param source      来源（[SOURCE_CHAT] = 从聊天里抽出来的）
 * @param askCount    被问过几次。0 = 还没问过，优先问
 * @param lastAskedAt 上次问的时间戳；[askCount] > 0 时，要隔 48 小时才能再问
 * @param expireAt    过期时间戳。计划过了、小病好了，再问就成了「你怎么还在头疼」
 */
@Entity(
    tableName = "topic_seed",
    indices = [Index(value = ["content"], unique = true), Index("expireAt")]
)
data class TopicSeed(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    val source: String = SOURCE_CHAT,
    val createdAt: Long = System.currentTimeMillis(),
    val askCount: Int = 0,
    val lastAskedAt: Long = 0L,
    val expireAt: Long = 0L
) {
    companion object {
        const val SOURCE_CHAT = "chat"

        /** 一条种子的字数上限（要塞进提示词，太长就变成转述聊天记录） */
        const val CONTENT_MAX = 40

        /** 表里最多留多少条（超了清最老的，种子的价值本来就有保质期） */
        const val MAX_SEEDS = 100
    }
}

/**
 * 周记 —— FlexiMem 的第三层（0.9.0）。
 *
 * 和 [MemoryEntity] 的区别：
 *  - [MemoryEntity] 是**抽出来的事实**（「用户喜欢猫」），靠模型抽，攒得慢；
 *  - 本表是**这一周发生了什么**（「用户周三说要开始跑步，周五又说膝盖疼」），
 *    靠**规则**从原文档案 [com.nexus.agent.persona.FlexiMem] 里挑，**一次模型都不调**。
 *
 * 一周固定 6 条：能写进周记的本来就该是「印象最深的几件事」，
 * 攒 50 条等于没重点，注入提示词还会把上下文挤爆。
 *
 * @param weekStart 这一周的周一 00:00（时间戳）。同一周的记忆共用它，便于整周查询与清理
 * @param content   一句话，≤ [CONTENT_MAX] 字
 * @param source    来源日期（`2026-11-05`），出问题时能回去翻那天的原文
 */
@Entity(
    tableName = "weekly_highlight",
    indices = [Index(value = ["weekStart", "content"], unique = true), Index("weekStart")]
)
data class WeeklyHighlight(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val weekStart: Long,
    val content: String,
    val source: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        /** 一条周记的字数上限（要塞进提示词，太长会把屏幕描述挤走） */
        const val CONTENT_MAX = 60

        /** 一周保留几条 */
        const val PER_WEEK = 6
    }
}

/**
 * 内心独白 —— 心智引擎的产物（0.9.6）。
 *
 * 为什么要单独一张表，而不是塞进 [PersonaProfile] 的某个字段：
 *  - **内心戏是有历史的**。塞一个字段只能看「最新一句」，出问题时没法回答
 *    「她昨天到底在想什么」「这句话为什么被注入了」；
 *  - **要能标记「用过了」**。同一句独白如果每轮都注入，她就会反复提同一件事，
 *    比不注入还假。[consumed] 就是这个开关。
 *
 * 与 [MemoryEntity] 的区别：记忆是「关于用户的客观事实」，本表是**她自己的想法**
 * （「也不知道他体检结果怎么样了，有点担心」）。前者能被检索、能被引用，
 * 后者只在她开口前影响语气和话题，属于「情绪底色」。
 *
 * @param content 一句独白，≤ [CONTENT_MAX] 字（太长就不像默念，像写作文）
 * @param mood    生成时的心情快照（「平静」「担心」），排查「她为什么突然冷淡」用
 * @param source  这次想事情的由头（`话题:明天体检` / `记忆` / `泛想`）
 * @param consumed 是否已经注入过对话；true 之后不再参与提示词
 */
@Entity(
    tableName = "inner_thought",
    indices = [Index("createdAt"), Index("consumed")]
)
data class InnerThought(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    val mood: String = "",
    val source: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val consumed: Boolean = false
) {
    companion object {
        /** 一句独白的字数上限 */
        const val CONTENT_MAX = 40

        /** 表里最多留多少条：这是「最近的念头」，不是日记本 */
        const val MAX_ROWS = 60
    }
}
