package com.nexus.agent.util

/**
 * 极小的 JSON 解析器（手写递归下降，零依赖）。
 *
 * ## 为什么不用 `org.json`
 *
 * 这个类服务的都是「模型输出解析」这种**最需要单测**的地方，而纯 JVM 单测里
 * `android.jar` 的 `org.json` 是空壳（所有方法返回默认值），`JSONObject(...)`
 * 解析出来永远是空对象——测试等于没测。手写一份 100 行的解析器，
 * 换来的是「解析逻辑可以在 `gradlew test` 里被真实验证」。
 *
 * ## 支持范围
 *
 * 只实现模型实际会吐的那部分：对象、数组、字符串（含常见转义）、数字、布尔、null。
 * 不处理注释、不处理单引号、不处理尾逗号——真遇到就当解析失败，由调用方降级。
 */
object MiniJson {

    /**
     * 从任意文本里薅出第一个 JSON 对象。
     *
     * 容忍模型加的 ```json 围栏和前后废话：从第一个 `{` 扫到与之配对的 `}`，
     * 中间即使夹着字符串里的花括号也不会截错。
     *
     * @return 解析成功返回 Map；找不到 / 语法错误返回 null
     */
    fun parseObject(text: String): Map<String, Any?>? {
        if (text.isBlank()) return null
        val start = text.indexOf('{')
        if (start < 0) return null
        val parser = Parser(text, start)
        val v = runCatching { parser.parseValue() }.getOrNull()
        @Suppress("UNCHECKED_CAST")
        return v as? Map<String, Any?>
    }

    /** 解析一个数组（同样容忍围栏与废话） */
    fun parseArray(text: String): List<Any?>? {
        if (text.isBlank()) return null
        val start = text.indexOf('[')
        if (start < 0) return null
        val parser = Parser(text, start)
        val v = runCatching { parser.parseValue() }.getOrNull()
        @Suppress("UNCHECKED_CAST")
        return v as? List<Any?>
    }

    /** 取字符串字段，顺带 trim；缺失或类型不对返回 null */
    fun str(map: Map<String, Any?>?, key: String): String? =
        (map?.get(key) as? String)?.trim()?.takeIf { it.isNotEmpty() }

    /** 取对象字段 */
    @Suppress("UNCHECKED_CAST")
    fun obj(map: Map<String, Any?>?, key: String): Map<String, Any?>? = map?.get(key) as? Map<String, Any?>

    private class Parser(private val s: String, private var i: Int) {

        fun parseValue(): Any? {
            skipWs()
            if (i >= s.length) error("eof")
            return when (val c = s[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                else -> {
                    if (c == '-' || c.isDigit()) parseNumber()
                    else error("unexpected '$c' at $i")
                }
            }
        }

        private fun parseObject(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            i++ // {
            skipWs()
            if (i < s.length && s[i] == '}') { i++; return out }
            while (i < s.length) {
                skipWs()
                if (i >= s.length || s[i] != '"') error("key expected at $i")
                val k = parseString()
                skipWs()
                if (i >= s.length || s[i] != ':') error("':' expected at $i")
                i++
                out[k] = parseValue()
                skipWs()
                if (i >= s.length) error("eof in object")
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return out }
                    else -> error("',' or '}' expected at $i")
                }
            }
            error("unterminated object")
        }

        private fun parseArray(): List<Any?> {
            val out = ArrayList<Any?>()
            i++ // [
            skipWs()
            if (i < s.length && s[i] == ']') { i++; return out }
            while (i < s.length) {
                out.add(parseValue())
                skipWs()
                if (i >= s.length) error("eof in array")
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return out }
                    else -> error("',' or ']' expected at $i")
                }
            }
            error("unterminated array")
        }

        private fun parseString(): String {
            val sb = StringBuilder()
            i++ // 起始引号
            while (i < s.length) {
                when (val c = s[i]) {
                    '"' -> { i++; return sb.toString() }
                    '\\' -> {
                        i++
                        if (i >= s.length) error("bad escape")
                        when (val e = s[i]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 >= s.length) error("bad unicode escape")
                                val hex = s.substring(i + 1, i + 5)
                                sb.append(hex.toInt(16).toChar())
                                i += 4
                            }
                            else -> sb.append(e)
                        }
                        i++
                    }
                    else -> { sb.append(c); i++ }
                }
            }
            error("unterminated string")
        }

        private fun parseNumber(): Any {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' ||
                    s[i] == '+' || s[i] == '-')
            ) i++
            val raw = s.substring(start, i)
            if (raw.contains('.') || raw.contains('e') || raw.contains('E')) {
                return raw.toDouble()
            }
            // 整数能给 Long 就给 Long：工具里统一按 Number 取，不会因为类型窄化失败
            return raw.toLongOrNull() ?: raw.toDouble()
        }

        private fun expect(word: String) {
            if (!s.startsWith(word, i)) error("'$word' expected at $i")
            i += word.length
        }

        private fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun error(msg: String): Nothing = throw IllegalArgumentException("MiniJson: $msg")
    }
}
