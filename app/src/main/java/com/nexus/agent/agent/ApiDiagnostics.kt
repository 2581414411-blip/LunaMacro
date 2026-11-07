package com.nexus.agent.agent

import android.content.Context
import android.util.Log
import com.nexus.agent.util.MiniJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * 云端 API 的**连通性与余额诊断**（0.9.1）。
 *
 * ## 为什么单独做一件事
 *
 * 0.9.0 之前「测试 LLM」就是发一句「回复两个字：你好」，失败时把异常 message 直接糊到界面上：
 *
 * ```
 * ❌ LLM 调用失败：HTTP 402 Payment Required
 * ```
 *
 * 用户看到这行只能来问「什么意思」。而真实原因通常只有三类：
 * **Key 无效 / 余额不足 / 网络不通**，三者的修法完全不同，所以必须分开说。
 *
 * ## 各家怎么查
 *
 * | provider | 探测方式 | 能查余额吗 |
 * |---|---|---|
 * | deepseek | `GET /user/balance`（官方余额接口） | 能，直接显示「余额：¥12.50」 |
 * | openai / qwen / 自定义 | `GET /models`（不消耗 Token） | 不能，提示去官网控制台看 |
 * | 兜底 | 一次 `max_tokens=1` 的极简对话 | 不能 |
 *
 * 状态码 → 人话的映射统一走 [classify]，聊天链路的错误提示
 * （[com.nexus.agent.copilot.ApiErrorState]）也复用它 —— **一份实现，不会两处说法不一致**。
 */
object ApiDiagnostics {

    private const val TAG = "ApiDiagnostics"

    /** 诊断结论的类别（决定界面上写什么、用户下一步该干嘛） */
    enum class Kind {
        /** 200 —— 连接正常 */
        OK,
        /** 401/403 —— Key 无效或没权限 */
        AUTH,
        /** 402 —— 余额/额度不足 */
        QUOTA,
        /** 429 —— 限流 */
        RATE_LIMIT,
        /** 404/405 —— 地址不对（baseUrl 填错、少了 /v1） */
        BAD_URL,
        /** 5xx —— 服务端自己挂了 */
        SERVER,
        /** 超时 */
        TIMEOUT,
        /** 网络不通（DNS / 代理 / 明文策略拦截） */
        NETWORK,
        /** 没填 Key */
        NO_KEY,
        /** 其它 */
        UNKNOWN
    }

    /** 一次诊断的结果 */
    data class Report(
        val provider: String,
        val baseUrl: String,
        val model: String,
        val kind: Kind,
        val httpCode: Int = 0,
        /** 一句话结论（界面标题行） */
        val title: String,
        /** 展开说明（含原文片段，便于贴给客服/开发者） */
        val detail: String = "",
        /** 能查到就是「余额：¥12.50」，查不到为 null */
        val balance: String? = null
    ) {
        val ok: Boolean get() = kind == Kind.OK

        /** 设置页弹窗里的正文 */
        fun toDisplayText(): String = buildString {
            appendLine(title)
            balance?.let { appendLine(it) }
            if (detail.isNotBlank()) appendLine(detail)
            appendLine()
            appendLine("provider：$provider")
            appendLine("model：$model")
            append("baseUrl：$baseUrl")
        }
    }

    private val client = OkHttpClient.Builder()
        // 诊断要快：卡住 30 秒不如直接告诉用户「超时」
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    /**
     * 跑一次完整诊断。
     *
     * 不抛异常：所有失败都变成 [Report]，界面只负责显示。
     */
    suspend fun check(context: Context, provider: String? = null): Report = withContext(Dispatchers.IO) {
        val p = (provider ?: LlmClientFactory.getProvider(context)).lowercase().trim()
        val (apiKey, baseUrl, model) = LlmClientFactory.getConfig(context, p)

        if (apiKey.isBlank() && p != "ollama") {
            return@withContext Report(
                provider = p,
                baseUrl = baseUrl,
                model = model,
                kind = Kind.NO_KEY,
                title = "还没填 API Key",
                detail = "去「配置 LLM」里粘贴 $p 的 Key 再测。"
            )
        }

        Log.i(TAG, "开始诊断 provider=$p baseUrl=$baseUrl model=$model keyLen=${apiKey.length}")

        if (p == "deepseek") {
            val balance = probeBalance(apiKey, baseUrl, model)
            if (balance != null) return@withContext balance
            // 余额接口不可用（老域名 / 代理不支持 / 404）→ 退回通用连通性探测
            Log.i(TAG, "余额接口不可用，退回 /models 探测")
        }
        probeModels(apiKey, baseUrl, p, model) ?: probeMinimalChat(apiKey, baseUrl, p, model)
    }

    // ---------------------------------------------------------------- 探测

