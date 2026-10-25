package com.nexus.agent.agent.state

import android.content.Context
import android.util.Log
import com.nexus.agent.input.ShizukuManager

/**
 * 任务状态机。
 *
 * 解决的问题：原来的执行器每一步都把「所有工具」丢给模型选，游戏/复杂界面里
 * 模型很容易在错误的界面上点错东西（例如在主界面点「全部领取」），
 * 而且没有任何"我现在到底在哪个界面"的显式概念。
 *
 * 状态机做三件事：
 * 1. **识别**：用 UI 树可见文本（必要时用本地视觉模型补充）判断当前处于哪个状态；
 * 2. **约束**：每个状态只开放合理的工具（[StateDef.allowedTools]），越界动作在
 *    执行前就被拦下；
 * 3. **校验**：动作执行后重新识别，若没有产生「预期转移」，累计计数，
 *    连续多次不动 → 判定卡住 → 交给恢复流程/重规划。
 *
 * 设计原则：**配置缺失时完全旁路**。没有对应 profile、或状态识别为 UNKNOWN 时，
 * `allowTool` 一律放行，绝不让状态机本身成为新的失败点。
 */
object TaskStateMachine {

    private const val TAG = "TaskStateMachine"

    /** 未识别出状态 */
    const val UNKNOWN = "UNKNOWN"

    /** 前台不在本配置的应用内（用户切走了 / 应用崩了） */
    const val FOREIGN = "FOREIGN"

    /** 连续多少次动作没有产生状态变化，判定为「卡住」 */
    private const val STUCK_THRESHOLD = 3

    /** 这些工具永远放行（逃逸通道，否则状态机配错会把任务焊死） */
    private val ALWAYS_ALLOWED = setOf(
        "finish", "wait", "press_back", "press_home",
        "list_macros", "stop_game_auto"
    )

    /** 状态识别结果 */
    data class Snapshot(
        val profileId: String?,
        val stateId: String,
        val stateName: String,
        val confidence: Float,
        /** 是否处于配置里标记的异常态 */
        val abnormal: Boolean,
        val stable: Boolean,
        /** 是否判定卡住（连续动作无状态变化） */
        val stuck: Boolean = false,
        val reason: String = ""
    ) {
        val unknown: Boolean get() = stateId == UNKNOWN
        val foreign: Boolean get() = stateId == FOREIGN
        /** 状态机是否真正生效（无 profile 时不起约束作用） */
        val active: Boolean get() = profileId != null && !unknown
    }

    /** 一次动作后的转移结果 */
    data class ActionOutcome(
        val expected: String?,
        val actual: String,
        val moved: Boolean,
        val stuck: Boolean
    )

    @Volatile
    private var profile: StateProfile? = null

    @Volatile
    private var snapshot: Snapshot? = null

    private var currentStateId: String? = null
    private var consecutiveNoMove = 0
    private var moveHistory = ArrayDeque<String>()

    /** 当前是否已被判定异常（供恢复流程判断） */
    @Volatile
    var abnormalFlag: Boolean = false
        private set

    /**
     * 任务开始时重置，并按前台包名（或任务文本里提到的应用名）挂载对应配置。
     *
     * @param taskHint 任务原文；当起始前台是桌面（无配置）时，用它匹配「原神/星穹铁道」这类名字
     * @return 挂载到的配置；null 表示该应用没有状态机配置（旁路）
     */
    fun attach(context: Context?, packageName: String?, taskHint: String? = null): StateProfile? {
        reset()
        val ctx = context ?: ShizukuManager.appContext()
        var p = if (ctx != null) StateMachineStore.forPackage(ctx, packageName) else null

        // 起始在桌面时靠任务文本猜：任务里提到「原神」就直接挂原神的状态机
        if (p == null && ctx != null && !taskHint.isNullOrBlank()) {
            p = StateMachineStore.profiles(ctx).firstOrNull {
                taskHint.contains(it.name) || taskHint.contains(it.id, ignoreCase = true)
            }
        }

        profile = p
        currentStateId = p?.initialState
        if (p != null) {
            Log.i(TAG, "挂载状态机 ${p.id}（${p.name}），初始状态 ${p.initialState}")
        } else {
            Log.i(TAG, "无状态机配置（包名 $packageName），旁路运行")
        }
        return p
    }

    fun reset() {
        profile = null
        snapshot = null
        currentStateId = null
        consecutiveNoMove = 0
        abnormalFlag = false
        moveHistory.clear()
    }

    fun currentProfile(): StateProfile? = profile

    fun current(): Snapshot? = snapshot

    fun isActive(): Boolean = profile != null

