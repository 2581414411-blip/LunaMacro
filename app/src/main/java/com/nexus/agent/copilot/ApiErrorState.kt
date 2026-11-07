package com.nexus.agent.copilot

import android.util.Log
import com.nexus.agent.agent.ApiDiagnostics
import com.nexus.agent.agent.LlmClient
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 「上一轮云端调用到底怎么失败的」—— 0.9.1。
 *
 * ## 要解决的问题
 *
 * 调用链是 `PersonaEngine.askRoleLlm` → `LlmClient.chat` → `LocalDigitalBeing.chat`，
 * 而 `LocalDigitalBeing.chat` 会把 Brain 抛出的异常**吞掉**并返回 null（这是刻意的：
 * 记忆引擎不能因为一次网络失败就把整个对话链路炸掉）。
 *
 * 副作用是：`PersonaEngine.respond` 收到 null 时**什么都不知道**，只能照着旧逻辑说
 * 「我这边模型没接上，等会儿再跟我说一遍？」—— 用户拿着这句话永远修不好问题，
 * 因为 401（Key 错）、402（没钱）、超时（网络）是三件完全不同的事。
 *
 * 所以异常在**离现场最近的地方**（[PersonaEngine.askRoleLlm]）记一笔，
 * 上层回头查这一笔，把状态码翻成人话。
 *
 * ## 线程与生命周期
 *
 * - 一个 volatile 字段，单机单用户，够用；
 * - 每轮 [PersonaEngine.respond] 开头 [beginTurn] 清一次，避免上一轮的失败被当成这一轮的；
 * - [takeRecent] 有 60 秒时效，聊天界面重试时不会拿到一小时前的旧账。
 */
object ApiErrorState {

    private const val TAG = "ApiErrorState"

    /** 一次失败的完整描述 */
    data class Failure(
        val provider: String,
        val kind: ApiDiagnostics.Kind,
        val code: Int,
        /** 响应原文/异常名，截断过，够定位就行 */
        val raw: String,
        val at: Long = System.currentTimeMillis()
    )

    @Volatile
    private var last: Failure? = null

    /** 新一轮开始：把上一轮的失败清掉 */
    fun beginTurn() {
        last = null
    }

    /** 记录一次失败（已存在更早的记录时不覆盖 —— 第一个失败的通常是真因） */
    fun record(provider: String, error: Throwable) {
        if (last != null) return
        val f = when (error) {
            is LlmClient.ApiException -> Failure(
                provider = provider,
                kind = ApiDiagnostics.classify(error.statusCode, error.responseBody),
                code = error.statusCode,
                raw = ApiDiagnostics.snippet(error.responseBody)
            )
            is SocketTimeoutException -> Failure(
                provider, ApiDiagnostics.Kind.TIMEOUT, 0, "SocketTimeoutException"
            )
            is UnknownHostException -> Failure(
                provider, ApiDiagnostics.Kind.NETWORK, 0, "UnknownHostException: ${error.message.orEmpty()}"
            )
            is IOException -> Failure(
                provider, ApiDiagnostics.Kind.NETWORK, 0, "${error.javaClass.simpleName}: ${error.message.orEmpty()}"
            )
            else -> Failure(
                provider,
                ApiDiagnostics.Kind.UNKNOWN,
                0,
                "${error.javaClass.simpleName}: ${error.message.orEmpty()}"
            )
        }
        last = f
        Log.w(TAG, "记录云端失败 provider=$provider kind=${f.kind} code=${f.code} raw=${f.raw.take(120)}")
    }

    /** 取最近一次失败（默认 60 秒内有效） */
    fun takeRecent(maxAgeMs: Long = 60_000L): Failure? {
        val f = last ?: return null
        // 用严格小于：maxAgeMs = 0 表示「不接受任何已有记录」，单测靠它验时效
        return if (System.currentTimeMillis() - f.at < maxAgeMs) f else null
    }

    /** 清空（排障页/测试用） */
    fun clear() {
        last = null
    }

    /**
     * 把失败翻成**用户能照着修**的一句话。
     *
     * 前缀「（系统提示：…）」是刻意的：它要明显不像是角色在说话 ——
     * 不然用户会以为「宏月」在跟他抱怨余额。
     */
    fun notice(f: Failure): String = when (f.kind) {
        ApiDiagnostics.Kind.AUTH ->
            "（系统提示：云端 API Key 无效或已失效，请前往设置 → LLM 配置检查。）"
        ApiDiagnostics.Kind.QUOTA ->
            "（系统提示：云端 API 额度耗尽或 Key 失效，请前往设置检查。）"
        ApiDiagnostics.Kind.RATE_LIMIT ->
            "（系统提示：云端接口被限流了（HTTP 429），等半分钟再说。）"
        ApiDiagnostics.Kind.BAD_URL ->
            "（系统提示：API 地址不对（HTTP ${f.code}），请到设置里检查 baseUrl。）"
        ApiDiagnostics.Kind.SERVER ->
            "（系统提示：云端服务端故障（HTTP ${f.code}），不是你这边的问题，稍后再试。）"
        ApiDiagnostics.Kind.TIMEOUT ->
            "（系统提示：云端请求超时，网络太慢或代理挂了，请检查网络后重试。）"
        ApiDiagnostics.Kind.NETWORK ->
            "（系统提示：连不上云端（网络或代理问题），请检查网络后重试。）"
        ApiDiagnostics.Kind.NO_KEY ->
            "（系统提示：还没填 API Key，请前往设置 → LLM 配置填写。）"
        ApiDiagnostics.Kind.OK ->
            "（系统提示：调用居然失败了，但没有错误码，请导出日志看看。）"
        ApiDiagnostics.Kind.UNKNOWN ->
            "（系统提示：云端调用失败${if (f.code > 0) "（HTTP ${f.code}）" else ""}：" +
                "${f.raw.take(80)}）"
    }

    /**
     * 有失败记录就用它的结论，否则用调用方给的兜底话。
     *
     * 给 [CopilotEngine] 那条「人格闲聊本轮放弃」的分支用。
     */
    fun lastNoticeOr(fallback: String): String =
        takeRecent()?.let { notice(it) } ?: fallback
}