    /** DeepSeek 官方余额接口；返回 null 表示「这个接口不可用，别处再试」 */
    private fun probeBalance(apiKey: String, baseUrl: String, model: String): Report? {
        val url = balanceUrl(baseUrl)
        val result = get(url, apiKey)
        if (result.code == 200) {
            val (balance, detail) = formatBalance(result.body)
            return Report(
                provider = "deepseek",
                baseUrl = baseUrl,
                model = model,
                kind = Kind.OK,
                httpCode = 200,
                title = "连接成功",
                detail = detail,
                balance = balance
            )
        }
        // 404/405 = 这个域名没有余额接口（自建代理），交给 /models 兜底；
        // 401/402 才是真结论，直接返回
        if (result.code == 404 || result.code == 405 || result.code == 0) return null
        return report("deepseek", baseUrl, model, result)
    }

    /** 通用：`GET /models`，不消耗 Token，能验证「地址对不对 + Key 通不通」 */
    private fun probeModels(apiKey: String, baseUrl: String, provider: String, model: String): Report? {
        val result = get(modelsUrl(baseUrl), apiKey)
        if (result.code == 200) {
            val count = countModels(result.body)
            return Report(
                provider = provider,
                baseUrl = baseUrl,
                model = model,
                kind = Kind.OK,
                httpCode = 200,
                title = "连接成功",
                detail = buildString {
                    if (count > 0) append("服务端可用模型 $count 个。")
                    append("这个服务不提供余额查询，额度请去官网控制台看。")
                }
            )
        }
        if (result.code == 404 || result.code == 405 || result.code == 0) return null
        return report(provider, baseUrl, model, result)
    }

    /** 最后的兜底：发一次 1 Token 的对话，花最少的钱验证整条链路 */
    private fun probeMinimalChat(apiKey: String, baseUrl: String, provider: String, model: String): Report {
        val url = "$baseUrl".trimEnd('/').let {
            if (it.endsWith("/chat/completions")) it else "$it/chat/completions"
        }
        val body = """{"model":${quote(model)},"messages":[{"role":"user","content":"hi"}],"max_tokens":1}"""
        val result = postJson(url, apiKey, body)
        if (result.code == 200) {
            return Report(
                provider, baseUrl, model, Kind.OK, 200,
                title = "连接成功",
                detail = "对话接口正常（这次探测只花了 1 个 Token）。这个服务不提供余额查询，额度请去官网控制台看。"
            )
        }
        return report(provider, baseUrl, model, result)
    }

    // ---------------------------------------------------------------- 纯函数（可单测）

    /** 一次 HTTP 的结果（把 OkHttp 的异常也压成 code=0） */
    internal data class HttpResult(val code: Int, val body: String)

    /** 把 HTTP 结果翻译成结论 */
    private fun report(provider: String, baseUrl: String, model: String, r: HttpResult): Report =
        Report(
            provider = provider,
            baseUrl = baseUrl,
            model = model,
            kind = classify(r.code, r.body),
            httpCode = r.code,
            title = titleOf(classify(r.code, r.body), r.code),
            detail = snippet(r.body)
        )

    /**
     * 状态码 → 类别。**这里是唯一的一份映射**，聊天链路的错误提示复用它。
     */
    fun classify(code: Int, body: String = ""): Kind {
        val lower = body.lowercase()
        return when {
            code == 200 || code == 201 -> Kind.OK
            code == 401 || code == 403 -> Kind.AUTH
            code == 402 -> Kind.QUOTA
            // 429 有两种：真限流，和「余额不足」被某些中转商写成 429
            code == 429 -> if (lower.contains("quota") || lower.contains("insufficient") ||
                lower.contains("balance") || lower.contains("余额")
            ) Kind.QUOTA else Kind.RATE_LIMIT
            code == 404 || code == 405 -> Kind.BAD_URL
            code in 500..599 -> Kind.SERVER
            code == 0 -> Kind.NETWORK
            // 400 也可能是「余额不足」（DeepSeek 欠费时会给 400 + insufficient balance）
            code == 400 && (lower.contains("insufficient") || lower.contains("balance") ||
                lower.contains("余额") || lower.contains("quota")) -> Kind.QUOTA
            else -> Kind.UNKNOWN
        }
    }

