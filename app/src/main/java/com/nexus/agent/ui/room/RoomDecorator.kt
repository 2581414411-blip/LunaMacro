package com.nexus.agent.ui.room

import android.content.Context
import android.util.Log
import com.nexus.agent.agent.LlmClient
import com.nexus.agent.agent.LlmClientFactory
import com.nexus.agent.data.db.AppDatabase
import com.nexus.agent.data.db.RoomItem
import com.nexus.agent.data.db.RoomTheme
import com.nexus.agent.util.MiniJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 自然语言装修（0.9.19 批次 C）。
 *
 * ## 一句话说清职责
 *
 * 把「加个红色的沙发」「把墙换成深蓝」这类话，变成对 `room_item` / `room_theme`
 * 的**受限写入**。它是唯一能改房间数据的入口 —— 界面、聊天、以后的定时任务
 * 都从这里进，校验规则因此只有一份。
 *
 * ## 为什么模型不是第一顺位
 *
 * 见 [RoomIntent]：格式化指令先本地解析，认不出才问模型。模型返回的 JSON
 * 和本地解析结果**走同一个校验器** [validate]，所以「本地能塞 13 件、模型不能」
 * 这种分裂不可能发生。
 *
 * ## 三件模型碰不到的东西
 *
 * 1. **坐标**：它只能给槽位名，槽位到坐标的换算在 [RoomLayout]；
 * 2. **数量**：硬上限 [MAX_ITEMS]，写之前在事务里再数一遍；
 * 3. **颜色**：只能给词表里的名字，不能给数值。
 *
 * 这不是不信任模型，是因为**它的错误没有代价**：编一个 `sofa_big` 它不会疼，
 * 用户的房间会变成一地方盒子。所以白名单在本地，不在提示词里。
 */
object RoomDecorator {

    private const val TAG = "RoomDebug"

    /** 屋子最多摆几件 —— 超了每帧 Canvas 会开始掉帧，而且屋子看着像仓库 */
    const val MAX_ITEMS = 12

    /** 一轮最多加几件。模型偶尔会一次吐十几件，那是「理解错了」而不是「想要十几件」 */
    const val MAX_PER_TURN = 4

    /** 等模型多久。它只是把一句话翻译成 JSON，不该像写长文一样等 */
    private const val LLM_TIMEOUT_MS = 18_000L

    // ------------------------------------------------------------ 结果类型

    /** 校验通过后要写的一件家具（还没有 id） */
    data class Draft(
        val kind: String,
        val slot: String,
        val tint: Int,
        val note: String
    )

    /** 校验器的输出。**不碰数据库**，所以可以在纯 JVM 单测里跑。 */
    sealed interface Decision {
        /**
         * @param relocate true = 「把已有的那件挪过去」，不再新增
         * @param dropped  有多少件因为我们不认识而被丢掉（要说给用户听）
         */
        data class Add(
            val drafts: List<Draft>,
            val relocate: Boolean,
            val dropped: Int
        ) : Decision

        data class Theme(
            val wall: Int?,
            val floor: Int?,
            val window: Boolean?,
            val preset: String?
        ) : Decision

        data class Remove(val ids: List<Long>, val kinds: List<String>) : Decision

        /** 用户能听懂的一句话；用来做降级提示，也可以直接塞进她嘴里 */
        data class Reject(val reason: String) : Decision
    }

    /** 一次装修的结果。`message` 是**事实描述**，不是她的语气 —— 语气交给角色 LLM */
    data class Outcome(
        val ok: Boolean,
        val message: String,
        /** local / llm / none（用来排障：这句话是靠关键词还是靠模型听懂的） */
        val source: String
    )

    // ------------------------------------------------------------ 校验器（纯逻辑）

