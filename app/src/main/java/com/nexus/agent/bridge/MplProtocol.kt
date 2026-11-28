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

    /**
     * 陈旧请求的宽限（协议 §4.1）。
     *
     * `age > timeoutMs + 宽限` 就拒绝执行。宽限只为容忍一次网络往返，
     * **不是时钟容差** —— 两端在同一台设备上共用一个系统时钟。
     */
    const val STALE_GRACE_MS = 2_000L

    /**
     * 动作后"等界面稳定"的两个数（协议 v1.2 常量，两边共用一份）。
     *
     * `SETTLE_QUIET_MS`：收到第一条内容变化事件后，再静默这么久才取新指纹。
     * `SETTLE_MAX_MS`：硬上限。**它不是保险，是主路径** ——
     * 游戏 / SurfaceView / FLAG_SECURE 一个事件都不产生，每次都走满这个数；
     * 列表惯性滚动 16ms 一个事件，静默条件也永远不成立，同样靠它兜底。
     *
     * 为什么 800 不是 600：高刷机上 600 偶发不够，取到一半的界面当最终态，
     * 代价比每次多等 200ms 高得多。
     */
    const val SETTLE_QUIET_MS = 120L
    const val SETTLE_MAX_MS = 800L

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
        val confirm: Boolean,
        /** 客户端发出时的 epoch 毫秒（v1.1 可选字段）；缺省 = 按新鲜处理 */
        val sentAt: Long? = null
    )

    /**
     * 请求是不是已经「过期」了（协议 §4.1）。
     *
     * 存在的理由：系统冻结进程时内核照常完成握手、请求留在 socket 缓冲区，
     * `accept()` 要等解冻才返回。不拦的话，用户十分钟后随手打开宏月，
     * 积压的 `click_text("发送")` 会在解冻瞬间一起执行 —— 屏幕上自己动一下。
     * 超时只是没做成，这个是做错了。
     *
     * 没有 `sentAt` 的请求（旧客户端）一律按新鲜处理：宁可少拦，也不误杀。
     *
     * @param now 由调用方传入 —— 这样这个函数是纯的，能被单测打靶
     */
    fun isStale(req: Request, now: Long): Boolean {
        val sentAt = req.sentAt ?: return false
        // 客户端时钟比服务端快（age 为负）不算过期，只拦「放太久」的
        return now - sentAt > req.timeoutMs + STALE_GRACE_MS
    }

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
                confirm = if (json.has("confirm")) json.optBoolean("confirm", true) else true,
                sentAt = if (json.has("sentAt")) json.optLong("sentAt", 0L).takeIf { it > 0 } else null
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

    /**
     * 401 的响应体（传输层鉴权失败，**不是** §6.1 那 12 个业务错误码之一）。
     *
     * 0.9.31 的 401 是空 body，客户端只拿得到状态码，没法区分「配对码不对」和「没连上」。
     * 协议正文没规定 401 必须无 body，所以这里补上不算改协议、不用换哈希。
     */
    fun authFailJson(): String = JSONObject().apply {
        put("v", VERSION)
        put("ok", false)
        put("error", JSONObject().apply {
            put("code", "UNAUTHORIZED")
            put("message", "配对码不正确，或宏月侧还没填配对码")
        })
    }.toString()
}