    /** 类别 → 一句话结论（用户照着这句话就知道去改哪里） */
    fun titleOf(kind: Kind, code: Int = 0): String = when (kind) {
        Kind.OK -> "连接成功"
        Kind.AUTH -> "鉴权失败（$code）：Key 无效或已被删除"
        Kind.QUOTA -> "余额/额度不足（$code）：去官网充值，或换一个 Key"
        Kind.RATE_LIMIT -> "请求太频繁（$code）：等一会儿再试"
        Kind.BAD_URL -> "地址不对（$code）：检查 baseUrl，通常要带 /v1"
        Kind.SERVER -> "服务端错误（$code）：不是你这边的问题，稍后再试"
        Kind.TIMEOUT -> "请求超时：检查手机网络 / 代理"
        Kind.NETWORK -> "网络不通：DNS 或代理有问题，也可能是明文 HTTP 被系统拦了"
        Kind.NO_KEY -> "还没填 API Key"
        Kind.UNKNOWN -> "未知错误" + if (code > 0) "（HTTP $code）" else ""
    }

    /**
     * 从 baseUrl 里取「源」：`https://api.deepseek.com/v1` → `https://api.deepseek.com`。
     *
     * 余额接口挂在域名根上（`/user/balance`），直接拼在 `/v1` 后面会 404。
     */
    internal fun originOf(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd < 0) return trimmed
        val hostStart = schemeEnd + 3
        val slash = trimmed.indexOf('/', hostStart)
        return if (slash < 0) trimmed else trimmed.substring(0, slash)
    }

    /** DeepSeek 余额接口地址 */
    fun balanceUrl(baseUrl: String): String = originOf(baseUrl) + "/user/balance"

    /** 通用 /models 地址 */
    fun modelsUrl(baseUrl: String): String = baseUrl.trim().trimEnd('/') + "/models"

    /**
     * 解析 DeepSeek 余额响应：
     * `{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"12.50",...}]}`
     *
     * @return (形如「余额：¥12.50」的可显示串, 附加说明)；解析不出来时返回 (null, 原文摘要)
     */
    fun formatBalance(json: String): Pair<String?, String> {
        val root = MiniJson.parseObject(json)
            ?: return null to "余额接口返回了无法解析的内容"
        val infos = root["balance_infos"] as? List<*> ?: emptyList<Any?>()
        val parts = ArrayList<String>(2)
        for (item in infos) {
            @Suppress("UNCHECKED_CAST")
            val m = item as? Map<String, Any?> ?: continue
            val currency = (m["currency"] as? String)?.uppercase().orEmpty()
            val total = (m["total_balance"] as? String)?.trim()
                ?: (m["total_balance"] as? Number)?.toString()
                ?: continue
            parts.add("${symbolOf(currency)}$total")
        }
        if (parts.isEmpty()) return null to "余额接口没返回可用字段（${snippet(json)}）"
        val available = (root["is_available"] as? Boolean) ?: true
        val detail = if (available) "" else "账户当前不可用（余额可能已耗尽）"
        return "余额：${parts.joinToString(" / ")}" to detail
    }

    /** 货币符号（默认按人民币，符合国内用户习惯） */
    fun symbolOf(currency: String): String = when (currency.uppercase()) {
        "CNY", "RMB", "" -> "¥"
        "USD" -> "$"
        "HKD" -> "HK$"
        else -> "$currency "
    }

    /** `/models` 返回里数一下有几个模型（只是让结论更有说服力，不参与判定） */
    internal fun countModels(json: String): Int {
        val arr = MiniJson.parseArray(json) ?: return 0
        return arr.size
    }

    /** 截一段响应原文（400 字符以内），出问题时能直接贴给人看 */
    internal fun snippet(body: String): String {
        val s = body.trim().replace(Regex("\\s+"), " ")
        if (s.isEmpty()) return ""
        return if (s.length <= 400) s else s.take(400) + "…"
    }

    private fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    // ---------------------------------------------------------------- HTTP

    private fun get(url: String, apiKey: String): HttpResult = execute(
        Request.Builder().url(url).get()
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Accept", "application/json")
            .build()
    )

    private fun postJson(url: String, apiKey: String, body: String): HttpResult = execute(
        Request.Builder().url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
    )

    private fun execute(request: Request): HttpResult = try {
        client.newCall(request).execute().use { resp ->
            HttpResult(resp.code, resp.body?.string().orEmpty())
        }
    } catch (e: SocketTimeoutException) {
        Log.w(TAG, "诊断超时：${request.url} ${e.message}")
        HttpResult(0, "timeout")
    } catch (e: UnknownHostException) {
        Log.w(TAG, "域名解析失败：${request.url} ${e.message}")
        HttpResult(0, "dns: ${e.message.orEmpty()}")
    } catch (e: IOException) {
        Log.w(TAG, "网络异常：${request.url} ${e.javaClass.simpleName} ${e.message}")
        HttpResult(0, "${e.javaClass.simpleName}: ${e.message.orEmpty()}")
    } catch (e: Throwable) {
        Log.w(TAG, "诊断异常：${e.javaClass.simpleName} ${e.message}")
        HttpResult(0, e.javaClass.simpleName)
    }
}
