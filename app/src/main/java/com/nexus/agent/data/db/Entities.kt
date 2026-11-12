package com.nexus.agent.data.db

import androidx.room.ColumnInfo
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
    val lastHitAt: Long = System.currentTimeMillis(),

    // ---- 情感指纹（0.9.15，v8 → v9） ----
    //
    // 这五列存在的唯一目的：让「她此刻的心情」参与记忆召回（`MemoryRanker`）。
    // 老数据一律落成中性（pad 全 0、强度 0、关键词空），打分时既不会被抬高，
    // 也不会被压低 —— 中性记忆的「情绪一致性」是同一个常数，只压缩分差不改顺序。

    /** 编码这条事实时的 P 轴（愉悦度），[-1,1] */
    @ColumnInfo(defaultValue = "0") val padP: Float = 0f,

    /** 编码时的 A 轴（唤醒度） */
    @ColumnInfo(defaultValue = "0") val padA: Float = 0f,

    /** 编码时的 D 轴（支配感） */
    @ColumnInfo(defaultValue = "0") val padD: Float = 0f,

    /** 编码时的情绪强度 0~1。只影响遗忘速度：越强的记忆老得越慢 */
    @ColumnInfo(defaultValue = "0") val emotionalIntensity: Float = 0f,

    /**
     * 检索用关键词，`\u0001` 分隔（不能用逗号，中文逗号会把一个词劈成两半）。
     * 空 = 老数据，打分时现切，行为等价。
     */
    @ColumnInfo(defaultValue = "''") val keywords: String = ""
) {
    companion object {
        const val CATEGORY_PREFERENCE = "preference"
        const val CATEGORY_HABIT = "habit"
        const val CATEGORY_RELATION = "relation"
        const val CATEGORY_EVENT = "event"
        const val CATEGORY_GENERAL = "general"
    }

    /** 编码时的 PAD 坐标（老数据 = 中性） */
    fun padOf(): com.nexus.agent.persona.PAD =
        com.nexus.agent.persona.PAD(padP, padA, padD)
}

/**
 * 情感状态 —— 单行表（0.9.15，v9 新增）。
 *
 * ## 为什么单开一张表，不塞进 [PersonaProfile]
 *
 * `saveProfile()` 是**低频全量覆盖**（设置页改个名字就把整行写回）。PAD 是**每轮都变**
 * 的连续状态，混在同一张表里，用户每次编辑人设都会把心情清成中性 —— 而且没人会想到
 * 「我改个名字，她怎么突然不难过了」。
 *
 * 高频写的状态和低频写的档案，放在两张表里，是这一版最重要的一条防呆。
 *
 * ## 三组坐标
 *
 * ```
 * quick  快变量：本轮的瞬时情绪，半衰期 20 分钟
 * slow   慢变量：长期基调（EMA），半衰期 6 小时
 * base   基线  ：由性格三维 + 好感度推出，快慢都往它衰减
 * ```
 *
 * @param lastDecayAt 上一次落库的时刻。冷启动要按「现在 − lastDecayAt」补算衰减，
 *                    否则睡一晚醒来心情还是昨晚的峰值。
 */
