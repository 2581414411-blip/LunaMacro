package com.nexus.agent.speech

import android.content.Context
import android.util.Log

/**
 * 中文 → sherpa-onnx KWS 音素串。
 *
 * 唤醒词自定义的桥：KWS 模型要的是「声母 + 韵母（带调）」的音素串，例如
 * `n ǐ h ǎo h óng y uè @你好宏月`，而用户只会输入中文。
 *
 * 拼音表来自 `assets/pinyin.txt`（6762 个 GB2312 汉字，由 pypinyin 生成，
 * 已逐字校验声母/韵母都能在 `assets/kws/tokens.txt` 里找到，否则 KWS 会崩）。
 *
 * 已知局限：多音字取的是**最常用读音**（单字查表，没有上下文分词），
 * 例如「行」按 xíng 处理。转换结果会回显给用户确认，读错了换个写法即可。
 */
object PinyinConverter {

    private const val TAG = "PinyinConverter"
    private const val ASSET = "pinyin.txt"

    /** 唤醒词长度限制（字） */
    const val MIN_LEN = 2
    const val MAX_LEN = 6

    /** 声母表，**按长度降序**：zh/ch/sh 必须先于 z/c/s 匹配 */
    private val INITIALS = arrayOf(
        "zh", "ch", "sh",
        "b", "p", "m", "f", "d", "t", "n", "l",
        "g", "k", "h", "j", "q", "x", "r", "z", "c", "s", "y", "w"
    )

    @Volatile
    private var table: Map<Char, String>? = null

    /** 转换结果 */
    sealed class Result {
        /** @param keywords 可直接喂给 KWS 的音素串 */
        data class Ok(val keywords: String, val syllables: List<String>) : Result()

        /** @param message 给用户看的中文原因 */
        data class Err(val message: String) : Result()
    }

    /** 预加载拼音表（启动语音服务、进设置页时可提前调用，避免首次转换卡一下） */
    fun preload(context: Context) {
        ensureLoaded(context)
    }

    /**
     * 把中文唤醒词转成音素串。
     *
     * @return [Result.Ok] 带音素串与逐字拼音；[Result.Err] 带具体原因
     */
    fun toKeywords(context: Context, word: String): Result {
        val w = word.trim().replace(" ", "").replace("\u3000", "")
        if (w.isEmpty()) return Result.Err("先输入一个唤醒词")
        if (w.length < MIN_LEN) return Result.Err("至少 $MIN_LEN 个字，太短容易误唤醒")
        if (w.length > MAX_LEN) return Result.Err("最多 $MAX_LEN 个字，太长识别不过来")
        if (!w.all { it in '\u4e00'..'\u9fff' }) {
            return Result.Err("只支持汉字（字母、数字、符号识别不了）")
        }

        val map = ensureLoaded(context)
        if (map.isEmpty()) return Result.Err("拼音表没加载出来，重启 App 再试")

        val syllables = ArrayList<String>(w.length)
        val pinyins = ArrayList<String>(w.length)
        for (ch in w) {
            val py = map[ch]
            if (py == null) {
                return Result.Err("「$ch」不在字库里，换一个字试试")
            }
            pinyins.add(py)
            syllables.add(split(py) ?: return Result.Err("「$ch」的读音（$py）没法转成音素"))
        }

        val keywords = syllables.joinToString(" ") + " @" + w
        Log.i(TAG, "唤醒词转换：$w → $keywords")
        return Result.Ok(keywords, pinyins)
    }

    /** 拼音拆成 "声母 韵母"；零声母时只给韵母 */
    private fun split(pinyin: String): String? {
        if (pinyin.isBlank()) return null
        for (ini in INITIALS) {
            if (pinyin.length > ini.length && pinyin.startsWith(ini)) {
                return "$ini ${pinyin.substring(ini.length)}"
            }
        }
        // 零声母（如 ài / ān / ér）：整串就是韵母
        return pinyin
    }

    private fun ensureLoaded(context: Context): Map<Char, String> {
        table?.let { return it }
        synchronized(this) {
            table?.let { return it }
            val map = HashMap<Char, String>(8192)
            try {
                context.applicationContext.assets.open(ASSET)
                    .bufferedReader(Charsets.UTF_8)
                    .use { br ->
                        br.forEachLine { line ->
                            if (line.length < 3) return@forEachLine
                            val tab = line.indexOf('\t')
                            if (tab <= 0) return@forEachLine
                            map[line[0]] = line.substring(tab + 1).trim()
                        }
                    }
                Log.i(TAG, "拼音表已加载：${map.size} 字")
            } catch (e: Throwable) {
                Log.e(TAG, "拼音表加载失败", e)
            }
            table = map
            return map
        }
    }
}
