package com.nexus.agent.habit

/**
 * 一条「他做过什么」的原始记录（0.9.11）。
 *
 * 刻意只留六列：**这里存的是行为，不是内容**。
 * 没有输入文本、没有截图、没有聊天记录、没有联系人 —— 那些字段根本没定义，
 * 想加也加不进来（要加就得先改这个类的结构，等于强制过一次评审）。
 *
 * 序列化手写不用 org.json：`isReturnDefaultValues = true` 的纯 JVM 单测里
 * `JSONObject` 是个空壳，用了就测不了（0.8.0 抽记忆时踩过同一个坑）。
 */
data class HabitEvent(
    /** 发生时刻（毫秒） */
    val at: Long,
    /** 包名 */
    val pkg: String,
    /** 应用显示名（查不到时退化成包名短名） */
    val app: String,
    /** [KIND_OPEN] 进入界面 / [KIND_TAP] 点了一下 */
    val kind: String,
    /** 界面标题，可空 */
    val screen: String = "",
    /** 控件标签，已脱敏，可空 */
    val label: String = ""
) {

    /** 落盘用的一行 JSON */
    fun toJson(): String = buildString {
        append('{')
        append("\"at\":").append(at).append(',')
        append("\"pkg\":\"").append(esc(pkg)).append("\",")
        append("\"app\":\"").append(esc(app)).append("\",")
        append("\"kind\":\"").append(esc(kind)).append("\",")
        append("\"screen\":\"").append(esc(screen)).append("\",")
        append("\"label\":\"").append(esc(label)).append('"')
        append('}')
    }

    companion object {
        /** 进入了一个新界面 */
        const val KIND_OPEN = "open"

        /** 点了一个控件 */
        const val KIND_TAP = "tap"

        /** 解析一行；坏行返回 null（宁可少一条，不要为一行脏数据炸掉整天） */
        fun fromJson(line: String?): HabitEvent? {
            val s = line?.trim().orEmpty()
            if (s.length < 2 || !s.startsWith("{") || !s.endsWith("}")) return null
            val map = runCatching { parseFlat(s) }.getOrNull() ?: return null
            val at = map["at"]?.toLongOrNull() ?: return null
            val pkg = map["pkg"].orEmpty()
            if (pkg.isEmpty()) return null
            val kind = map["kind"].orEmpty().ifEmpty { KIND_OPEN }
            return HabitEvent(
                at = at,
                pkg = pkg,
                app = map["app"].orEmpty().ifEmpty { HabitPrivacy.shortPkg(pkg) },
                kind = kind,
                screen = map["screen"].orEmpty(),
                label = map["label"].orEmpty()
            )
        }

        /** 只支持「扁平对象 + 字符串/数字」，够用了；不自称通用解析器 */
        private fun parseFlat(s: String): Map<String, String> {
            val out = HashMap<String, String>(8)
            var i = 1
            val end = s.length - 1
            while (i < end) {
                while (i < end && (s[i] == ',' || s[i].isWhitespace())) i++
                if (i >= end) break
                if (s[i] != '"') return out
                val key = readString(s, i).also { i = it.second }.first
                while (i < end && (s[i].isWhitespace() || s[i] == ':')) i++
                val (value, next) = if (i < end && s[i] == '"') {
                    readString(s, i)
                } else {
                    val start = i
                    while (i < end && s[i] != ',') i++
                    s.substring(start, i).trim() to i
                }
                i = next
                out[key] = value
            }
            return out
        }

        private fun readString(s: String, from: Int): Pair<String, Int> {
            val sb = StringBuilder()
            var i = from + 1
            while (i < s.length) {
                when (val c = s[i]) {
                    '\\' -> {
                        i++
                        if (i >= s.length) break
                        when (val e = s[i]) {
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            'u' -> {
                                if (i + 4 < s.length) {
                                    val hex = s.substring(i + 1, i + 5)
                                    hex.toIntOrNull(16)?.let { sb.append(it.toChar()) }
                                    i += 4
                                }
                            }
                            else -> sb.append(e)
                        }
                        i++
                    }
                    '"' -> return sb.toString() to (i + 1)
                    else -> {
                        sb.append(c)
                        i++
                    }
                }
            }
            return sb.toString() to i
        }

        private fun esc(v: String): String {
            if (v.isEmpty()) return ""
            val sb = StringBuilder(v.length + 8)
            for (c in v) {
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> if (c < ' ') sb.append(' ') else sb.append(c)
                }
            }
            return sb.toString()
        }
    }
}