@Entity(tableName = "pad_state")
data class PadState(
    @PrimaryKey val id: Long = SINGLETON_ID,

    val quickP: Float = 0f,
    val quickA: Float = 0f,
    val quickD: Float = 0f,

    val slowP: Float = 0f,
    val slowA: Float = 0f,
    val slowD: Float = 0f,

    val baseP: Float = 0f,
    val baseA: Float = 0f,
    val baseD: Float = 0f,

    val updatedAt: Long = 0L,
    val lastDecayAt: Long = 0L
) {
    companion object {
        /** 永远是这一行 */
        const val SINGLETON_ID = 1L
    }

    fun quickPAD(): com.nexus.agent.persona.PAD =
        com.nexus.agent.persona.PAD(quickP, quickA, quickD)

    fun slowPAD(): com.nexus.agent.persona.PAD =
        com.nexus.agent.persona.PAD(slowP, slowA, slowD)

    fun basePAD(): com.nexus.agent.persona.PAD =
        com.nexus.agent.persona.PAD(baseP, baseA, baseD)
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

// ==================== 外挂记忆（冻结模型，靠记忆自我进化） ====================

/**
 * 原始轨迹池 —— 白天只往这儿写，**不做任何加工**（0.9.14）。
 *
 * ## 为什么不再边跑边学
 *
 * 0.9.13 是「一跑完就加工」：成功一次就建一条候选记忆，失败一次就调模型出经验。
 * 两个后果都很糟：
 *  - **记忆污染**：用户手滑点错、或者某次界面刚好弹了个广告，都会被当成「他就是这么做事的」；
 *  - **成本失控**：一天做二十件事，就可能在后台调二十次模型。
 *
 * 现在改成「白天经历 + 夜间巩固」：这张表只记账，一次模型都不调；
 * 真正提炼发生在夜里的批处理（`MemoryConsolidator.consolidate`），
 * 那时能看到**一整天的全部样本**，能判断「这是偶发还是习惯」。
 *
 * ## 它依然是耗材
 *
 * 巩固完成后由 `processedAt` 标记归档，超过保留天数整行删除。
 * 资产在 [ActionMemory] 与 [IntentTip]，这张表删了不影响任何已学会的东西。
 *
 * @param kind       [KIND_AGENT_SUCCESS] / [KIND_AGENT_FAIL] / [KIND_AGENT_ABORT] / [KIND_REPLAY_FAIL]
 * @param intent     用户原话（截断），排查时得知道当时到底说了什么
 * @param intentKey  归一化后的意图（`IntentKey.normalize`），批处理按它分组
 * @param fingerprint 起始界面指纹（`ScreenFingerprint.of`）
 * @param screenKeys  起始界面的关键节点明文，用于算相似度与解释「差在哪」
 * @param steps      结构化动作序列（`ActionStep.encodeAll`），不是给人看的 trace
 * @param outcome    [OUTCOME_SUCCESS] / [OUTCOME_FAILED] / [OUTCOME_ABORTED]
 * @param failStep   第几步失败（1 基）；0 = 没失败或不知道
 * @param memoryId   仅 [KIND_REPLAY_FAIL] 有值：是哪条记忆回放失败了。
 *                   这是「让下一次巩固能纠正这个错误」的唯一凭证
 * @param batchId    巩固批号（0 = 还没被巩固过）
 * @param processedAt 巩固时间（0 = 待处理）。**判待处理只看这一列**，
 *                    不要用 batchId 判 —— 批号可能被复用，时间不会
 * @param source     [SOURCE_AGENT] 她自己跑的 / [SOURCE_USER] 用户手动操作的
 */
@Entity(
    tableName = "raw_trace",
    indices = [
        Index("intentKey"),
        Index("fingerprint"),
        Index("processedAt"),
        Index("createdAt"),
        Index("memoryId")
    ]
)
data class RawTrace(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String = KIND_AGENT_FAIL,
    val intent: String,
    val intentKey: String,
    val fingerprint: String,
    val screenKeys: String = "",
    val pkg: String = "",
    val steps: String = "",
    val outcome: String = OUTCOME_FAILED,
    val failStep: Int = 0,
    val detail: String = "",
    val source: String = SOURCE_AGENT,
    val memoryId: Long = 0,
    val batchId: Long = 0,
    val processedAt: Long = 0,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val KIND_AGENT_SUCCESS = "agent_success"
        const val KIND_AGENT_FAIL = "agent_fail"
        const val KIND_AGENT_ABORT = "agent_abort"

        /** 回放失败写回：不是「任务失败」，是「我记错了」。巩固时专门处理这一类 */
        const val KIND_REPLAY_FAIL = "replay_fail"

        /**
         * 用户自己点出来的一串动作（0.9.16）。
         *
         * 和上面几种的根本区别：**它没有意图**。用户点「发送」是「他想发消息」
         * 还是「他在演示给谁看」，程序无从判断，所以 `intent` / `intentKey` 都是空串，
         * 夜里也**只能**聚成候选（`state = candidate`），不许自动转正。
         * 分组不靠意图键，靠骨架相似度 —— 见 `MemoryConsolidator.clusterUserPaths`。
         *
         * `outcome` 记 success 只表示「他把这一串点完了」，不表示事情办成了；
         * 无障碍看不到结果，这一点在画像统计里也按「他常做的事」对待。
         */
        const val KIND_USER_PATH = "user_path"

        const val OUTCOME_SUCCESS = "success"
        const val OUTCOME_FAILED = "failed"
        const val OUTCOME_ABORTED = "aborted"

        const val SOURCE_AGENT = "agent"
        const val SOURCE_USER = "user"

        /**
         * 池子上限。
         *
         * 比 0.9.13 的 200 条大得多 —— 现在它要装一整天的量（含回放失败），
         * 200 条会在一个重度使用日下午就把早上的样本挤掉，夜里统计就失真了。
         * 正常用户一天 5~30 条，1200 条足够装一周；巩固完会按时间清。
         */
        const val MAX_ROWS = 1200

        /** 原话保留长度上限 */
        const val INTENT_MAX = 120

        /** 失败原因保留长度上限 */
        const val DETAIL_MAX = 200
    }

    /** 这次轨迹对「学出一条动作」有正面价值吗 */
    val isSuccessSample: Boolean
        get() = outcome == OUTCOME_SUCCESS && steps.isNotBlank()

    val isFailureSample: Boolean
        get() = kind == KIND_AGENT_FAIL || kind == KIND_REPLAY_FAIL
}

