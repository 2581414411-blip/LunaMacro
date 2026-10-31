package com.nexus.agent.speech

import android.content.Context
import android.util.Log

/**
 * 唤醒词配置（预置音素 + 自定义 + 阈值）。
 *
 * 预置唤醒词的音素串来自 sherpa-onnx 官方 keywords.txt（已验证）。
 * 0.7.9 起支持**自定义**：用户输入中文，由 [PinyinConverter] 转成音素串后，
 * 连同中文一起存进 prefs（`wake_word` + `wake_keywords`）。
 *
 * 注意：KWS 的 keywords 只在 `init()` 时生效，改完必须**重新开启唤醒**。
 */
object WakeWordConfig {

    private const val TAG = "WakeWordConfig"
    private const val PREFS = "voice_assistant"
    private const val KEY_WAKE_WORD = "wake_word"
    private const val KEY_WAKE_KEYWORDS = "wake_keywords"
    private const val KEY_THRESHOLD = "wake_threshold"

    /** 预置唤醒词（中文 → 音素串） */
    val PRESET: Map<String, String> = linkedMapOf(
        "你好高总" to "n ǐ h ǎo g āo z ǒng @你好高总",
        "小爱同学" to "x iǎo ài t óng x ué @小爱同学",
        "小艺小艺" to "x iǎo y ì x iǎo y ì @小艺小艺",
        "你好军哥" to "n ǐ h ǎo j ūn g ē @你好军哥",
    )

    const val DEFAULT_WAKE_WORD = "你好高总"
    const val DEFAULT_THRESHOLD = 0.6f

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 读取当前唤醒词（中文名，可能是自定义的） */
    fun getWakeWord(context: Context): String {
        val w = prefs(context).getString(KEY_WAKE_WORD, DEFAULT_WAKE_WORD)
        if (w.isNullOrBlank()) return DEFAULT_WAKE_WORD
        if (PRESET.containsKey(w)) return w
        // 自定义词：必须同时有对应的音素串，否则视为脏数据回默认
        val custom = prefs(context).getString(KEY_WAKE_KEYWORDS, null)
        return if (!custom.isNullOrBlank() && custom.endsWith("@$w")) w else DEFAULT_WAKE_WORD
    }

    /** 读取当前唤醒词的音素串 */
    fun getKeywords(context: Context): String {
        val word = getWakeWord(context)
        PRESET[word]?.let { return it }
        val custom = prefs(context).getString(KEY_WAKE_KEYWORDS, null)
        if (!custom.isNullOrBlank() && custom.endsWith("@$word")) return custom
        return PRESET.getValue(DEFAULT_WAKE_WORD)
    }

    /** 当前唤醒词是不是自定义的 */
    fun isCustom(context: Context): Boolean = !PRESET.containsKey(getWakeWord(context))

    /** 读取触发阈值 */
    fun getThreshold(context: Context): Float =
        prefs(context).getFloat(KEY_THRESHOLD, DEFAULT_THRESHOLD)

    /** 选择预置唤醒词（顺手清掉自定义音素，避免残留） */
    fun setWakeWord(context: Context, word: String) {
        if (!PRESET.containsKey(word)) {
            Log.w(TAG, "忽略未知预置唤醒词：$word")
            return
        }
        prefs(context).edit()
            .putString(KEY_WAKE_WORD, word)
            .remove(KEY_WAKE_KEYWORDS)
            .apply()
    }

    /**
     * 设置自定义唤醒词。
     *
     * @param word     中文唤醒词（如「你好宏月」）
     * @param keywords 由 [PinyinConverter] 生成的音素串
     */
    fun setCustomWakeWord(context: Context, word: String, keywords: String) {
        if (word.isBlank() || keywords.isBlank()) return
        prefs(context).edit()
            .putString(KEY_WAKE_WORD, word)
            .putString(KEY_WAKE_KEYWORDS, keywords)
            .apply()
        Log.i(TAG, "自定义唤醒词已保存：$word")
    }

    /** 设置触发阈值（0.2~0.95，越高越严格） */
    fun setThreshold(context: Context, threshold: Float) {
        prefs(context).edit().putFloat(KEY_THRESHOLD, threshold.coerceIn(0.2f, 0.95f)).apply()
    }
}