    /**
     * 识别当前状态并更新内部状态。
     *
     * @param packageName 当前前台包名
     * @param visibleTexts 当前界面可见文本（UI 树 text/contentDescription）
     * @param visionHintProvider UI 树文本太少时（游戏常见）调用，返回一句界面描述；
     *        通常内部去调本地 Qwen2-VL。返回 null 表示不可用。
     */
    suspend fun refresh(
        packageName: String?,
        visibleTexts: List<String>,
        visionHintProvider: (suspend () -> String?)? = null
    ): Snapshot {
        // 懒挂载：任务执行中途打开游戏时（起始前台是桌面），第一次识别到这里自动挂上
        if (profile == null) {
            val ctx = ShizukuManager.appContext()
            val auto = if (ctx != null) StateMachineStore.forPackage(ctx, packageName) else null
            if (auto == null) {
                return Snapshot(null, UNKNOWN, "未启用", 0f, false, false)
            }
            profile = auto
            currentStateId = auto.initialState
            Log.i(TAG, "自动挂载状态机 ${auto.id}（检测到前台 ${packageName}）")
        }
        val p = profile ?: return Snapshot(null, UNKNOWN, "未启用", 0f, false, false)

        // 1) 不在目标应用内 → 外部状态（算异常：可能用户切走了，或应用被系统杀了）
        if (!packageName.isNullOrBlank() && p.packageNames.isNotEmpty() &&
            p.packageNames.none { packageName == it || packageName.startsWith(it) }
        ) {
            val snap = Snapshot(
                profileId = p.id,
                stateId = FOREIGN,
                stateName = "外部应用($packageName)",
                confidence = 1f,
                abnormal = true,
                stable = false,
                reason = "前台已切到其他应用"
            )
            snapshot = snap
            abnormalFlag = true
            Log.w(TAG, "检测到前台离开目标应用：$packageName")
            return snap
        }

        // 2) UI 树文本打分
        var (state, score) = scoreByText(p, visibleTexts)
        var source = "UI树"

        // 3) 文本太少（游戏几乎读不到）→ 用本地视觉模型补一帧描述再打一次分
        if (state == null && visionHintProvider != null) {
            val hint = runCatching { visionHintProvider() }.getOrNull()
            if (!hint.isNullOrBlank()) {
                val (s2, sc2) = scoreByText(p, listOf(hint))
                if (s2 != null) {
                    state = s2
                    score = sc2
                    source = "本地视觉"
                }
            }
        }

        val resolvedId = state?.id
            ?: currentStateId?.takeIf { it != FOREIGN && it != UNKNOWN }
            ?: UNKNOWN
        val resolvedDef = p.state(resolvedId)
        val unknown = resolvedId == UNKNOWN

        val stuck = !unknown && consecutiveNoMove >= STUCK_THRESHOLD
        val snap = Snapshot(
            profileId = p.id,
            stateId = resolvedId,
            stateName = resolvedDef?.name ?: if (unknown) "未知界面" else resolvedId,
            confidence = if (state != null) score else 0.3f,
            abnormal = (resolvedDef?.abnormal == true) || stuck,
            stable = resolvedDef?.stable == true,
            stuck = stuck,
            reason = when {
                stuck -> "连续 $consecutiveNoMove 次动作画面无变化"
                resolvedDef?.abnormal == true -> "命中异常界面：${resolvedDef.name}"
                unknown -> "未能识别界面（$source）"
                else -> source
            }
        )
        snapshot = snap
        currentStateId = resolvedId
        if (snap.abnormal) abnormalFlag = true
        Log.i(
            TAG,
            "状态=$resolvedId(${snap.stateName}) conf=${"%.2f".format(score)} " +
                "stuck=$stuck abnormal=${snap.abnormal} src=$source"
        )
        return snap
    }

    /**
     * 用可见文本给每个状态打分，取最高分。
     *
     * 规则：
     * - 每个 keywords 命中 +1
     * - 每个 antiKeywords 命中 -2（用来排除「委托」这类跨页面重复词）
     * - 当前状态若是稳定态，额外 +0.5（抑制界面轻微变化导致的来回抖）
     *
     * @return (命中的状态, 得分)；都没有命中返回 (null, 0)
     */
    private fun scoreByText(p: StateProfile, texts: List<String>): Pair<StateDef?, Float> {
        if (texts.isEmpty()) return null to 0f
        val blob = texts.joinToString("\u0001")
        var best: StateDef? = null
        var bestScore = 0f
        for (s in p.states) {
            var score = 0f
            for (k in s.keywords) if (k.isNotBlank() && blob.contains(k, ignoreCase = true)) score += 1f
            for (k in s.antiKeywords) if (k.isNotBlank() && blob.contains(k, ignoreCase = true)) score -= 2f
            if (s.stable && s.id == currentStateId) score += 0.5f
            if (score > bestScore) {
                bestScore = score
                best = s
            }
        }
        return if (best != null && bestScore > 0f) best to bestScore else null to 0f
    }

    /**
     * 当前状态是否允许该工具。
     *
     * 放行条件（任一）：未挂载配置 / 状态未知 / 状态白名单为空 / 工具在逃逸名单。
     */
    fun allowTool(tool: String): Boolean {        val p = profile ?: return true
        val st = currentStateId ?: return true
        if (st == UNKNOWN || st == FOREIGN) return true
        val allowed = p.allowedToolsOf(st)
        if (allowed.isEmpty()) return true
        val t = tool.lowercase()
        return t in allowed || t in ALWAYS_ALLOWED
    }

