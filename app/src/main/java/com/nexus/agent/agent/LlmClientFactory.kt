package com.nexus.agent.agent

import android.content.Context
import android.content.SharedPreferences

/**
 * LLM 客户端工厂。
 *
 * 根据 provider 名称创建对应的 [LlmClient]：
 * - "openai"    → OpenAI 官方接口
 * - "deepseek"  → DeepSeek 接口（纯文本备选）
 * - "qwen"      → 通义千问（DashScope 兼容 OpenAI 模式）
 * - "qwen-vl"   → 通义千问视觉模型（DashScope 兼容 OpenAI 模式，多模态）
 *
 * apiKey / baseUrl / model 优先从 SharedPreferences 读取，
 * 未配置时回退到 BuildConfig 或内置默认值。
 */
object LlmClientFactory {

    private const val PREFS_NAME = "nexus_llm_config"

    /** 默认 provider（无配置时回退） */
    const val DEFAULT_PROVIDER = "deepseek"

    /** 默认视觉 provider（UI 树元素过少时用截图找目标） */
    const val DEFAULT_VISION_PROVIDER = "qwen-vl"

    /** SharedPreferences 里保存当前 provider 的 key */
    private const val KEY_PROVIDER = "provider"

    /** SharedPreferences 里保存视觉 provider 的 key */
    private const val KEY_VISION_PROVIDER = "vision_provider"

    private const val DEFAULT_MODEL = "deepseek-chat"

    /** 各 provider 默认 baseUrl（OpenAI 兼容地址） */
    private val DEFAULT_BASE_URLS = mapOf(
        "openai" to "https://api.openai.com/v1",
        "deepseek" to "https://api.deepseek.com/v1",
        "qwen" to "https://dashscope.aliyuncs.com/compatible-mode/v1",
        "qwen-vl" to "https://dashscope.aliyuncs.com/compatible-mode/v1"
    )

    /** 各 provider 默认模型 */
    private val DEFAULT_MODELS = mapOf(
        "openai" to "gpt-4o",
        "deepseek" to "deepseek-chat",
        "qwen" to "qwen-plus",
        "qwen-vl" to "qwen-vl-max"
    )

    /**
     * 创建 LLM 客户端。
     *
     * @param provider 提供方标识（openai / deepseek / qwen）
     * @param context  用于读取 SharedPreferences（可为 null，则仅用内置默认值）
     * @return 配置好的 [LlmClient]
     */
    fun create(provider: String, context: Context? = null): LlmClient {
        val p = provider.lowercase().trim()

        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val apiKey = prefs?.getString("${p}_api_key", null)
            ?: buildConfigApiKey(p)
            ?: ""

        val baseUrl = prefs?.getString("${p}_base_url", null)
            ?: DEFAULT_BASE_URLS[p]
            ?: "https://api.deepseek.com/v1"

        val model = prefs?.getString("${p}_model", null)
            ?: DEFAULT_MODELS[p]
            ?: DEFAULT_MODEL

        return LlmClient(apiKey = apiKey, baseUrl = baseUrl, modelName = model)
    }

    /**
     * 从 BuildConfig 读取 apiKey（可选，便于调试期硬编码）。
     *
     * 当前未在 build.gradle.kts 中定义 LLM apiKey 字段，
     * 故默认返回 null；后续若需支持，可在 buildConfig 里添加对应字段。
     */
    private fun buildConfigApiKey(provider: String): String? {
        return null
    }

    /**
     * 保存某 provider 的配置到 SharedPreferences。
     *
     * 供设置界面调用，实现运行期动态切换 API 配置。
     */
    fun saveConfig(
        context: Context,
        provider: String,
        apiKey: String,
        baseUrl: String? = null,
        model: String? = null
    ) {
        val p = provider.lowercase().trim()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("${p}_api_key", apiKey)
            baseUrl?.let { putString("${p}_base_url", it) }
            model?.let { putString("${p}_model", it) }
        }.apply()
    }

    /**
     * 读取当前生效的 provider（默认 "deepseek"）。
     *
     * 供 Planner / Executor / AgentRunner 等调用方统一获取，
     * 避免在多处硬编码 provider。
     */
    fun getProvider(context: Context?): String {
        val p = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.getString(KEY_PROVIDER, null)
        return if (p.isNullOrBlank()) DEFAULT_PROVIDER else p.lowercase().trim()
    }

    /**
     * 保存当前 provider（供设置界面切换）。
     */
    fun saveProvider(context: Context, provider: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_PROVIDER, provider.lowercase().trim()).apply()
    }

    /**
     * 读取当前生效的视觉 provider（默认 "qwen-vl"）。
     *
     * 供 Executor 在视觉模式（UI 树元素过少时）使用。
     */
    fun getVisionProvider(context: Context?): String {
        val p = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.getString(KEY_VISION_PROVIDER, null)
        return if (p.isNullOrBlank()) DEFAULT_VISION_PROVIDER else p.lowercase().trim()
    }

    /**
     * 保存视觉 provider。
     */
    fun saveVisionProvider(context: Context, provider: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_VISION_PROVIDER, provider.lowercase().trim()).apply()
    }

    /**
     * 读取某 provider 当前生效的配置（含默认值回退）。
     *
     * @return 三元组 (apiKey, baseUrl, model)
     */
    fun getConfig(context: Context, provider: String): Triple<String, String, String> {
        val p = provider.lowercase().trim()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val apiKey = prefs.getString("${p}_api_key", "") ?: ""
        val baseUrl = prefs.getString("${p}_base_url", null)
            ?: DEFAULT_BASE_URLS[p]
            ?: "https://api.deepseek.com/v1"
        val model = prefs.getString("${p}_model", null)
            ?: DEFAULT_MODELS[p]
            ?: DEFAULT_MODEL
        return Triple(apiKey, baseUrl, model)
    }
}
