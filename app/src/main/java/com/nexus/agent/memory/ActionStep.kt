package com.nexus.agent.memory

/**
 * 一条**结构化**动作（0.9.13 外挂记忆）。
 *
 * ## 为什么不用 Executor 现成的 trace
 *
 * `Executor` 每步都在拼 `观察(界面:xxx) → 执行(click_text:搜索) → 结果(变化:是)`——
 * 那是**给人看的**，拿去回放是不行的：正则一断就什么都点不了。
 * 回放要的是「调哪个工具、传什么参数」，所以这里单独拎一个结构出来。
 *
 * ## 为什么不用 org.json 序列化
 *
 * 这一层的解析要能被纯 JVM 单测覆盖（`ActionStepTest`），
 * 而单测环境里的 `org.json` 是个空壳（只有方法签名没有实现），一调就抛。
 * 所以编码格式自己定：一行一步，字段用 `\t` 分隔，值做最少的转义。
 *
 * 格式：
 * ```
 * click_text\ttext=搜索
 * click_text\tindex=0\ttext=订阅号
 * ```
 */
data class ActionStep(
    val tool: String,
    val args: Map<String, String> = emptyMap()
) {

    /**
     * 「参数化」后的签名。
     *
     * 数字统一变成 `#`：`3 条未读` 和 `12 条未读` 是同一步，不该算两种习惯。
     * **注意只归一纯数字，不归一文字**——把「张三」抽象成「某人」听着聪明，
     * 实际后果是回放时点到隔壁老李的聊天框里。宁可不会做，不能做错。
     */
    fun paramKey(): String {
        val sb = StringBuilder(tool)
        sb.append('{')
        var first = true
        for ((k, v) in args.entries.sortedBy { it.key }) {
            if (!first) sb.append(',')
            first = false
            sb.append(k).append('=').append(normalizeParam(v))
        }
        sb.append('}')
        return sb.toString()
    }

    /** 序列化成一行 */
    fun encode(): String {
        val sb = StringBuilder(tool)
        for ((k, v) in args.entries.sortedBy { it.key }) {
            sb.append(SEP).append(k).append('=').append(escape(v))
        }
        return sb.toString()
    }

    companion object {

        /** 字段分隔符（Tab，正常 UI 文案里不会出现） */
        private const val SEP = '\t'

        /** 参数值最长保留多少字（再长就不是「点哪个按钮」，是整段描述） */
        private const val VALUE_MAX = 40

        /**
         * 从工具调用的原始参数构造。
         *
         * 只保留 JSON 基本类型的字符串形式；null 直接丢（回放时也不需要）。
         */
        fun of(tool: String, raw: Map<String, Any?>): ActionStep {
            val m = LinkedHashMap<String, String>()
            for ((k, v) in raw) {
                if (v == null) continue
                val s = v.toString()
                if (s.isBlank()) continue
                m[k] = s.take(VALUE_MAX)
            }
            return ActionStep(tool.lowercase().trim(), m)
        }

        /** 一批步骤 → 落库字符串 */
        fun encodeAll(steps: List<ActionStep>): String =
            steps.joinToString("\n") { it.encode() }

        /**
         * 落库字符串 → 步骤列表。
         *
         * 坏行直接跳过（宁可少回放一步，也不要拿半条记忆去点屏幕）。
         */
        fun decodeAll(text: String?): List<ActionStep> {
            if (text.isNullOrBlank()) return emptyList()
            val out = ArrayList<ActionStep>(8)
            for (line in text.lineSequence()) {
                if (line.isBlank()) continue
                val parts = line.split(SEP)
                val tool = parts.firstOrNull()?.trim().orEmpty()
                if (tool.isEmpty()) continue
                val args = LinkedHashMap<String, String>()
                for (i in 1 until parts.size) {
                    val p = parts[i]
                    if (p.isEmpty()) continue
                    val eq = p.indexOf('=')
                    if (eq <= 0) continue
                    args[p.substring(0, eq)] = unescape(p.substring(eq + 1))
                }
                out.add(ActionStep(tool, args))
            }
            return out
        }

        /** 数字 → `#`；压掉连续空白；截断 */
        fun normalizeParam(raw: String): String {
            val s = raw.trim().replace(Regex("\\s+"), " ")
            if (s.isEmpty()) return ""
            return s.replace(Regex("\\d+"), "#").take(VALUE_MAX)
        }

        /**
         * 两个步骤是不是「同一步」。
         *
         * 用参数化签名比，所以计数变化、空白差异都算同一步；
         * 文字不同（点了别的按钮）一定算不同步 —— 这是安全底线。
         */
        fun same(a: ActionStep, b: ActionStep): Boolean = a.paramKey() == b.paramKey()

        private fun escape(s: String): String {
            val sb = StringBuilder(s.length + 8)
            for (c in s) {
                when (c) {
                    '\\' -> sb.append("\\\\")
                    '\t' -> sb.append("\\t")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }

        private fun unescape(s: String): String {
            if (s.indexOf('\\') < 0) return s
            val sb = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (s[i + 1]) {
                        '\\' -> { sb.append('\\'); i += 2 }
                        't' -> { sb.append('\t'); i += 2 }
                        'n' -> { sb.append('\n'); i += 2 }
                        'r' -> { sb.append('\r'); i += 2 }
                        else -> { sb.append(c); i++ }
                    }
                } else {
                    sb.append(c)
                    i++
                }
            }
            return sb.toString()
        }
    }
}
