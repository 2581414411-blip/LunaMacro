package com.nexus.agent.server

import android.content.Context
import android.util.Log
import com.nexus.agent.copilot.CopilotEngine
import com.nexus.agent.copilot.PersonaEngine
import com.nexus.agent.util.MiniJson
import kotlinx.coroutines.runBlocking
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * 本地 HTTP 接口 —— 给「另一块屏幕」用的门（0.9.0 批次四）。
 *
 * ## 为什么要这个
 *
 * 现在这套东西只能被**这台手机上的这个 App** 使用：UI 是 Compose，逻辑全在进程内。
 * 想让手表（Wear OS）、鸿蒙 NEXT、PC 上的窗口也用它，只有两条路：
 *  1. 每个端各写一份 SDK，把人格、记忆、路由全抄一遍 —— 三个月后必然分叉；
 *  2. 在手机上开一个**极小的、稳定的 HTTP 契约**，别的端只学会两个接口就行。
 *
 * 这里选 2。它不追求通用，只追求「十年后还能连上」：
 *  - 没有第三方依赖（原生 [ServerSocket]，一行 JSON 手搓），不担心库弃用；
 *  - 只有两个业务接口：[API_STATUS] 和 [API_CHAT]；
 *  - 鉴权是一个固定 Token，够挡住同一 WiFi 下的邻居，不假装是 TLS。
 *
 * ## 契约
 *
 * ```
 * GET  /api/ping     → {"ok":true}                        不需要 Token（探活）
 * GET  /api/status   → {"ok":true,"name":..,"mood":..,"affinity":..,"busy":..}
 * POST /api/chat     → {"ok":true,"reply":".."}   body: {"message":"在吗"}
 * ```
 *
 * Token 走 `X-Auth-Token` 头或 `?token=` 查询参数。
 *
 * ## 安全边界（写在代码里，免得以后自己都忘了）
 *
 * - 默认**关闭**，用户亲手打开才开始监听；
 * - 打开时自动生成 12 位 Token，界面上要看得到、能复制、能重置；
 * - 只绑定在局域网上用，不做端口转发也不做 UPnP；
 * - 请求体上限 [MAX_BODY]，任何异常都只返回 4xx 并断开，不抛到主流程。
 */
object LocalAgentServer {

    private const val TAG = "LocalAgentServer"

    private const val PREFS = "luna_crossdevice"
    private const val KEY_ENABLED = "allow_external_connections"
    private const val KEY_TOKEN = "access_token"
    private const val KEY_PORT = "server_port"

    /** 默认端口；被占用时用户可以在设置里换 */
    const val DEFAULT_PORT = 8080

    /** 路径常量（跨端 SDK 直接抄这两个字符串） */
    const val API_PING = "/api/ping"
    const val API_STATUS = "/api/status"
    const val API_CHAT = "/api/chat"

    /** 请求体上限：一句聊天不需要 64KB，超了就是有人乱打 */
    private const val MAX_BODY = 64 * 1024

    /** 请求头上限 */
    private const val MAX_HEAD = 16 * 1024

    /** 单次读超时 */
    private const val SO_TIMEOUT_MS = 15_000

    /** 聊天最多等多久拿到「说话权」（另一个人可能在用 UI 聊天） */
    private const val CHAT_LOCK_WAIT_MS = 90_000L

    @Volatile
    private var appCtx: Context? = null

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptThread: Thread? = null

    private val running = AtomicBoolean(false)

    /** 聊天串行闸：模型实例只有一份，两个请求同时推理会互相拖死 */
    private val chatGate = Semaphore(1, true)