    /**
     * 把一张「和模型 JSON 同形」的 map 变成可执行的 [Decision]。
     *
     * **宽容度是分层的，这是刻意的**：
     *
     * | 字段 | 错了怎么办 | 为什么 |
     * |---|---|---|
     * | `kind` | 丢掉那一件 | 认不出的种类画不出来，只能是个方盒子 |
     * | `slot` | 落到默认槽位 | 位置错了至少还在屋里，比丢了强 |
     * | `color` | 用该种类的默认色 | 颜色是最不影响结构的信息 |
     * | `action` | 整条拒绝 | 不知道要干什么，猜就是乱来 |
     *
     * @param current 房间里现有的家具（用来判重、算余量、找空位）
     */
    fun validate(
        raw: Map<String, Any?>?,
        current: List<RoomItem>,
        userText: String
    ): Decision {
        if (raw == null) return Decision.Reject("我没听清你想把屋子改成什么样")

        // 模型偶尔会漏 action 只给 items —— 那就按 add 处理，别让整句白说
        val action = MiniJson.str(raw, "action")?.lowercase()
            ?: if (raw["items"] != null) "add" else null
            ?: return Decision.Reject("这句话我没听出要做什么")

        return when (action) {
            "add", "move", "replace" -> add(raw, current, userText)
            "theme" -> theme(raw)
            "remove", "delete" -> remove(raw, current)
            else -> Decision.Reject("我还不认识「$action」这种改法")
        }
    }

    private fun add(raw: Map<String, Any?>, current: List<RoomItem>, userText: String): Decision {
        val list = (raw["items"] as? List<*>)
            ?: (raw["item"]?.let { listOf(it) })
            ?: return Decision.Reject("你说要加东西，但我没听出是什么")

        var dropped = 0
        val accepted = ArrayList<Pair<String, Map<String, Any?>>>()
        for (entry in list) {
            @Suppress("UNCHECKED_CAST")
            val m = entry as? Map<String, Any?>
            val kind = m?.let { canonicalKind(MiniJson.str(it, "kind")) }
            if (m == null || kind == null) {
                dropped++
                continue
            }
            accepted.add(kind to m)
            if (accepted.size >= MAX_PER_TURN) break
        }

        if (accepted.isEmpty()) {
            return Decision.Reject("你说的那些家具我一件都画不出来")
        }

        // 「把床搬到右边」不是「再买一张床」：有移动动词、且屋里已有同类，就改那件
        val relocate = RoomIntent.isMove(userText) &&
            accepted.size == 1 &&
            current.any { it.kind == accepted[0].first }

        val used = current.map { it.slot }.toMutableSet()
        var room = if (relocate) {
            // 挪动不占新位置：先把它自己算进来的那一格释放掉
            current.count { it.kind != accepted[0].first }
        } else {
            current.size
        }

        val drafts = ArrayList<Draft>()
        for ((kind, m) in accepted) {
            if (room >= MAX_ITEMS) {
                dropped++
                continue
            }
            val slot = RoomLayout.geometricOf(MiniJson.str(m, "slot") ?: "")
                ?: RoomLayout.freeSlotGeometricOf(kind, used)
            used.add(slot)
            val tint = RoomPalette.argbOf(MiniJson.str(m, "color"))
                ?: RoomPalette.defaultTintOf(kind)
            drafts.add(Draft(kind, slot, tint, userText.take(RoomItem.NOTE_MAX)))
            room++
        }

        if (drafts.isEmpty()) {
            return Decision.Reject("屋里已经摆了 $MAX_ITEMS 件，再加就挤不下了")
        }
        return Decision.Add(drafts, relocate, dropped)
    }

    /**
     * 换主题。
     *
     * **预设必须落实成具体颜色**：`preset` 字段只进库不参与渲染，如果只写
     * `preset=night` 而 wall/floor 留空，用户说「弄成夜里那样」会看到屋子
     * 一点没变（库里多了一个字符串）。所以这里把预设展开成那套墙色/地板色，
     * 用户显式给的颜色优先级更高（「夜里那样，但墙要深蓝」）。
     */
    private fun theme(raw: Map<String, Any?>): Decision {
        val explicitWall = RoomPalette.argbOf(MiniJson.str(raw, "wall"))
        val explicitFloor = RoomPalette.argbOf(MiniJson.str(raw, "floor"))
        val presetName = MiniJson.str(raw, "preset")
            ?.takeIf { name -> RoomDefaults.PRESETS.any { it.name == name } }
        val preset = presetName?.let { RoomDefaults.presetOf(it) }
        val window = raw["window"] as? Boolean ?: preset?.window

        val wall = explicitWall ?: preset?.wall
        val floor = explicitFloor ?: preset?.floor

        if (wall == null && floor == null && preset == null) {
            return Decision.Reject("要换的墙色和地板色我都没认出来")
        }
        return Decision.Theme(wall, floor, window, preset?.name)
    }

