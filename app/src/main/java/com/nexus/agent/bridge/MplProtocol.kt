package com.nexus.agent.bridge

import org.json.JSONObject

/**
 * MPL ↔ 宏月动作协议 v1 —— 纯逻辑层（0.9.31）。
 *
 * 冻结正文在 `docs/mpl-protocol-v1.md`（sha256 与 MPL 仓库一致）。
 * 这个文件只做三件事：**解析请求 / 组装响应 / 夹取超时**。
 * 不碰 socket、不碰 Android API —— 所以它能被纯 JVM 单测打靶，
 * 也是 fixtures 能在两边各跑一遍的前提。
 *
 * ## 为什么错误码是字符串而不是数字
 *
 * 协议 §11 写死了「客户端必须按 code 字符串分支」。数字一旦被谁改一次顺序，
 * 对面的分支就会静默走进错误的分支 —— 而这里最不能接受的就是「静默」。
 */
object MplProtocol {

    /** 协议版本。字段改名 / 语义变化才动它 */
    const val VERSION = 1

    /** `ping` / `health` 里报的应用名，MPL 用它确认端口上确实是宏月 */
    const val APP_NAME = "LunaMacro"

    /** 业务端口（只绑 loopback） */
    const val PORT = 18080

    /** 事件端口（MPL 侧监听，0.9.32 才开始用） */
    const val EVENT_PORT = 18081

    /** 请求体上限 */
    const val MAX_BODY = 16 * 1024

    /** 幂等记录保留多久 */
    const val LEDGER_TTL_MS = 10 * 60_000L

    /** health 里报给客户端的默认 / 最大超时 */
    const val DEFAULT_TIMEOUT_MS = 15_000L
    const val MAX_TIMEOUT_MS = 120_000L

    /** 12 个错误码（协议 §6.1，一个都不能少、不能多到客户端不认识） */
    object Code {
        const val UNSUPPORTED_VERSION = "UNSUPPORTED_VERSION"
        const val UNKNOWN_ACTION = "UNKNOWN_ACTION"
        const val FORBIDDEN = "FORBIDDEN"
        const val NEEDS_CONFIRM = "NEEDS_CONFIRM"
        const val APP_NOT_FOUND = "APP_NOT_FOUND"
        const val TARGET_NOT_FOUND = "TARGET_NOT_FOUND"
        const val STOPPED_AT_STEP = "STOPPED_AT_STEP"
        const val TIMEOUT = "TIMEOUT"
        const val BUSY = "BUSY"
        const val PERMISSION_DENIED = "PERMISSION_DENIED"
        const val UNKNOWN_RESULT = "UNKNOWN_RESULT"
        const val CANCELLED = "CANCELLED"
    }

    /**
     * 一个动作的规格。
     *
     * @param readOnly 只读动作不受「引擎在忙」限制（边说边看一眼屏幕必须能做）
     */
    data class ActionSpec(
        val name: String,
        val defaultMs: Long,
        val maxMs: Long,
        val readOnly: Boolean
    )

    /** 六个动作的规格。协议 §5 那张表就是它 */
    val SPECS: List<ActionSpec> = listOf(
        ActionSpec("read_screen", 3_000, 5_000, readOnly = true),
        ActionSpec("open_app", 20_000, 30_000, readOnly = true),
        ActionSpec("list_macros", 3_000, 5_000, readOnly = true),
        ActionSpec("click_text", 5_000, 8_000, readOnly = false),
        ActionSpec("input_text", 8_000, 12_000, readOnly = false),
        ActionSpec("run_macro", 60_000, 120_000, readOnly = false)
    )

    /**
     * 本版本真正开放的动作。
     *
     * 0.9.31 只开两个只读的 —— 先把鉴权、幂等、风控、忙闸四条腿验证完，
     * 再放开会点会输的那三个。协议允许「只上报一部分」，客户端按 `health.actions` 协商。
     */
    val ACTIONS_READONLY: List<String> = listOf("read_screen", "open_app")

    /** 上报给客户端的动作清单（0.9.32 会追加 4 个，不需要改协议版本） */
    fun actions(): List<String> = ACTIONS_READONLY

    fun specOf(action: String): ActionSpec? =
        SPECS.firstOrNull { it.name == action.lowercase().trim() }

    fun isSupported(action: String): Boolean = actions().contains(action.lowercase().trim())

