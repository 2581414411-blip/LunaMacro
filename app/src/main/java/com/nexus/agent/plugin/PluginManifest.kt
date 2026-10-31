package com.nexus.agent.plugin

import org.json.JSONArray
import org.json.JSONObject

/**
 * 插件配置字段类型。
 *
 * 二创作者在 `plugin.json` 的 `settingsSchema` 里声明需要哪些参数，
 * App 端用 Compose 动态渲染成表单，无需重新打包。
 */
enum class PluginFieldType {
    /** 单行文本 */
    TEXT,

    /** 数字（整数/小数，运行时按文本存，用的时候自行转换） */
    NUMBER,

    /** 布尔开关 */
    BOOL,

    /** 下拉单选（取值见 [PluginField.options]） */
    SELECT,

    /**
     * 滑动条（0.8.12）：整数取值，范围见 [PluginField.minValue] / [PluginField.maxValue]。
     *
     * 为什么单独给一个类型而不是「number + 猜」：UI 层需要知道该渲染成输入框还是滑条，
     * 猜错了用户体验就很糟（「激进程度」让人手打 0~100 的数字很蠢）。
     */
    SLIDER;

    companion object {
        fun of(raw: String?): PluginFieldType = when (raw?.lowercase()) {
            "number", "int", "float", "integer" -> NUMBER
            "bool", "boolean", "switch" -> BOOL
            "select", "enum", "choice" -> SELECT
            "slider", "range", "percent", "level" -> SLIDER
            else -> TEXT
        }
    }
}

/**
 * 一个可配置项。
 *
 * @param key     存进 prefs 的键（同一插件内唯一）
 * @param label   显示名
 * @param type    控件类型
 * @param default 默认值（统一用字符串表示，方便持久化）
 * @param options SELECT 的候选项
 * @param hint    说明文字
 * @param minValue SLIDER 的最小值（非 SLIDER 忽略）
 * @param maxValue SLIDER 的最大值（非 SLIDER 忽略）
 */
