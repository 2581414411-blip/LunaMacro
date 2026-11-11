package com.nexus.agent.persona

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 会话情感快照 —— 「她昨晚睡前的心情」（0.9.15 阶段 0）。
 *
 * ## 为什么是文件而不是一张表
 *
 * 它没有检索需求：开机读**一条**，读完注入一次就作废。建表要多一次迁移、一个 DAO、
 * 一个实体，换来的「能查询」这个能力一次都用不上。所以落成
 * `filesDir/persona/diary/2026-11-11.json`，和 `FlexiMem` 的
 * `persona/archive/{day}.jsonl` 同构 —— 运维时进同一个目录就能看全部。
 *
 * ## 为什么自己写编解码，不用 org.json
 *
 * 项目里已经踩过：纯 JVM 单测里 `org.json` 是空壳（`isReturnDefaultValues = true`），
 * 一碰就 `not mocked`，而这玩意的编解码**必须**能被单测覆盖（它是冷启动的第一句话，
 * 坏了就是「她一早起来在念乱码」）。四个标量 + 一个数组 + 一段文本，手写 60 行够用。
 *
 * ## 两道时间闸（别混）
 *
 *  - **20 小时幂等闸** [shouldWrite]：防止「夜间定时」+「启动兜底」各写一次；
 *  - **24 小时时效闸** [latest]：超过一天的日记不再注入 —— 昨天的日记今天还念，
 *    比不念更假。
 */