    private fun remove(raw: Map<String, Any?>, current: List<RoomItem>): Decision {
        val names = ArrayList<String>()
        MiniJson.str(raw, "kind")?.let { names.add(it) }
        (raw["kinds"] as? List<*>)?.forEach { it?.let { k -> names.add(k.toString()) } }
        if (names.isEmpty()) return Decision.Reject("要撤哪件？我没听清")

        val kinds = names.mapNotNull { canonicalKind(it) }
        val ids = current.filter { it.kind in kinds }.map { it.id }
        if (ids.isEmpty()) {
            return Decision.Reject("屋里本来就没有${kinds.firstOrNull()?.let { zhOfKind(it) } ?: "那件"}呀")
        }
        return Decision.Remove(ids, kinds)
    }

    /** 种类归一：英文名、中文词、复数、模型自创的近义词 */
    fun canonicalKind(raw: String?): String? {
        val s = raw?.trim()?.lowercase() ?: return null
        if (s.isEmpty()) return null
        if (s in RoomItem.DECOR_KINDS) return s
        val zh = ZH_KIND_ALIASES[s] ?: ZH_KIND_ALIASES.entries.firstOrNull { s.contains(it.key) }?.value
        if (zh != null) return zh
        return RoomItem.DECOR_KINDS.firstOrNull { s.startsWith(it) || it.startsWith(s) }
    }

    private val ZH_KIND_ALIASES: Map<String, String> = mapOf(
        "沙发" to RoomItem.KIND_SOFA, "sofa" to RoomItem.KIND_SOFA,
        "床" to RoomItem.KIND_BED, "bed" to RoomItem.KIND_BED,
        "桌" to RoomItem.KIND_DESK, "书桌" to RoomItem.KIND_DESK, "desk" to RoomItem.KIND_DESK,
        "灯" to RoomItem.KIND_LAMP, "台灯" to RoomItem.KIND_LAMP, "lamp" to RoomItem.KIND_LAMP,
        "书架" to RoomItem.KIND_SHELF, "柜" to RoomItem.KIND_SHELF, "shelf" to RoomItem.KIND_SHELF,
        "植物" to RoomItem.KIND_PLANT, "绿植" to RoomItem.KIND_PLANT, "花" to RoomItem.KIND_PLANT,
        "plant" to RoomItem.KIND_PLANT,
        "地毯" to RoomItem.KIND_RUG, "rug" to RoomItem.KIND_RUG,
        "画" to RoomItem.KIND_POSTER, "挂画" to RoomItem.KIND_POSTER, "海报" to RoomItem.KIND_POSTER,
        "poster" to RoomItem.KIND_POSTER
    )

    fun zhOfKind(kind: String): String? = ZH_KIND_ALIASES.entries.firstOrNull { it.value == kind }?.key

    // ------------------------------------------------------------ 落库

    /**
     * 完整一轮：本地解析 → （认不出才）问模型 → 校验 → 写库。
     *
     * 写库走 [applyDecision]，它只做「决定好的事」，不再校验 ——
     * 校验和写入分开，是为了让单测能只跑校验（纯 JVM，不碰 Room）。
     */
    suspend fun run(context: Context, text: String): Outcome {
        val app = context.applicationContext
        val db = AppDatabase.get(app)
        val current = withContext(Dispatchers.IO) { db.roomItemDao().all() }

        // ① 本地：零延迟、离线可用、结果可复现
        RoomIntent.parse(text)?.let { local ->
            val decision = validate(local, current, text)
            val outcome = applyDecision(app, decision, text)
            Log.i(TAG, "[装修] 来源=本地 决定=${decision::class.java.simpleName} 结果=${outcome.message}")
            return outcome.copy(source = "local")
        }

        // ② 模型：只为了「听懂」，不为了写数据
        val json = askLlm(app, text)
        if (json == null) {
            Log.i(TAG, "[装修] 来源=无（本地认不出，模型不可用）")
            return Outcome(false, "这句我还没听懂，说具体点？比如「加个红色沙发」", "none")
        }
        val parsed = MiniJson.parseObject(json)
        val decision = validate(parsed, current, text)
        val outcome = applyDecision(app, decision, text)
        Log.i(TAG, "[装修] 来源=模型 决定=${decision::class.java.simpleName} 结果=${outcome.message}")
        return outcome.copy(source = "llm")
    }