    /** 当前状态允许的工具（给 prompt 用）；null = 不限制 */
    fun allowedToolsHint(): Set<String>? {
        val p = profile ?: return null
        val st = currentStateId ?: return null
        if (st == UNKNOWN || st == FOREIGN) return null
        val allowed = p.allowedToolsOf(st)
        return allowed.ifEmpty { null }
    }

    /**
     * 动作执行后调用：重新识别状态 + 判定是否按预期转移。
     *
     * @param before 动作前的快照（Executor 里 act 之前拿到的）
     * @param after 动作后的新快照
     * @param tool 工具名
     * @param args 工具参数
     * @param succeeded 工具是否报告成功
     */
    fun onAction(
        before: Snapshot,
        after: Snapshot,
        tool: String,
        args: Map<String, Any?>,
        succeeded: Boolean
    ): ActionOutcome {
        val p = profile ?: return ActionOutcome(null, after.stateId, false, false)
        val expected = p.expectedNext(before.stateId, tool, args)

        val moved = after.stateId != before.stateId
        // 「预期转移」有两种达成方式：① 到了配置写的目标态 ② 至少离开了原状态
        val matched = when {
            expected != null -> after.stateId.equals(expected, ignoreCase = true)
            else -> moved
        }

        if (matched) {
            consecutiveNoMove = 0
        } else {
            // 前提：只有「看得清」的时候才允许累计卡住计数。
            // 识别不到界面（游戏 UI 树为空、本地视觉模型没下载）时不计入，
            // 否则会误判卡住 → 触发恢复流程疯狂按返回键，反而毁掉任务。
            val confident = !before.unknown && !after.unknown &&
                before.confidence >= 0.5f && after.confidence >= 0.5f
            // wait / scroll 这类不改变界面的动作本来就会让状态不变，也不计
            val quiet = tool.lowercase() in setOf("wait", "scroll", "swipe")
            if (confident && !quiet) consecutiveNoMove++
        }

        moveHistory.addLast("${before.stateId} -[${tool}]-> ${after.stateId}")
        while (moveHistory.size > 8) moveHistory.removeFirst()

        val stuck = consecutiveNoMove >= STUCK_THRESHOLD
        if (stuck) {
            abnormalFlag = true
            Log.w(TAG, "判定卡住：连续 $consecutiveNoMove 次动作未推动状态")
        } else if (succeeded) {
            // 成功推动了状态 → 允许清掉一次历史异常（避免一次抖动永久标记）
            if (!after.abnormal) abnormalFlag = false
        }
        snapshot = after
        return ActionOutcome(expected, after.stateId, moved, stuck)
    }

    /** 外部显式标记异常（如恢复流程失败） */
    fun markAbnormal(reason: String) {
        abnormalFlag = true
        snapshot = snapshot?.copy(abnormal = true, reason = reason)
        Log.w(TAG, "标记异常：$reason")
    }

    /** 清掉异常标记（恢复成功后调用） */
    fun clearAbnormal() {
        abnormalFlag = false
        consecutiveNoMove = 0
        snapshot = snapshot?.copy(abnormal = false, stuck = false, reason = "已恢复")
    }

    /**
     * 恢复用的候选动作（按代价从小到大）：
     * 1) 返回键 —— 退回上一层界面
     * 2) 再返回一次 —— 处理双层弹窗
     * 3) 回主页 + 重新打开目标应用 —— 彻底重置到入口
     */
    fun recoveryPlan(packageName: String?, appLabel: String?): List<Pair<String, Map<String, Any?>>> {
        val plan = mutableListOf<Pair<String, Map<String, Any?>>>()
        plan.add("press_back" to emptyMap())
        plan.add("press_back" to emptyMap())
        if (!appLabel.isNullOrBlank()) {
            plan.add("press_home" to emptyMap())
            plan.add("open_app" to mapOf("appName" to appLabel))
            plan.add("wait" to mapOf("seconds" to 4))
        } else if (!packageName.isNullOrBlank()) {
            plan.add("open_app" to mapOf("packageName" to packageName))
            plan.add("wait" to mapOf("seconds" to 4))
        }
        return plan
    }

    /** 转移历史（诊断/上报用） */
    fun history(): List<String> = moveHistory.toList()

    /** 给 prompt 的一行状态描述 */
    fun describe(): String {
        val p = profile ?: return "状态机：未启用（通用模式）"
        val s = snapshot ?: return "状态机：${p.name}（尚未识别）"
        val allowed = allowedToolsHint()
        return buildString {
            append("状态机：${p.name} · 当前界面 = ${s.stateName}（${s.stateId}）")
            if (s.abnormal) append(" ⚠️${s.reason}")
            if (!allowed.isNullOrEmpty()) {
                append("\n本界面允许的工具：${allowed.joinToString(", ")}")
            }
        }
    }
}
