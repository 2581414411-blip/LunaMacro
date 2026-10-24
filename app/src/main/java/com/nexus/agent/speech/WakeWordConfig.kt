package com.nexus.agent.speech

import android.content.Context

/**
 * 唤醒词配置（预置音素 + 阈值）。
 *
 * 预置唤醒词的音素串来自 sherpa-onnx 官方 keywords.txt（已验证）。
 * 自定义唤醒词需要「中文 → 音素」转换，目前只支持从预置里选。
 */
object WakeWordConfig {

    private const val PREFS = "voice_assistant"
    private const val KEY_WAKE_WORD = "wake_word"
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

    /** 读取当前唤醒词（中文名） */
    fun getWakeWord(context: Context): String {
        val w = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_WAKE_WORD, DEFAULT_WAKE_WORD)
        return if (PRESET.containsKey(w)) w!! else DEFAULT_WAKE_WORD
    }

    /** 读取当前唤醒词的音素串 */
    fun getKeywords(context: Context): String =
        PRESET[getWakeWord(context)] ?: PRESET.getValue(DEFAULT_WAKE_WORD)

    /** 读取触发阈值 */
    fun getThreshold(context: Context): Float =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(KEY_THRESHOLD, DEFAULT_THRESHOLD)

    /** 设置唤醒词（中文名） */
    fun setWakeWord(context: Context, word: String) {
        if (PRESET.containsKey(word)) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_WAKE_WORD, word).apply()
        }
    }

    /** 设置触发阈值（0.2~0.95，越高越严格） */
    fun setThreshold(context: Context, threshold: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_THRESHOLD, threshold.coerceIn(0.2f, 0.95f)).apply()
    }
}