    /** 只写「已经决定好的事」。失败一律降级成 Outome(false)，不抛。 */
    suspend fun applyDecision(context: Context, decision: Decision, userText: String): Outcome =
        withContext(Dispatchers.IO) {
            val db = AppDatabase.get(context.applicationContext)
            val now = System.currentTimeMillis()
            runCatching {
                when (decision) {
                    is Decision.Reject -> Outcome(false, decision.reason, "none")

                    is Decision.Add -> {
                        var moved = 0
                        var alreadyThere = false
                        if (decision.relocate) {
                            val target = decision.drafts.first()
                            val existing = db.roomItemDao().all()
                                .firstOrNull { it.kind == target.kind }
                            if (existing != null) {
                                // 本来就在那儿：「挪到右边」而她已经在右边时，
                                // 不要回一句「挪好了」—— 用户会盯着没变的画面怀疑她在应付。
                                if (existing.slot == target.slot && existing.tint == target.tint) {
                                    alreadyThere = true
                                } else {
                                    db.roomItemDao().update(
                                        existing.copy(
                                            slot = target.slot,
                                            tint = target.tint,
                                            note = target.note
                                        )
                                    )
                                    moved = 1
                                }
                            }
                        }
                        val toInsert = if (moved == 1) decision.drafts.drop(1) else decision.drafts
                        toInsert.forEach { d ->
                            db.roomItemDao().insert(
                                RoomItem(
                                    kind = d.kind,
                                    slot = d.slot,
                                    tint = d.tint,
                                    note = d.note,
                                    createdAt = now
                                )
                            )
                        }
                        val total = db.roomItemDao().count()
                        val sb = StringBuilder()
                        if (alreadyThere) {
                            val d = decision.drafts.first()
                            sb.append("${zhOfKind(d.kind) ?: d.kind}本来就在${zhOfSlot(d.slot)}呀")
                        } else if (moved == 1) {
                            val d = decision.drafts.first()
                            sb.append("${zhOfKind(d.kind) ?: d.kind}挪到${zhOfSlot(d.slot)}了")
                        } else {
                            sb.append(decision.drafts.joinToString("、") {
                                "${RoomPalette.zhOf(it.tint) ?: ""}${zhOfKind(it.kind) ?: it.kind}"
                                    .trim() + "摆到${zhOfSlot(it.slot)}"
                            })
                        }
                        if (decision.dropped > 0) sb.append("（有 ${decision.dropped} 件没认出来，先没放）")
                        sb.append("，屋里现在 $total 件")
                        Outcome(true, sb.toString(), "local")
                    }

                    is Decision.Theme -> {
                        val old = db.roomThemeDao().get() ?: RoomDefaults.theme()
                        val preset = decision.preset?.let { RoomDefaults.presetOf(it).name } ?: old.preset
                        val merged = old.copy(
                            wall = decision.wall ?: old.wall,
                            floor = decision.floor ?: old.floor,
                            window = decision.window ?: old.window,
                            preset = preset,
                            updatedAt = now
                        )
                        db.roomThemeDao().upsert(merged)
                        val parts = ArrayList<String>()
                        // 预设展开成颜色之后，wall/floor 一般都有值，这句只是兜底
                        decision.wall?.let { parts.add("墙刷成${RoomPalette.zhOf(it) ?: "新颜色"}") }
                        decision.floor?.let { parts.add("地板换成${RoomPalette.zhOf(it) ?: "新颜色"}") }
                        if (parts.isEmpty()) parts.add("屋子换成${zhOfPreset(preset)}的样子了")
                        Outcome(true, parts.joinToString("，"), "local")
                    }

                    is Decision.Remove -> {
                        decision.ids.forEach { db.roomItemDao().deleteById(it) }
                        val total = db.roomItemDao().count()
                        val what = decision.kinds.mapNotNull { zhOfKind(it) }.joinToString("、")
                        Outcome(true, "把${what}撤走了，屋里剩 $total 件", "local")
                    }
                }
            }.getOrElse { e ->
                Log.w(TAG, "[装修] 写库失败：${e.javaClass.simpleName} ${e.message}")
                Outcome(false, "改动没写进屋子，等下再试？", "none")
            }
        }