    private val workers = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "local-agent-http").apply { isDaemon = true }
    }

    // ---------------------------------------------------------------- 开关 / Token

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 用户是否允许外部设备连接（默认关） */
    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    /** 端口（用户可改，默认 [DEFAULT_PORT]） */
    fun port(context: Context): Int =
        prefs(context).getInt(KEY_PORT, DEFAULT_PORT).let {
            if (it in 1024..65535) it else DEFAULT_PORT
        }

    fun setPort(context: Context, port: Int) {
        val p = if (port in 1024..65535) port else DEFAULT_PORT
        prefs(context).edit().putInt(KEY_PORT, p).apply()
    }

    /**
     * 取访问 Token；没有就生成一个。
     *
     * 生成时机放在「用户打开开关」和「UI 要显示」两处，
     * 这样用户永远不会看到一个空 Token 的输入框。
     */
    fun token(context: Context): String {
        val p = prefs(context)
        val existing = p.getString(KEY_TOKEN, "")?.trim().orEmpty()
        if (existing.isNotEmpty()) return existing
        val fresh = generateToken()
        p.edit().putString(KEY_TOKEN, fresh).apply()
        return fresh
    }

    /** 换一个 Token（旧客户端的请求会立刻失效） */
    fun regenerateToken(context: Context): String {
        val fresh = generateToken()
        prefs(context).edit().putString(KEY_TOKEN, fresh).apply()
        Log.i(TAG, "访问 Token 已重置")
        return fresh
    }

    /** 12 位、去掉容易看错的 0/O/1/I，方便用户在手表上手输 */
    private fun generateToken(): String {
        val alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
        val rnd = Random(System.currentTimeMillis())
        return (1..12).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
    }

    // ---------------------------------------------------------------- 生命周期

    fun isRunning(): Boolean = running.get()

    /** 当前监听端口（没在跑就是配置里的端口） */
    fun currentPort(): Int = serverSocket?.localPort ?: 0

    /**
     * 按配置启动（App 启动时调用；开关关着就什么都不做）。
     *
     * @return true = 现在确实在监听
     */
    fun startIfEnabled(context: Context): Boolean {
        appCtx = context.applicationContext
        if (!isEnabled(context)) {
            Log.i(TAG, "外部设备接入未开启，不启动 HTTP 服务")
            return false
        }
        return start(context)
    }

    /** 用户把开关打开 / 关掉时调 */
    fun setEnabled(context: Context, enabled: Boolean): Boolean {
        appCtx = context.applicationContext
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (!enabled) {
            stop()
            return false
        }
        // 开门前必须保证有 Token，否则等于在 WiFi 上挂了个谁都能用的口子
        token(context)
        return start(context)
    }

    /**
     * 开始监听。
     *
     * 失败（端口被占用等）只打日志并返回 false —— 设置页会用返回值告诉用户「换个端口」。
     */
    @Synchronized
    fun start(context: Context): Boolean {
        appCtx = context.applicationContext
        if (running.get() && serverSocket?.isClosed == false) {
            Log.i(TAG, "HTTP 服务已在 ${currentPort()} 运行")
            return true
        }
        val p = port(context)
        return try {
            val ss = ServerSocket(p)
            ss.reuseAddress = true
            serverSocket = ss
            running.set(true)
            val t = Thread({ acceptLoop(ss) }, "local-agent-accept").apply {
                isDaemon = true
                start()
            }
            acceptThread = t
            Log.i(TAG, "HTTP 服务已启动：http://${localIp()}:$p ，Token 已就绪")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "HTTP 服务启动失败（端口 $p）：${e.javaClass.simpleName} ${e.message}")
            running.set(false)
            serverSocket = null
            false
        }
    }

    @Synchronized
    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }.onFailure { Log.w(TAG, "关闭监听失败：${it.message}") }
        serverSocket = null
        acceptThread = null
        Log.i(TAG, "HTTP 服务已停止")
    }

    /** 本机在局域网里的 IPv4（拿不到就返回 127.0.0.1，至少让用户知道要连本机） */
    fun localIp(): String {
        return runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList()
                ?.flatMap { it.inetAddresses.toList() }
                ?.firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                ?.hostAddress
        }.getOrNull() ?: "127.0.0.1"
    }

    // ---------------------------------------------------------------- 接入循环

    private fun acceptLoop(ss: ServerSocket) {
        while (running.get() && !ss.isClosed) {
            val socket = try {
                ss.accept()
            } catch (e: Throwable) {
                // stop() 会 close()，这里必然抛一次；running 为 false 就正常退出
                if (running.get()) Log.w(TAG, "accept 异常：${e.javaClass.simpleName}")
                break
            }
            runCatching {
                workers.execute { handleSafely(socket) }
            }.onFailure {
                Log.w(TAG, "派发请求失败：${it.message}")
                runCatching { socket.close() }
            }
        }
        Log.i(TAG, "接入循环退出")
    }

    private fun handleSafely(socket: Socket) {
        try {
            socket.soTimeout = SO_TIMEOUT_MS
            handle(socket)
        } catch (e: Throwable) {
            Log.w(TAG, "处理请求失败：${e.javaClass.simpleName} ${e.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    private data class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: String
    )

    private fun handle(socket: Socket) {
        val input = socket.getInputStream()
        val headBytes = readHead(input)
        if (headBytes.isEmpty()) return
        val head = String(headBytes, Charsets.ISO_8859_1)
        val lines = head.split("\r\n").filter { it.isNotEmpty() }
        val first = lines.firstOrNull() ?: return
        val parts = first.split(" ")
        if (parts.size < 2) {
            writeJson(socket.getOutputStream(), 400, error("bad_request"))
            return
        }
        val method = parts[0].uppercase(Locale.ROOT)
        val target = parts[1]
        val qIndex = target.indexOf('?')
        val path = if (qIndex >= 0) target.substring(0, qIndex) else target
        val query = if (qIndex >= 0) parseQuery(target.substring(qIndex + 1)) else emptyMap()
        val headers = HashMap<String, String>()
        for (i in 1 until lines.size) {
            val idx = lines[i].indexOf(':')
            if (idx > 0) {
                headers[lines[i].substring(0, idx).trim().lowercase(Locale.ROOT)] =
                    lines[i].substring(idx + 1).trim()
            }
        }

        val out = BufferedOutputStream(socket.getOutputStream())
        // 浏览器/网页端要跨域调用，预检直接放行（服务本身有 Token 兜着）
        if (method == "OPTIONS") {
            writeEmpty(out, 204)
            return
        }

        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (contentLength in 1..MAX_BODY) readBody(input, contentLength) else ""

        val req = Request(method, path, query, headers, body)
        route(req, out)
    }

    private fun route(req: Request, out: OutputStream) {
        when (req.path) {
            API_PING -> writeJson(out, 200, """{"ok":true}""")

            API_STATUS -> {
                if (!authorized(req)) {
                    writeJson(out, 401, error("unauthorized"))
                    return
                }
                writeJson(out, 200, statusJson())
            }

            API_CHAT -> {
                if (!authorized(req)) {
                    writeJson(out, 401, error("unauthorized"))
                    return
                }
                if (req.method != "POST") {
                    writeJson(out, 405, error("method_not_allowed"))
                    return
                }
                handleChat(req, out)
            }

            else -> writeJson(out, 404, error("not_found"))
        }
    }

    private fun authorized(req: Request): Boolean {
        val ctx = appCtx ?: return false
        val expected = token(ctx)
        if (expected.isEmpty()) return false
        val given = req.headers["x-auth-token"] ?: req.query["token"].orEmpty()
        return given.isNotEmpty() && given == expected
    }

    /** POST /api/chat：转给 [PersonaEngine]，返回它说的那句话 */
    private fun handleChat(req: Request, out: OutputStream) {
        val ctx = appCtx ?: run {
            writeJson(out, 503, error("not_initialized"))
            return
        }
        val message = extractMessage(req.body)
        if (message.isBlank()) {
            writeJson(out, 400, error("message_required"))
            return
        }
        // 手机上正在用 UI 聊天/干活时不要再挤进去：模型实例只有一份
        if (CopilotEngine.isBusy) {
            writeJson(out, 409, error("agent_busy"))
            return
        }
        val gotLock = runCatching {
            chatGate.tryAcquire(CHAT_LOCK_WAIT_MS, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        if (!gotLock) {
            writeJson(out, 409, error("agent_busy"))
            return
        }
        val startedAt = System.currentTimeMillis()
        try {
            // respond 是唯一入口：人格、FlexiMem 召回、周记、云端路由全在里面。
            // 这里不带截图（外部请求没有「他此刻的屏幕」这回事），
            // onNotice 传个空实现把「本轮没截到屏幕」这类系统提示吞掉 —— API 只要正文。
            val reply = runBlocking {
                runCatching {
                    PersonaEngine.respond(ctx, message, onNotice = {})
                }.getOrNull()
            }
            if (reply.isNullOrBlank()) {
                writeJson(out, 503, error("no_reply"))
                return
            }
            // 同步到手机上的聊天流，用户在手机上也能看到「谁在跟我说话」
            runCatching {
                PersonaEngine.addUserItem(message)
                PersonaEngine.addAssistantItem(reply)
            }
            val cost = System.currentTimeMillis() - startedAt
            Log.i(TAG, "外部聊天完成：${message.length} 字 → ${reply.length} 字，耗时 ${cost}ms")
            writeJson(
                out,
                200,
                """{"ok":true,"reply":${quote(reply)},"cost_ms":$cost}"""
            )
        } finally {
            runCatching { chatGate.release() }
        }
    }

    /**
     * 从请求体里取消息。
     *
     * 兼容三种写法：`{"message":".."}`、`{"text":".."}`、以及裸文本 ——
     * 手表端和 PC 端各自手搓客户端时，最常犯的错就是字段名不一样。
     */
    internal fun extractMessage(body: String): String {
        val raw = body.trim()
        if (raw.isEmpty()) return ""
        if (raw.startsWith("{")) {
            val obj = runCatching { MiniJson.parseObject(raw) }.getOrNull()
            val txt = (obj?.get("message") as? String)
                ?: (obj?.get("text") as? String)
                ?: (obj?.get("content") as? String)
            if (!txt.isNullOrBlank()) return txt.trim().take(2000)
            return ""
        }
        return raw.take(2000)
    }

    // ---------------------------------------------------------------- 状态

    private fun statusJson(): String {
        val ctx = appCtx ?: return error("not_initialized")
        val p = PersonaEngine.profileSnapshot()
        val version = runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrNull().orEmpty()
        return buildString {
            append("{\"ok\":true")
            append(",\"name\":").append(quote(p.name))
            append(",\"mood\":").append(quote(p.mood))
            append(",\"affinity\":").append(p.affinity)
            append(",\"busy\":").append(CopilotEngine.isBusy)
            append(",\"memory_turns\":").append(PersonaEngine.turnCount())
            append(",\"version\":").append(quote(version))
            append(",\"port\":").append(currentPort())
            append('}')
        }
    }

    // ---------------------------------------------------------------- HTTP 原语

    /** 读到 `\r\n\r\n` 为止（含），返回原始字节 */
    private fun readHead(input: InputStream): ByteArray {
        val buf = ByteArrayOutputStream(512)
        var matched = 0
        while (buf.size() < MAX_HEAD) {
            val b = input.read()
            if (b < 0) break
            buf.write(b)
            matched = when {
                b == 13 && (matched == 0 || matched == 2) -> matched + 1
                b == 10 && (matched == 1 || matched == 3) -> matched + 1
                else -> if (b == 13) 1 else 0
            }
            if (matched == 4) break
        }
        return buf.toByteArray()
    }

    private fun readBody(input: InputStream, length: Int): String {
        val data = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(data, read, length - read)
            if (n < 0) break
            read += n
        }
        return String(data, 0, read, Charsets.UTF_8)
    }

    private fun parseQuery(raw: String): Map<String, String> =
        raw.split("&").mapNotNull { kv ->
            val i = kv.indexOf('=')
            if (i <= 0) null else kv.substring(0, i) to kv.substring(i + 1)
        }.toMap()

    private fun writeJson(out: OutputStream, code: Int, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Connection: close\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Headers: X-Auth-Token, Content-Type\r\n")
            append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            append("\r\n")
        }
        runCatching {
            out.write(head.toByteArray(Charsets.ISO_8859_1))
            out.write(bytes)
            out.flush()
        }
    }

    private fun writeEmpty(out: OutputStream, code: Int) {
        val head = "HTTP/1.1 $code ${reason(code)}\r\n" +
            "Content-Length: 0\r\n" +
            "Connection: close\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Access-Control-Allow-Headers: X-Auth-Token, Content-Type\r\n" +
            "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n\r\n"
        runCatching {
            out.write(head.toByteArray(Charsets.ISO_8859_1))
            out.flush()
        }
    }

    private fun reason(code: Int): String = when (code) {
        200 -> "OK"
        204 -> "No Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        409 -> "Conflict"
        503 -> "Service Unavailable"
        else -> "OK"
    }

    private fun error(code: String): String = """{"ok":false,"error":"$code"}"""

    /** JSON 字符串字面量（手搓：不引依赖，也不让 org.json 进纯 JVM 单测） */
    internal fun quote(s: String): String {
        val sb = StringBuilder(s.length + 8)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u").append("%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