data class PluginField(
    val key: String,
    val label: String,
    val type: PluginFieldType = PluginFieldType.TEXT,
    val default: String = "",
    val options: List<String> = emptyList(),
    val hint: String = "",
    val minValue: Int = DEFAULT_SLIDER_MIN,
    val maxValue: Int = DEFAULT_SLIDER_MAX
) {
    /** 实际可用的滑动范围：配置写反了也不至于让 UI 崩（Compose Slider 要求 start < end） */
    val sliderRange: IntRange
        get() = if (minValue < maxValue) minValue..maxValue else DEFAULT_SLIDER_MIN..DEFAULT_SLIDER_MAX

    companion object {
        const val DEFAULT_SLIDER_MIN = 0
        const val DEFAULT_SLIDER_MAX = 100

        fun from(o: JSONObject): PluginField? {
            val key = o.optString("key").trim()
            if (key.isEmpty()) return null
            val opts = mutableListOf<String>()
            o.optJSONArray("options")?.let { arr ->
                for (i in 0 until arr.length()) opts.add(arr.optString(i))
            }
            return PluginField(
                key = key,
                label = o.optString("label").ifBlank { key },
                type = PluginFieldType.of(o.optString("type")),
                default = o.optString("default"),
                options = opts,
                hint = o.optString("hint"),
                minValue = o.optInt("minValue", DEFAULT_SLIDER_MIN),
                maxValue = o.optInt("maxValue", DEFAULT_SLIDER_MAX)
            )
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("key", key)
        put("label", label)
        put("type", type.name.lowercase())
        if (default.isNotEmpty()) put("default", default)
        if (options.isNotEmpty()) put("options", JSONArray(options))
        if (hint.isNotEmpty()) put("hint", hint)
        if (type == PluginFieldType.SLIDER) {
            put("minValue", minValue)
            put("maxValue", maxValue)
        }
    }
}

/**
 * 插件清单（`plugin.json`）。
 *
 * 设计原则：**声明式**。插件自己不执行代码（避免动态 dex 的安全与审核风险），
 * 只声明「要做什么 + 需要哪些参数 + 推荐用哪个模型」，
 * 由 App 的 Agent 能力去完成，二创者改 JSON 即可产出新玩法。
 *
 * 示例：
 * ```json
 * {
 *   "id": "genshin_daily",
 *   "name": "原神日常",
 *   "version": "1.0.0",
 *   "description": "自动完成每日委托并领取奖励",
 *   "packageName": "com.miHoYo.Yuanshen",
 *   "recommendedModel": "cloud",
 *   "goal": "完成原神今天的每日委托并领取奖励",
 *   "allowedTools": ["click_text", "tap", "swipe", "launch_app", "screenshot"],
 *   "settingsSchema": [
 *     {"key":"times","label":"执行次数","type":"number","default":"1"},
 *     {"key":"mode","label":"风格","type":"select","options":["保守","激进"],"default":"保守"},
 *     {"key":"collect","label":"顺手采集","type":"bool","default":"false"},
 *     {"key":"aggressiveness","label":"激进程度","type":"slider","minValue":0,"maxValue":100,"default":"50"}
 *   ]
 * }
 * ```
 */
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String = "1.0.0",
    val author: String = "",
    val description: String = "",
    /** 目标应用包名（可空，空则不限） */
    val packageName: String? = null,
    /** 推荐模型：default（自动）/ cloud（云端）/ local（本地优先） */
    val recommendedModel: String = MODEL_DEFAULT,
    /** 交给 Agent 的目标描述 */
    val goal: String = "",
    val settingsSchema: List<PluginField> = emptyList(),
    /**
     * 工具白名单（0.8.12）。
     *
     * - `null` = 不限制（老插件的行为，保持向后兼容）；
     * - 非 null = **只允许**调用列表内的工具，其余一律拒绝并返回
     *   「该插件无权使用此工具」。
     *
     * 为什么要有：插件本质是「一段提示词 + 配置」，跑起来却是完整的 Agent，
     * 能点、能滑、能装应用。二创作者写错一个目标，AI 可能真把用户的手机翻个底朝天。
     * 声明式白名单把「它最多能干到哪」写死在清单里，执行器在动手前校验。
     */
    val allowedTools: List<String>? = null,
    /** 是否内置示例插件 */
    val builtin: Boolean = false
) {
    /** 实际运行用的任务文本（goal 为空时退回 name） */
    val taskText: String get() = goal.ifBlank { name }

    companion object {
        const val MODEL_DEFAULT = "default"
        const val MODEL_CLOUD = "cloud"
        const val MODEL_LOCAL = "local"

        /** 模型选择的中文标签 */
        fun modelLabel(model: String): String = when (model) {
            MODEL_CLOUD -> "云端"
            MODEL_LOCAL -> "本地优先"
            else -> "默认（自动）"
        }

        /** 规范化 id：只留字母数字下划线中划线，避免路径穿越 */
        fun safeId(raw: String): String =
            raw.trim().lowercase().replace(Regex("[^a-z0-9_\\-]"), "_").ifBlank { "plugin" }

        /** 解析 JSON 文本；缺 id 或 name 视为无效 */
        fun parse(json: String): PluginManifest? = try {
            from(JSONObject(json))
        } catch (e: Throwable) {
            null
        }

        fun from(o: JSONObject): PluginManifest? {
            val id = o.optString("id").trim()
            val name = o.optString("name").trim()
            if (id.isEmpty() || name.isEmpty()) return null

            val fields = mutableListOf<PluginField>()
            o.optJSONArray("settingsSchema")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val f = arr.optJSONObject(i) ?: continue
                    PluginField.from(f)?.let { fields.add(it) }
                }
            }

            val model = o.optString("recommendedModel").lowercase().let {
                if (it == MODEL_CLOUD || it == MODEL_LOCAL) it else MODEL_DEFAULT
            }

            // allowedTools：字段缺失 = null（不限）；显式给 [] = 一个工具都不许用
            val allowed: List<String>? = o.optJSONArray("allowedTools")?.let { arr ->
                val list = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val t = arr.optString(i).trim()
                    if (t.isNotEmpty()) list.add(t)
                }
                list.distinct()
            }

            return PluginManifest(
                id = safeId(id),
                name = name,
                version = o.optString("version").ifBlank { "1.0.0" },
                author = o.optString("author"),
                description = o.optString("description"),
                packageName = o.optString("packageName").takeIf { it.isNotBlank() },
                recommendedModel = model,
                goal = o.optString("goal"),
                settingsSchema = fields,
                allowedTools = allowed,
                builtin = o.optBoolean("builtin", false)
            )
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("version", version)
        if (author.isNotEmpty()) put("author", author)
        if (description.isNotEmpty()) put("description", description)
        if (!packageName.isNullOrBlank()) put("packageName", packageName)
        put("recommendedModel", recommendedModel)
        if (goal.isNotEmpty()) put("goal", goal)
        allowedTools?.let { put("allowedTools", JSONArray(it)) }
        put("builtin", builtin)
        put("settingsSchema", JSONArray().apply { settingsSchema.forEach { put(it.toJson()) } })
    }
}