data class Diary(
    /** `yyyy-MM-dd`，本地时区 */
    val day: String,
    /** 写入时刻 */
    val at: Long,
    /** 写这份日记时的快变量 */
    val quick: PAD,
    /** 写这份日记时的慢变量（「底色」） */
    val slow: PAD,
    /** 第一人称那一小段，≤ [TEXT_MAX] 字 */
    val text: String
) {
    companion object {

        const val VERSION = 1

        /** 正文硬上限：超了就不是「记忆碎片」，是作文 */
        const val TEXT_MAX = 200

        /** 幂等闸：20 小时内不再写第二份 */
        const val GAP_MS = 20L * 60 * 60 * 1000

        /** 时效闸：超过一天就不再注入 */
        const val FRESH_MS = 24L * 60 * 60 * 1000

        /** 扫目录时最多看几个文件（一天一个，14 个够覆盖两周） */
        private const val MAX_SCAN = 14

        // ------------------------------------------------------------ 编解码

        fun encode(d: Diary): String = buildString {
            append("{\"version\":").append(VERSION)
            append(",\"day\":\"").append(escape(d.day)).append('"')
            append(",\"at\":").append(d.at)
            append(",\"quick\":").append(triple(d.quick))
            append(",\"slow\":").append(triple(d.slow))
            append(",\"text\":\"").append(escape(d.text)).append('"')
            append('}')
        }

        /**
         * 解析。任何一处不对都返回 null —— 调用方拿到 null 就走兜底（重写一份），
         * 绝不让半个对象流进提示词。
         */
        fun decode(raw: String?): Diary? {
            val s = raw?.trim().orEmpty()
            if (s.isEmpty() || !s.startsWith("{")) return null
            val day = readString(s, "day") ?: return null
            val at = readLong(s, "at") ?: return null
            val quick = readTriple(s, "quick") ?: return null
            val slow = readTriple(s, "slow") ?: return null
            val text = readString(s, "text") ?: return null
            if (day.isBlank() || text.isBlank()) return null
            // 版本不认识就当没有：宁可重写一份，也不猜新格式
            val ver = readLong(s, "version") ?: 0L
            if (ver != VERSION.toLong()) return null
            return Diary(day, at, quick, slow, text)
        }

        // ------------------------------------------------------------ 文件

        /** `filesDir/persona/diary` */
        fun dirOf(filesDir: File): File = File(File(filesDir, "persona"), "diary")

        fun fileOf(dir: File, day: String): File = File(dir, "$day.json")

        fun write(dir: File, d: Diary): Boolean = runCatching {
            if (!dir.isDirectory) dir.mkdirs()
            // 先写临时文件再改名：写到一半被杀进程，也不会留下半个坏 JSON
            val tmp = File(dir, "${d.day}.json.tmp")
            tmp.writeText(encode(d), Charsets.UTF_8)
            val target = fileOf(dir, d.day)
            if (target.exists()) target.delete()
            tmp.renameTo(target)
        }.getOrDefault(false)

        fun read(dir: File, day: String): Diary? = runCatching {
            val f = fileOf(dir, day)
            if (!f.isFile) null else decode(f.readText(Charsets.UTF_8))
        }.getOrNull()

        /**
         * 最近一份、且不超过 [maxAgeMs] 的日记。
         *
         * 按文件名倒序扫（`yyyy-MM-dd` 的字典序就是时间序），**短路返回** ——
         * 正常情况下第一个文件就命中，不会读满整个目录。
         */
        fun latest(dir: File, nowMs: Long, maxAgeMs: Long = FRESH_MS): Diary? {
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return null
            return files.asSequence()
                .sortedByDescending { it.name }
                .take(MAX_SCAN)
                .mapNotNull { f -> runCatching { decode(f.readText(Charsets.UTF_8)) }.getOrNull() }
                .firstOrNull { nowMs - it.at <= maxAgeMs }
        }

        /** 幂等闸：距上一份日记不足 [gapMs] 就不该再写 */
        fun shouldWrite(dir: File, nowMs: Long, gapMs: Long = GAP_MS): Boolean {
            val last = latest(dir, nowMs, Long.MAX_VALUE) ?: return true
            return nowMs - last.at >= gapMs
        }

        /** 「清空聊天」时连带清掉 —— 界面空了但她还念着昨天，是最吓人的那种不一致 */
        fun clear(dir: File): Int {
            val files = dir.listFiles() ?: return 0
            var n = 0
            files.forEach { if (it.isFile && it.delete()) n++ }
            return n
        }

        // ------------------------------------------------------------ 工具

        /** `yyyy-MM-dd`（本地时区）。用局部 `SimpleDateFormat`，它本身不是线程安全的。 */
        fun dayKey(atMs: Long, locale: Locale = Locale.US): String =
            SimpleDateFormat("yyyy-MM-dd", locale).format(Date(atMs))

        /**
         * 没配 Key / 超时时的规则兜底 —— **绝不允许空档**（合伙人 2026-11-11 拍板）。
         *
         * 它读起来是一句人话，但其实是两段模板拼的：心情短语 + 未完的事。
         * 不需要模型，也就不会失败。
         *
         * ## 为什么心情短语不复用八分区
         *
         * 八分区是给**提示词**用的标签（「焦躁」「敌意」「消沉」），分类清楚但不好听。
         * 直接塞进第一人称日记会出这种东西：
         *
         * ```
         * 今天心里有点敌意。          ← 半夜读到自己这句话，比没有日记更吓人
         * 今天心里很焦躁。            ← 像是在写病历
         * ```
         *
         * 所以这里另起一套五档口语（见 [moodPhraseOf]）：它只回答「今天心里是什么滋味」，
         * 不承担分类职责 —— 日记本来就是写给人读的，不是写给路由器读的。
         */
        fun template(slow: PAD, quick: PAD, unclosed: List<String>): String {
            val base = if (slow.isNeutral()) quick else slow
            val mood = moodPhraseOf(base)
            val tail = if (unclosed.isEmpty()) {
                "没什么特别的，就这么过去了。"
            } else {
                "他还提了「${unclosed.first().take(24)}」，我没往下问。"
            }
            return "今天心里$mood。$tail"
        }

        /**
         * 五档口语化心情（配合「今天心里」用，所以每档都是能直接接在后面的短语）。
         *
         * 分档只看两件事：**愉不愉说**（P 的正负）和**有没有劲**（A 的高低）。
         * D 轴故意不参与 —— 「有底气」和「服软」在一句睡前日记里读不出区别，
         * 多一个维度只会让文案更难挑。
         */
        internal fun moodPhraseOf(base: PAD): String = when {
            base.isNeutral() -> "挺平静的"
            // 0.10 是「能感觉出来、但还说不清」的门槛：低于它就说「说不上什么滋味」，
            // 高于它就得给一个具体说法 —— 情绪已经在了，糊过去反而不像人
            base.p >= 0.10f && base.a >= 0.10f -> "挺轻快的"
            base.p >= 0.10f -> "挺安稳的"
            base.p <= -0.10f && base.a >= 0.10f -> "有点烦"
            base.p <= -0.10f -> "有点发沉"
            base.a >= 0.20f -> "有点静不下来"
            base.a <= -0.20f -> "有点提不起劲"
            else -> "说不上什么滋味"
        }

        // ------------------------------------------------------------ JSON 小工具

        private fun escape(s: String): String = buildString(s.length + 8) {
            s.forEach { c ->
                when (c) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (c.code < 0x20) append(' ') else append(c)
                }
            }
        }

        private fun triple(v: PAD): String = buildString {
            append('[').append(num(v.p)).append(',')
                .append(num(v.a)).append(',')
                .append(num(v.d)).append(']')
        }

        private fun num(v: Float): String = String.format(Locale.US, "%.3f", v)

        /** 找到 `"key"` 后跳过冒号与空白，返回值的起点下标 */
        private fun valueStart(raw: String, key: String): Int {
            val k = "\"$key\""
            var i = raw.indexOf(k)
            if (i < 0) return -1
            i += k.length
            while (i < raw.length && raw[i] != ':') i++
            if (i >= raw.length) return -1
            i++
            while (i < raw.length && raw[i].isWhitespace()) i++
            return if (i >= raw.length) -1 else i
        }

        private fun readString(raw: String, key: String): String? {
            var i = valueStart(raw, key)
            if (i < 0 || raw[i] != '"') return null
            i++
            val sb = StringBuilder()
            while (i < raw.length) {
                val c = raw[i]
                if (c == '\\' && i + 1 < raw.length) {
                    when (raw[i + 1]) {
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        else -> sb.append(raw[i + 1])
                    }
                    i += 2
                    continue
                }
                if (c == '"') return sb.toString()
                sb.append(c)
                i++
            }
            return null
        }

        private fun readLong(raw: String, key: String): Long? {
            val i = valueStart(raw, key)
            if (i < 0) return null
            var j = i
            if (j < raw.length && (raw[j] == '-' || raw[j] == '+')) j++
            while (j < raw.length && raw[j].isDigit()) j++
            if (j == i) return null
            return raw.substring(i, j).toLongOrNull()
        }

        private fun readTriple(raw: String, key: String): PAD? {
            val i = valueStart(raw, key)
            if (i < 0 || raw[i] != '[') return null
            val end = raw.indexOf(']', i)
            if (end < 0) return null
            val parts = raw.substring(i + 1, end).split(',')
            if (parts.size != 3) return null
            val p = parts[0].trim().toFloatOrNull() ?: return null
            val a = parts[1].trim().toFloatOrNull() ?: return null
            val d = parts[2].trim().toFloatOrNull() ?: return null
            return PAD(p, a, d).clamp()
        }
    }
}