    /** 预设名回话用中文 —— 「屋子换成 night 的样子了」是代码在说话，不是她 */
    private fun zhOfPreset(preset: String): String = when (preset) {
        RoomTheme.PRESET_NIGHT -> "夜间"
        RoomTheme.PRESET_STUDIO -> "工作室"
        else -> "暖木小屋"
    }

    private fun zhOfSlot(geometric: String): String = when (geometric) {
        RoomItem.SLOT_BACK_LEFT, RoomItem.SLOT_MID_LEFT, RoomItem.SLOT_FRONT_LEFT -> "左边"
        RoomItem.SLOT_BACK_RIGHT, RoomItem.SLOT_MID_RIGHT, RoomItem.SLOT_FRONT_RIGHT -> "右边"
        RoomItem.SLOT_WINDOW_SIDE -> "窗边"
        RoomItem.SLOT_ON_DESK -> "桌上"
        RoomItem.SLOT_ON_WALL -> "墙上"
        else -> "中间"
    }

    // ------------------------------------------------------------ 模型调用

    /**
     * 让模型把一句话翻成 JSON。
     *
     * 三处刻意的严格：
     *  - **temperature 之类不试**：LlmClient 不支持，别为它加一层；
     *  - 只给一句 system + 一句 user，不带历史（装修不需要上下文，带了反而跑题）；
     *  - 超时 [LLM_TIMEOUT_MS]：翻译一句话不该等一分钟。
     *
     * 任何失败都返回 null，由调用方降级成「我没听懂」——装修失败不该让聊天崩掉。
     */
    private suspend fun askLlm(context: Context, text: String): String? {
        val provider = runCatching { LlmClientFactory.getProvider(context) }.getOrNull() ?: return null
        val (apiKey, _, _) = runCatching {
            LlmClientFactory.getConfig(context, provider)
        }.getOrElse { return null }
        // 云端没 Key 就没必要等一个必然 401 的请求；本地 ollama 不需要 Key
        if (apiKey.isBlank() && provider != "ollama") return null

        return withTimeoutOrNull(LLM_TIMEOUT_MS) {
            runCatching {
                val client = LlmClientFactory.create(provider, context)
                client.chat(
                    listOf(
                        LlmClient.Message("system", SYSTEM_PROMPT),
                        LlmClient.Message("user", text.take(120))
                    )
                )
            }.onFailure {
                Log.w(TAG, "[装修] 模型调用失败：${it.javaClass.simpleName} ${it.message?.take(80)}")
            }.getOrNull()
        }
    }

    /**
     * 给模型的提示词。
     *
     * 句式刻意像「接口文档」而不是「聊天」：这个任务不需要它发挥，需要它**照着填**。
     * 末尾三句警告针对的是本地小模型最常见的三种走偏：编坐标、编种类、解释一大堆。
     */
    val SYSTEM_PROMPT: String = """
        你现在的任务是修改赛博房间。请根据用户的要求，输出严格的 JSON。

        可用的家具种类(kind)：${RoomItem.DECOR_KINDS.joinToString(", ")}
        可用的摆放槽位(slot)：${RoomLayout.SLOTS.joinToString(", ")}
        可用的颜色(color)：${RoomPalette.SWATCHES.joinToString(", ") { it.name }}

        输出格式要求：
        如果添加/移动家具：{"action": "add", "items": [{"kind": "sofa", "slot": "left_wall", "color": "red"}]}
        如果改变主题：{"action": "theme", "wall": "dark", "floor": "gray"}
        如果撤走家具：{"action": "remove", "kind": "lamp"}

        注意：绝对不允许输出坐标，只能用上述的槽位！
        注意：kind / slot / color 只能从上面三行里选，不许自创。
        注意：只输出 JSON 本体，不要解释、不要 Markdown 代码块。
    """.trimIndent()
}