/**
 * 动作记忆 —— **可以直接回放的肌肉记忆**（0.9.13）。
 *
 * ## 它解决的账
 *
 * 0.9.12 之前，同一句「打开微信搜一下订阅号」说十遍，就是十次完整的
 * 云端规划 + 逐步推理。每次两三秒、几千 token，做的事却一模一样。
 * 这张表把它压成一条固定动作序列：第二次起直接照着点，不调模型。
 *
 * ## 两条转正路径，同一张表（0.9.16）
 *
 * ```
 * origin=agent  CANDIDATE --(成功累计 3 次)--> ACTIVE            （自动）
 * origin=user   CANDIDATE --(用户点「收下」并给它起名)--> ACTIVE  （必须人点）
 * ```
 *
 * 为什么手动路径不能自动转正：无障碍事件里**没有意图**。用户点「发送」可能是
 * 「他要发消息」，也可能只是「他在翻聊天记录时手滑」。没有名字，就没有下一次
 * 能被命中的那个「要干什么」。[label] 就是那个名字，也是它唯一的意图来源。
 *
 * ## 状态机（**没有任何路径能让 agent 记忆不攒够次数就 ACTIVE**）
 *
 * ```
 * CANDIDATE --(成功累计 3 次)--> ACTIVE --(30 天没被命中)--> EXPIRED
 *                                   |--(首次回放失败)--> SUSPECT --(当晚又成功)--> ACTIVE
 *                                   |                      \--(再失败一次)--> REVOKED
 *                                   \--(用户点「不要」)--> REJECTED（永不再学）
 * EXPIRED / REVOKED --(又成功跑通一次)--> 回到 CANDIDATE（重新攒）
 * ```
 *
 * [STATE_SUSPECT] 是 0.9.16 加的中间态：一次回放失败**当场就不参与路由**
 * （满足「错了立刻不拦」），但不永久作废 —— 界面卡一下、广告挡一下都会导致
 * 一次失败，一次就废掉的话，用户体感是「她昨天还会，今天突然不会了」。
 *
 * @param memoryKey      `指纹|意图键`，唯一索引 —— 一条「在哪个界面做什么」
 * @param intent         给人看的说明（agent：用户原话；user：系统给的建议名）
 * @param label          用户认可的名字。**只有 origin=user 需要它非空才能回放**
 * @param origin         [ORIGIN_AGENT] 她自己跑的 / [ORIGIN_USER] 用户手动点的
 * @param coverage       骨架覆盖率（骨架步数 / 最长样本步数），0 = 老数据或导入
 * @param steps          参数化后的动作序列（`ActionStep.encodeAll`）
 * @param hits           被成功回放的次数（不是被采集的次数）
 * @param successCount   累计成功回放次数
 * @param failCount      累计回放失败次数
 * @param consecutiveFail 连续失败次数，到 [MAX_CONSECUTIVE_FAIL] 立刻作废
 * @param state          见上方状态机
 * @param weight         达尔文评分（频率 + 新近 + 成功率），只用于排序和展示
 */
