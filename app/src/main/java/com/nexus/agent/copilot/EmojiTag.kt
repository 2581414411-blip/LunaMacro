package com.nexus.agent.copilot

/**
 * 表情标签（0.9.6）。
 *
 * ## 为什么不直接让模型吐 emoji
 *
 * 试过：模型要么把 😭 混在中文里让整句变宽两倍，要么在部分模型上吐出裸代理对
 * （`\uD83D` 单独一半），渲染出来是个方框。**用文字标签描述情绪、由代码换成 emoji**
 * 才能保证不同模型、不同字体下都长得一样，也让「她用了什么表情」可统计。
 *
 * ## 只认白名单
 *
 * 不白名单的话，模型会写出 `[你的名字]`、`[图片]`、`[系统提示]` 这种把它当变量的东西，
 * 或者干脆 `[1]`。白名单之外的原样保留成文字，宁可露出一串方括号，也不乱替换。
 */
object EmojiTag {

    /** 标签 → emoji。键都是两三个字的情绪词，用户在提示词里看到的也是这些 */
    val MAP: Map<String, String> = linkedMapOf(
        "大哭" to "😭",
        "狗头" to "🐶",
        "贴贴" to "🥰",
        "笑死" to "😂",
        "破防" to "😵",
        "无语" to "😑",
        "委屈" to "🥺",
        "生气" to "😤",
        "得意" to "😎",
        "害羞" to "😳",
        "困" to "😪",
        "爱心" to "❤️",
        "鼓掌" to "👏",
        "思考" to "🤔",
        "白眼" to "🙄",
        "加油" to "💪",
        "摸摸头" to "🤗",
        "震惊" to "😱",
        "小声" to "🤫",
        "干杯" to "🍻",
        "吃瓜" to "🍉",
        "裂开" to "💔",
        "累了" to "🫠",
        "偷笑" to "🤭",
        "叹气" to "😮‍💨",
        "问号" to "❓"
    )

    /** 提示词里给模型看的可用标签清单 */
    fun tagsHint(): String = MAP.keys.joinToString(" ") { "[$it]" }

    /**
     * 提示词里实际列出的那几个（[tagsHint] 全列太长，会把上下文预算吃掉一大截）。
     *
     * 只列最常用的，其余照样能换 —— 白名单管的是「能不能换」，不是「准不准用」。
     */
    val PROMPT_TAGS = listOf("大哭", "狗头", "贴贴", "笑死", "破防", "无语", "委屈", "困", "爱心")

    fun promptHint(): String = PROMPT_TAGS.joinToString(" ") { "[$it]" }

    /**
     * 把文本里的白名单标签换成 emoji，其余原样保留。
     *
     * 标签最长 4 个字（「摸摸头」3 个），所以正则限长，避免把
     * `[这是一段很长的说明文字]` 也吞进去。
     */
    fun expand(text: String): String {
        if (text.indexOf('[') < 0) return text
        return TAG_REGEX.replace(text) { m ->
            MAP[m.groupValues[1]] ?: m.value
        }
    }

    private val TAG_REGEX = Regex("\\[([^\\[\\]\\n]{1,4})]")
}
