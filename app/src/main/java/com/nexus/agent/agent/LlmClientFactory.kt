package com.nexus.agent.agent

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

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

    private const val TAG = "LlmClientFactory"

    private const val PREFS_NAME = "nexus_llm_config"

    /** Keystore 中 AES 密钥别名（用于加密 apiKey） */
    private const val KEY_ALIAS = "nexus_llm_api_key"

    /** 加密值前缀：带此前缀表示已加密，否则视为明文旧数据 */
    private const val ENC_PREFIX = "enc:"

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

        val apiKey = decryptApiKey(prefs?.getString("${p}_api_key", null) ?: "")

        val baseUrl = sanitizeBaseUrl(p, prefs?.getString("${p}_base_url", null))
            ?: DEFAULT_BASE_URLS[p]
            ?: "https://api.deepseek.com/v1"

        val model = sanitizeModel(p, prefs?.getString("${p}_model", null))
            ?: DEFAULT_MODELS[p]
            ?: DEFAULT_MODEL

        return LlmClient(apiKey = apiKey, baseUrl = baseUrl, modelName = model)
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
            putString("${p}_api_key", encryptApiKey(apiKey))
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
     * 清除视觉 provider 的显式设置，回退到「跟随主模型」。
     */
    fun clearVisionProvider(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_VISION_PROVIDER).apply()
    }

    /**
     * 视觉 provider 是否处于「跟随主模型」状态（用户没显式指定过）。
     */
    fun isVisionFollowMain(context: Context?): Boolean {
        val v = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.getString(KEY_VISION_PROVIDER, null)
        return v.isNullOrBlank()
    }

    /**
     * 副驾驶实际该用的 provider。
     *
     * 之前这里直接返回 `getVisionProvider()`（默认 `qwen-vl`），
     * 而设置页从来只写主 provider 的 key —— 结果用户配好 DeepSeek 后，
     * 副驾驶去读一个永远为空的 `qwen-vl_api_key`，连 HTTP 请求都发不出去。
     *
     * 现在的规则：显式指定过就用它，否则**跟随主 provider**。
     */
    fun getEffectiveVisionProvider(context: Context): String {
        val explicit = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_VISION_PROVIDER, null)
        return if (explicit.isNullOrBlank()) getProvider(context) else explicit.lowercase().trim()
    }

    /**
     * 判断 provider / model 是否支持图片输入。
     *
     * 只做名称匹配（各家视觉模型命名都有明显标识），
     * 判断错了也不会崩：最坏情况是把图发过去被服务端拒掉，上层按 [NetworkError] 处理。
     */
    fun supportsVision(provider: String, model: String? = null): Boolean {
        val p = provider.lowercase().trim()
        val m = model.orEmpty().lowercase().trim()
        if (p == "qwen-vl") return true
        val hints = listOf(
            "vl", "vision", "gpt-4o", "gpt-4.1", "gpt-5", "o3", "o4",
            "gemini", "claude-3", "claude-4", "glm-4v", "internvl", "llava",
            "pixtral", "step-1v", "qvq"
        )
        return hints.any { m.contains(it) } || hints.any { p.contains(it) }
    }

    /**
     * 返回某 provider 的默认 baseUrl（不读已保存值）。
     *
     * 供配置界面在切换 provider 时填充，避免被历史保存的旧值污染。
     */
    fun getDefaultBaseUrl(provider: String): String {
        val p = provider.lowercase().trim()
        return DEFAULT_BASE_URLS[p] ?: "https://api.deepseek.com/v1"
    }

    /**
     * 返回某 provider 的默认模型名（不读已保存值）。
     */
    fun getDefaultModel(provider: String): String {
        val p = provider.lowercase().trim()
        return DEFAULT_MODELS[p] ?: DEFAULT_MODEL
    }

    /**
     * 自愈：若保存的 baseUrl 恰好等于「另一个 provider 的默认地址」，
     * 说明是历史 bug（切换 provider 时没同步）污染的值，返回 null 让它回退默认值。
     */
    private fun sanitizeBaseUrl(p: String, saved: String?): String? {
        if (saved.isNullOrBlank()) return null
        val isOtherProviderDefault = DEFAULT_BASE_URLS.any { (k, v) -> k != p && v == saved }
        return if (isOtherProviderDefault) null else saved
    }

    /**
     * 自愈：若保存的 model 恰好等于「另一个 provider 的默认模型名」，同样判定为污染。
     */
    private fun sanitizeModel(p: String, saved: String?): String? {
        if (saved.isNullOrBlank()) return null
        val isOtherProviderDefault = DEFAULT_MODELS.any { (k, v) -> k != p && v == saved }
        return if (isOtherProviderDefault) null else saved
    }

    /**
     * 获取或创建 Android Keystore 中的 AES 密钥。
     */
    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    /**
     * 加密 apiKey。失败时兜底返回明文，绝不让「加密失败」导致功能不可用。
     */
    private fun encryptApiKey(plain: String): String {
        return try {
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(iv.size + ct.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(ct, 0, combined, iv.size, ct.size)
            ENC_PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Throwable) {
            Log.e(TAG, "encrypt apiKey failed, fallback to plaintext", e)
            plain
        }
    }

    /**
     * 解密 apiKey。无前缀（历史明文）或解密失败时直接返回原值，保证向后兼容。
     */
    private fun decryptApiKey(stored: String): String {
        if (stored.isBlank() || !stored.startsWith(ENC_PREFIX)) return stored
        return try {
            val key = getOrCreateKey()
            val combined = Base64.decode(stored.removePrefix(ENC_PREFIX), Base64.NO_WRAP)
            val iv = combined.copyOfRange(0, 12) // GCM 默认 IV 长度 12 字节
            val ct = combined.copyOfRange(12, combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Throwable) {
            Log.e(TAG, "decrypt apiKey failed, return raw", e)
            stored
        }
    }

    /**
     * 读取某 provider 当前生效的配置（含默认值回退）。
     *
     * @return 三元组 (apiKey, baseUrl, model)
     */
    fun getConfig(context: Context, provider: String): Triple<String, String, String> {
        val p = provider.lowercase().trim()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val apiKey = decryptApiKey(prefs.getString("${p}_api_key", "") ?: "")
        val baseUrl = sanitizeBaseUrl(p, prefs.getString("${p}_base_url", null))
            ?: DEFAULT_BASE_URLS[p]
            ?: "https://api.deepseek.com/v1"
        val model = sanitizeModel(p, prefs.getString("${p}_model", null))
            ?: DEFAULT_MODELS[p]
            ?: DEFAULT_MODEL
        return Triple(apiKey, baseUrl, model)
    }

    // ===== ASR 独立配置 =====

    /**
     * 读取 ASR 的 API Key：优先独立配置 asr_api_key，为空回退 qwen Key。
     *
     * 两者都为空返回空字符串（调用方据此提示用户配置）。
     */
    fun getAsrApiKey(context: Context?): String {
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return ""
        val asrKey = decryptApiKey(prefs.getString("asr_api_key", "") ?: "")
        if (asrKey.isNotBlank()) return asrKey
        return decryptApiKey(prefs.getString("qwen_api_key", "") ?: "")
    }

    /** 保存 ASR 独立 Key（加密存储） */
    fun saveAsrApiKey(context: Context, apiKey: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString("asr_api_key", encryptApiKey(apiKey)).apply()
    }

    /** 读取 ASR 模型（默认 paraformer-realtime-v2） */
    fun getAsrModel(context: Context?): String {
        val model = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.getString("asr_model", null)
        return if (model.isNullOrBlank()) "paraformer-realtime-v2" else model
    }

    /** 保存 ASR 模型 */
    fun saveAsrModel(context: Context, model: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString("asr_model", model).apply()
    }
}
