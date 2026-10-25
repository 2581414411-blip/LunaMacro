package com.nexus.agent.agent.state

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一个界面状态的定义。
 *
 * 状态是「屏幕处于什么界面」的抽象（主界面 / 委托界面 / 战斗界面 / 加载中），
 * 而不是具体某个按钮。状态决定「这个界面下允许做哪些动作」，
 * 这是状态机约束的核心价值：把动作空间从「所有工具」收窄到「当前界面合理的几个」。
 */
data class StateDef(
    /** 状态 id（大写英文，配置文件里唯一） */
    val id: String,
    /** 展示名（中文，进入日志/提示） */
    val name: String,
    /** 命中这些文本 → 认为处于该状态（任一命中即加分） */
    val keywords: List<String> = emptyList(),
    /** 命中这些文本 → 排除该状态（防止「委托」这种词在多个页面出现时误判） */
    val antiKeywords: List<String> = emptyList(),
    /** 该状态允许的工具名；空列表 = 不限制 */
    val allowedTools: List<String> = emptyList(),
    /** 是否稳定态（恢复流程会尝试回到最近的稳定态） */
    val stable: Boolean = false,
    /** 是否异常态（如网络异常/掉线/充值弹窗） */
    val abnormal: Boolean = false
)

/**
 * 一条状态转移：在 [from] 状态下执行 [action]，预期到达 [to]。
 *
 * [action] 写法为 `工具名` 或 `工具名:参数关键词`，例如：
 * - `press_back`            → 任何 press_back
 * - `click_text:委托`        → click_text 且参数里含「委托」
 */
data class StateTransition(val from: String, val action: String, val to: String)

/**
 * 一个应用/游戏的状态机配置。
 *
 * 存在 `assets/state_profiles/{id}.json`，也可被 `filesDir/state_profiles/` 下的同名文件覆盖。
 * 新增支持的应用 = 加一个 json，不用改代码。
 */
data class StateProfile(
    val id: String,
    val name: String,
    /** 归属包名（支持前缀匹配，如 com.miHoYo.Yuanshen 也匹配 .bilibili 渠道包） */
    val packageNames: List<String> = emptyList(),
    /** 初始状态 id（识别不出来时的兜底） */
    val initialState: String,
    val states: List<StateDef> = emptyList(),
    val transitions: List<StateTransition> = emptyList(),
    /** 命中这些文本说明子目标可能已达成（如「已领取」「任务完成」） */
    val successKeywords: List<String> = emptyList()
) {

    fun state(id: String?): StateDef? =
        if (id == null) null else states.firstOrNull { it.id.equals(id, ignoreCase = true) }

    fun stateByName(name: String?): StateDef? =
        if (name == null) null else states.firstOrNull {
            it.name == name || it.id.equals(name, ignoreCase = true)
        }

    /** 期望转移：给定当前状态 + 工具 + 参数，返回目标状态 id（无定义返回 null） */
    fun expectedNext(from: String, tool: String, args: Map<String, Any?>): String? {
        val argText = args.entries.joinToString(" ") { "${it.key}=${it.value}" }
        return transitions.firstOrNull { t ->
            t.from.equals(from, ignoreCase = true) && matchAction(t.action, tool, argText)
        }?.to
    }

    /** 该状态允许的工具；空列表表示不限制 */
    fun allowedToolsOf(stateId: String?): Set<String> {
        val s = state(stateId) ?: return emptySet()
        return s.allowedTools.map { it.lowercase() }.toSet()
    }

    private fun matchAction(action: String, tool: String, argText: String): Boolean {
        val parts = action.split(':', limit = 2)
        val name = parts[0].trim().lowercase()
        if (name != tool.trim().lowercase()) return false
        if (parts.size == 1) return true
        val kw = parts[1].trim()
        return kw.isEmpty() || argText.contains(kw, ignoreCase = true)
    }

    companion object {
        private const val TAG = "StateProfile"

        /**
         * 解析 JSON 配置。
         *
         * 容错：缺字段用默认值；states/transitions 缺失视为空。解析失败返回 null（上层旁路）。
         */
        fun parse(json: String): StateProfile? = try {
            val o = JSONObject(json.trim().removePrefix("\uFEFF"))
            val id = o.optString("id").trim()
            if (id.isEmpty()) {
                null
            } else {
                StateProfile(
                    id = id,
                    name = o.optString("name", id).ifBlank { id },
                    packageNames = o.optJSONArray("packageNames").toStringList(),
                    initialState = o.optString("initial", "").ifBlank {
                        o.optJSONArray("states")
                            ?.optJSONObject(0)
                            ?.optString("id")
                            .orEmpty()
                    },
                    states = o.optJSONArray("states").toStates(),
                    transitions = o.optJSONArray("transitions").toTransitions(),
                    successKeywords = o.optJSONArray("successKeywords").toStringList()
                )
            }
        } catch (e: Throwable) {
            Log.w(TAG, "状态机配置解析失败：${e.message}")
            null
        }

        private fun JSONArray?.toStringList(): List<String> {
            if (this == null) return emptyList()
            val out = ArrayList<String>(length())
            for (i in 0 until length()) {
                val s = optString(i).trim()
                if (s.isNotEmpty()) out.add(s)
            }
            return out
        }

        private fun JSONArray?.toStates(): List<StateDef> {
            if (this == null) return emptyList()
            val out = ArrayList<StateDef>(length())
            for (i in 0 until length()) {
                val s = optJSONObject(i) ?: continue
                val sid = s.optString("id").trim()
                if (sid.isEmpty()) continue
                out.add(
                    StateDef(
                        id = sid,
                        name = s.optString("name", sid).ifBlank { sid },
                        keywords = s.optJSONArray("keywords").toStringList(),
                        antiKeywords = s.optJSONArray("antiKeywords").toStringList(),
                        allowedTools = s.optJSONArray("allowedTools").toStringList(),
                        stable = s.optBoolean("stable", false),
                        abnormal = s.optBoolean("abnormal", false)
                    )
                )
            }
            return out
        }

        private fun JSONArray?.toTransitions(): List<StateTransition> {
            if (this == null) return emptyList()
            val out = ArrayList<StateTransition>(length())
            for (i in 0 until length()) {
                val t = optJSONObject(i) ?: continue
                val from = t.optString("from").trim()
                val action = t.optString("action").trim()
                val to = t.optString("to").trim()
                if (from.isEmpty() || action.isEmpty() || to.isEmpty()) continue
                out.add(StateTransition(from, action, to))
            }
            return out
        }
    }
}