@Entity(
    tableName = "action_memory",
    indices = [
        Index(value = ["memoryKey"], unique = true),
        Index("state"),
        Index("lastHitAt")
    ]
)
data class ActionMemory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val memoryKey: String,
    val intent: String,
    val intentKey: String,
    val fingerprint: String,
    val screenKeys: String = "",
    val pkg: String = "",
    @ColumnInfo(defaultValue = "agent") val origin: String = ORIGIN_AGENT,
    @ColumnInfo(defaultValue = "") val label: String = "",
    @ColumnInfo(defaultValue = "0") val coverage: Float = 0f,
    val steps: String = "",
    val hits: Int = 0,
    val successCount: Int = 0,
    val failCount: Int = 0,
    val consecutiveFail: Int = 0,
    val state: String = STATE_CANDIDATE,
    val weight: Float = 0f,
    val createdAt: Long = System.currentTimeMillis(),
    val lastHitAt: Long = 0L,
    val lastFailAt: Long = 0L,
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val STATE_CANDIDATE = "candidate"
        const val STATE_ACTIVE = "active"
        const val STATE_EXPIRED = "expired"
        const val STATE_REVOKED = "revoked"

        /** 回放失败一次后的中间态：不参与路由，等当晚的成功轨迹来决定复活还是报废 */
        const val STATE_SUSPECT = "suspect"

        /** 用户明确说「不要这个动作」。与 revoked 区分：它**不会**因为又跑通而长回来 */
        const val STATE_REJECTED = "rejected"

        const val ORIGIN_AGENT = "agent"
        const val ORIGIN_USER = "user"

        /** 成功累计多少次才值得当「肌肉记忆」直接回放 */
        const val ACTIVE_THRESHOLD = 3

        /** 连续几次回放失败就作废（不是「降级」，是立刻失去拦截资格） */
        const val MAX_CONSECUTIVE_FAIL = 2

        /** 多少天没被命中就算过期（不删除，只是不再参与拦截） */
        const val EXPIRE_DAYS = 30

        /** 一条记忆最多几步（太长就不是「习惯」，是长任务，回放失败率极高） */
        const val MAX_STEPS = 12

        /** 用户给动作起的名字最长多少字（设置页一行放得下） */
        const val LABEL_MAX = 24

        val EXPIRE_MS = EXPIRE_DAYS * 24L * 60 * 60 * 1000
    }

    val isUserOrigin: Boolean get() = origin == ORIGIN_USER

    /**
     * 能不能参与第一级拦截。
     *
     * `origin=user` 多一道：**必须有名字**。名字是它唯一的意图来源，
     * 没名字的记忆就算攒够次数也不许回放 —— 那等于拿一串没有目的的动作去点屏幕。
     */
    val isReplayable: Boolean
        get() = state == STATE_ACTIVE &&
            steps.isNotBlank() &&
            (!isUserOrigin || label.isNotBlank())
}

/**
 * 经验记忆 —— 「这个界面上有个坑」（0.9.13）。
 *
 * 与 [MistakeNote] 的分工：
 *  - [MistakeNote] 是**错误类型级**的（「click_text 找不到控件」→ 先 scroll），跨界面通用；
 *  - 本表是**界面级**的（「在微信搜索页点搜索框前，得先等键盘弹出」），
 *    它带着指纹，所以只在该界面生效，不会污染别的场景。
 *
 * 只在第二级路由注入：没有可直接回放的动作时，把它塞进提示词让模型少踩一次。
 */
@Entity(
    tableName = "intent_tip",
    indices = [Index(value = ["tipKey"], unique = true), Index("updatedAt")]
)
data class IntentTip(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tipKey: String,
    val fingerprint: String,
    val intentKey: String,
    val pkg: String = "",
    val tip: String,
    val hits: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        /** 一条经验最长字数（塞进提示词，太长会挤掉工具清单） */
        const val TIP_MAX_CHARS = 60

        /** 表里最多留多少条 */
        const val MAX_ROWS = 60
    }
}

/**
 * 房间里的一件家具（0.9.17，赛博房间批次 B）。
 *
 * ## 为什么家具是数据而不是代码
 *
 * 第一版画出来的只是几何色块，看起来跟「装修系统」没关系。但**形状是画出来的，
 * 摆什么是数据**：这一层拆开的唯一理由，是下一批要接「自然语言装修」——
 * 用户说「把床挪到窗边、台灯暗一点」，模型只需要产出一串 [RoomItem] 变更，
 * 不用改代码、不用发版。
 *
 * @param kind  家具种类，取值见 [KIND_SOFA] 等 7 个常量。**未知种类不报错**，
 *              渲染层画成一个标注了 [note] 的方盒子 —— 模型哪天编出一个新词，
 *              房间不会整间空掉。
 * @param slot  位置槽位，取值见 [SLOT_BACK_LEFT] 等 7 个常量。存字符串不存坐标，
 *              因为房间是**透视**的：同一个「左后方」在平板和手机上差得远，
 *              存槽位才能让一份数据在任何屏幕上都不穿帮。
 * @param tint  ARGB 颜色（不含 alpha，家具一律不透明）
 * @param scale 0.5~1.5 的额外缩放；越界由渲染层钳制，不在写入时拦
 * @param flip  是否左右镜像（同一张沙发摆两边用）
 * @param note  备注 / 装修时的原话（「靠窗那张旧沙发」），第一版只在未知种类时显示
 */