    fun isReadOnly(action: String): Boolean = specOf(action)?.readOnly ?: false

    /** 按 action clamp 超时：客户端要多少不重要，服务端说了算 */
    fun clampTimeout(action: String, requestedMs: Long?): Long {
        val spec = specOf(action)
        val max = spec?.maxMs ?: MAX_TIMEOUT_MS
        val def = spec?.defaultMs ?: DEFAULT_TIMEOUT_MS
        val want = requestedMs ?: return def
        if (want <= 0) return def
        return want.coerceAtMost(max)
    }

    // ---------------------------------------------------------------- 请求

    data class Request(
        val id: String,
        val action: String,
        val args: Map<String, Any?>,
        val risk: String,
        val timeoutMs: Long,
        val reason: String?,
        val confirm: Boolean
    )

    /** 解析结果：能用的请求，或者一个「连 id 都拿不到」的坏请求 */
    sealed class Parsed {
        data class Ok(val req: Request) : Parsed()

        /** 传输层错误：服务端回 4xx 且不回 body（协议 §6 只允许 401 走 HTTP 层，其余业务错误都是 200） */
        data class Bad(val httpStatus: Int, val message: String) : Parsed()

        /** 业务层错误：能拿到 id，回 200 + ok:false */
        data class Biz(val id: String, val code: String, val message: String) : Parsed()
    }

    fun parse(body: String): Parsed {
        if (body.isBlank()) return Parsed.Bad(400, "空请求体")
        val json = try {
            JSONObject(body)
        } catch (e: Throwable) {
            return Parsed.Bad(400, "不是合法 JSON：${e.message}")
        }

        val id = json.optString("id", "").trim()
        if (id.isEmpty()) return Parsed.Bad(400, "缺少 id")

        val v = json.optInt("v", -1)
        if (v != VERSION) {
            return Parsed.Biz(id, Code.UNSUPPORTED_VERSION, "协议版本 $v 不支持，本端只认 $VERSION")
        }

        val action = json.optString("action", "").trim().lowercase()
        if (action.isEmpty()) return Parsed.Biz(id, Code.UNKNOWN_ACTION, "缺少 action")

        val argsObj = json.optJSONObject("args") ?: JSONObject()
        val args = HashMap<String, Any?>(argsObj.length())
        argsObj.keys().forEach { k -> args[k] = argsObj.opt(k) }

        return Parsed.Ok(
            Request(
                id = id,
                action = action,
                args = args,
                risk = json.optString("risk", "caution").lowercase(),
                timeoutMs = clampTimeout(action, if (json.has("timeoutMs")) json.optLong("timeoutMs") else null),
                reason = json.optString("reason", "").takeIf { it.isNotEmpty() },
                confirm = if (json.has("confirm")) json.optBoolean("confirm", true) else true
            )
        )
    }

    // ---------------------------------------------------------------- 响应

    fun okJson(
        id: String,
        elapsedMs: Long,
        data: JSONObject? = null,
        warnings: List<String> = emptyList()
    ): String = JSONObject().apply {
        put("v", VERSION)
        put("id", id)
        put("ok", true)
        put("elapsedMs", elapsedMs)
        put("data", data ?: JSONObject())
        put("warnings", org.json.JSONArray().also { arr -> warnings.forEach { arr.put(it) } })
        put("error", JSONObject.NULL)
    }.toString()

    fun errJson(
        id: String,
        elapsedMs: Long,
        code: String,
        message: String,
        step: Int? = null,
        retryAfterMs: Long? = null,
        reason: String? = null,
        warnings: List<String> = emptyList()
    ): String = JSONObject().apply {
        put("v", VERSION)
        put("id", id)
        put("ok", false)
        put("elapsedMs", elapsedMs)
        put("data", JSONObject.NULL)
        put("warnings", org.json.JSONArray().also { arr -> warnings.forEach { arr.put(it) } })
        put(
            "error",
            JSONObject().apply {
                put("code", code)
                put("message", message)
                put("step", step ?: JSONObject.NULL)
                put("retryAfterMs", retryAfterMs ?: JSONObject.NULL)
                put("reason", reason ?: JSONObject.NULL)
            }
        )
    }.toString()

    /** `/v1/ping` 的响应（匿名，只证明端口上是宏月） */
    fun pingJson(): String = JSONObject().apply {
        put("v", VERSION)
        put("app", APP_NAME)
    }.toString()
}