@Entity(tableName = "room_item")
data class RoomItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String = KIND_SOFA,
    val slot: String = SLOT_MID_LEFT,
    val tint: Int = DEFAULT_TINT,
    val scale: Float = 1f,
    val flip: Boolean = false,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        // ---- 7 种家具（第一版全部由 Canvas 画几何形状，不引入任何图片资源）----
        const val KIND_SOFA = "sofa"
        const val KIND_BED = "bed"
        const val KIND_DESK = "desk"
        const val KIND_LAMP = "lamp"
        const val KIND_SHELF = "shelf"
        const val KIND_PLANT = "plant"
        const val KIND_RUG = "rug"

        val KINDS = listOf(KIND_SOFA, KIND_BED, KIND_DESK, KIND_LAMP, KIND_SHELF, KIND_PLANT, KIND_RUG)

        // ---- 槽位 ----
        // 命名里带深度前缀（back/mid/front），渲染时按 y 从远到近画，
        // 不然一把椅子可能画在墙前面、画在床后面，靠 id 顺序碰运气。
        //
        // 刻意**没有** `mid_center`：那是她站的位置。第一版只有 7 件家具，
        // 与其让用户把沙发摆在她脚下，不如干脆不提供这个槽位。
        const val SLOT_BACK_LEFT = "back_left"
        const val SLOT_BACK_MID = "back_mid"
        const val SLOT_BACK_RIGHT = "back_right"
        const val SLOT_MID_LEFT = "mid_left"
        const val SLOT_MID_RIGHT = "mid_right"
        const val SLOT_FRONT_LEFT = "front_left"
        const val SLOT_FRONT_MID = "front_mid"
        const val SLOT_FRONT_RIGHT = "front_right"

        val SLOTS = listOf(
            SLOT_BACK_LEFT, SLOT_BACK_MID, SLOT_BACK_RIGHT,
            SLOT_MID_LEFT, SLOT_MID_RIGHT,
            SLOT_FRONT_LEFT, SLOT_FRONT_MID, SLOT_FRONT_RIGHT
        )

        /** 默认家具色：低饱和的暖灰，跟极简色板同一个调子 */
        val DEFAULT_TINT = 0xFFB9AFA3.toInt()

        const val SCALE_MIN = 0.5f
        const val SCALE_MAX = 1.5f

        /** 备注最长字数（塞进标签，长了会盖住家具本身） */
        const val NOTE_MAX = 24
    }
}

/**
 * 房间主题（0.9.17，赛博房间批次 B）。**单行表**。
 *
 * 为什么不做成 `RoomItem` 的一种：主题是「整间屋子」的属性，家具可以有一百件，
 * 主题永远只有一个。单行表 + `id = 1`，读写都不用查「哪一行是当前主题」。
 *
 * @param wall   墙色 ARGB
 * @param floor  地板色 ARGB
 * @param window 有没有窗（第一版只影响墙上那个亮矩形，不参与光照计算）
 * @param preset 预设名（[PRESET_DEFAULT] 等），留给下一批自然语言装修按名切换
 */
@Entity(tableName = "room_theme")
data class RoomTheme(
    @PrimaryKey val id: Long = SINGLETON_ID,
    val wall: Int = DEFAULT_WALL,
    val floor: Int = DEFAULT_FLOOR,
    val window: Boolean = true,
    val preset: String = PRESET_DEFAULT,
    val updatedAt: Long = 0L
) {
    companion object {
        /** 永远只有这一行 */
        const val SINGLETON_ID = 1L

        const val PRESET_DEFAULT = "default"
        const val PRESET_NIGHT = "night"
        const val PRESET_STUDIO = "studio"

        /** 暖木小屋：米墙 + 橡木地板 */
        val DEFAULT_WALL = 0xFFE9E3D8.toInt()
        val DEFAULT_FLOOR = 0xFFC2A181.toInt()

        /** 夜灯：深蓝墙 + 冷灰地板 */
        val NIGHT_WALL = 0xFF2E3A4E.toInt()
        val NIGHT_FLOOR = 0xFF4A5568.toInt()

        /** 工作室：纯白墙 + 浅灰水泥地 */
        val STUDIO_WALL = 0xFFF4F4F4.toInt()
        val STUDIO_FLOOR = 0xFFCFCFCF.toInt()
    }
}
