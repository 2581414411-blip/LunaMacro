package com.nexus.agent.bridge

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.nexus.agent.accessibility.NexusAccessibilityService
import com.nexus.agent.copilot.CopilotEngine
import com.nexus.agent.input.ShizukuManager
import com.nexus.agent.macro.MacroPlayer
import com.nexus.agent.safety.SafetyPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * MPL 桥 —— 给「另一个脑」用的手（0.9.31，协议 v1 只读阶段）。
 *
 * ## 和 8080 那个口的区别（别混）
 *
 * | | `LocalAgentServer`（8080） | `MplServer`（18080） |
 * |---|---|---|
 * | 给谁 | 手表 / PC / 局域网设备 | 同一台手机上的 MPL |
 * | 绑哪 | 所有网卡（WiFi 上能连） | **只绑 127.0.0.1** |
 * | 鉴权 | 12 位 token，可走 `?token=` | 32 字节 token，**只认请求头** |
 * | 语义 | 聊天 / 状态 | 动作（读屏、打开应用…） |
 *
 * 合在一起用是不行的：一旦为了省事复用 8080，打开 MPL 就等于顺手把动作接口
 * 挂到了 WiFi 上，而那个口的 token 只有 12 位、还允许查询参数。
 *
 * ## 安全约定（写在代码里，免得以后自己都忘了）
 *
 * - 只绑 loopback，不绑 0.0.0.0；
 * - **不发任何 `Access-Control-*` 头**，OPTIONS 一律 404 —— 让浏览器预检直接失败；
 * - 不接受 `?token=`，只认 `X-MPL-Token`；
 * - 没有 token 时一律 401，且**不回 body**；
 * - 401 之外没有 4xx：业务错误一律 200 + `ok:false`（协议 §6）。
 */
object MplServer {

    private const val TAG = "MplServer"

    private const val PREFS = "mpl_bridge"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_TOKEN = "token"
    private const val KEY_PORT = "port"

    /** 请求头上限 */
    private const val MAX_HEAD = 16 * 1024

    /** 单次读超时 */
    private const val SO_TIMEOUT_MS = 10_000

    /** BUSY 建议客户端隔多久再来（协议 §6.1 `retryAfterMs`） */
    private const val BUSY_RETRY_MS = 1_000L

    /** 本进程标识：用来识别「上次跑一半进程没了」 */
    private val bootId: String = UUID.randomUUID().toString().take(8)

    @Volatile
    private var appCtx: Context? = null

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptThread: Thread? = null

    private val running = AtomicBoolean(false)
    private val workers = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "mpl-worker").apply { isDaemon = true }
    }

    @Volatile
    private var ledgerRef: ActionLedger? = null

    // ---------------------------------------------------------------- 配置

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun port(context: Context): Int = prefs(context).getInt(KEY_PORT, MplProtocol.PORT)

    @Synchronized
    fun setPort(context: Context, p: Int): Boolean {
        if (p !in 1024..65535) return false
        prefs(context).edit().putInt(KEY_PORT, p).apply()
        if (isRunning()) {
            stop()
            return start(context)
        }
        return true
    }

    /** 已配对的 token（没配就是空串）。**只在设置页显示，不进任何响应** */
    fun token(context: Context): String = prefs(context).getString(KEY_TOKEN, "").orEmpty()

    /**
     * 保存用户粘贴的配对码。
     *
     * 只做**格式**校验（32~128 位 hex）：内容对不对由 `health` 的 200/401 说了算，
     * 本地没法验证 —— 也不该假装能验证。
     */
    @Synchronized
    fun setToken(context: Context, raw: String): Boolean {
        val t = raw.trim().replace(" ", "").replace("-", "")
        if (t.length !in 32..128) return false
        if (!t.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return false
        prefs(context).edit().putString(KEY_TOKEN, t).apply()
        return true
    }

    @Synchronized
    fun clearToken(context: Context) {
        prefs(context).edit().remove(KEY_TOKEN).apply()
    }

    /** 用户把开关打开 / 关掉 */
    @Synchronized
    fun setEnabled(context: Context, enabled: Boolean): Boolean {
        appCtx = context.applicationContext
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (!enabled) {
            stop()
            return false
        }
        return start(context)
    }

    fun isRunning(): Boolean = running.get()

    fun currentPort(): Int = serverSocket?.localPort ?: 0

    /**
     * 按配置启动（App 启动时调用；开关关着就什么都不做）。
     *
     * 没配 token 也**不阻止启动** —— 监听归监听，没有 token 的请求一律 401。
     * 这样用户能在设置页看到"端口通了但没配对"，比"打不开服务、也不知道为什么"好排查。
     */
    fun startIfEnabled(context: Context): Boolean {
        appCtx = context.applicationContext
        if (!isEnabled(context)) return false
        return start(context)
    }

    @Synchronized
    fun start(context: Context): Boolean {
        appCtx = context.applicationContext
        if (running.get() && serverSocket?.isClosed == false) return true
        val p = port(context)
        return try {
            // 只绑 loopback：局域网上的设备连 SYN 都收不到
            val addr = InetAddress.getByName("127.0.0.1")
            val ss = ServerSocket(p, 8, addr)
            ss.reuseAddress = true
            serverSocket = ss
            running.set(true)
            acceptThread = Thread({ acceptLoop(ss) }, "mpl-accept").apply {
                isDaemon = true
                start()
            }
            Log.i(TAG, "MPL 桥已启动：127.0.0.1:$p（token=${if (token(context).isEmpty()) "未配对" else "已配置"}）")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "MPL 桥启动失败（端口 $p）：${e.javaClass.simpleName} ${e.message}")
            running.set(false)
            serverSocket = null
            false
        }
    }

    @Synchronized
    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }.onFailure { Log.w(TAG, "关闭失败：${it.message}") }
        serverSocket = null
        acceptThread = null
        Log.i(TAG, "MPL 桥已停止")
    }

    // ---------------------------------------------------------------- 接入

    private fun acceptLoop(ss: ServerSocket) {
        while (running.get() && !ss.isClosed) {
            val socket = try {
                ss.accept()
            } catch (e: Throwable) {
                if (running.get()) Log.w(TAG, "accept 异常：${e.javaClass.simpleName}")
                break
            }
            runCatching { workers.execute { handleSafely(socket) } }
                .onFailure { runCatching { socket.close() } }
        }
    }

    private fun handleSafely(socket: Socket) {
        try {
            socket.soTimeout = SO_TIMEOUT_MS
            handle(socket)
        } catch (e: Throwable) {
            Log.w(TAG, "处理失败：${e.javaClass.simpleName} ${e.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun handle(socket: Socket) {
        val ctx = appCtx ?: return
        val input = socket.getInputStream()
        val out = socket.getOutputStream()

        val head = readHead(input)
        if (head.isEmpty()) return
        val text = String(head, Charsets.ISO_8859_1)
        val lines = text.split("\r\n").filter { it.isNotEmpty() }
        val first = lines.firstOrNull() ?: return
        val parts = first.split(" ")
        if (parts.size < 2) return writeEmpty(out, 400)

        val method = parts[0].uppercase(Locale.ROOT)
        val path = parts[1].substringBefore('?')   // 查询参数一律忽略（协议禁止 ?token=）

        val headers = HashMap<String, String>()
        for (i in 1 until lines.size) {
            val idx = lines[i].indexOf(':')
            if (idx > 0) {
                headers[lines[i].substring(0, idx).trim().lowercase(Locale.ROOT)] =
                    lines[i].substring(idx + 1).trim()
            }
        }

        // OPTIONS 一律 404：浏览器预检过不去，跨域 POST 就发不出来
        if (method == "OPTIONS") return writeEmpty(out, 404)

        when {
            method == "GET" && path == "/v1/ping" -> writeJson(out, 200, MplProtocol.pingJson())

            method == "GET" && path == "/v1/health" -> {
                if (!authorized(ctx, headers)) return writeEmpty(out, 401)
                writeJson(out, 200, healthJson(ctx))
            }

            method == "POST" && path == "/v1/action" -> {
                if (!authorized(ctx, headers)) return writeEmpty(out, 401)
                val len = headers["content-length"]?.toIntOrNull() ?: 0
                if (len > MplProtocol.MAX_BODY) return writeEmpty(out, 413)
                val body = readBody(input, len)
                handleAction(ctx, body, out)
            }

            else -> writeEmpty(out, 404)
        }
    }

    private fun authorized(ctx: Context, headers: Map<String, String>): Boolean {
        val expected = token(ctx)
        if (expected.isEmpty()) return false
        val got = headers["x-mpl-token"] ?: return false
        // 常量时间比较：别让"前几位对不对"从耗时里漏出去
        return MessageDigest.isEqual(got.toByteArray(), expected.toByteArray())
    }

    // ---------------------------------------------------------------- 动作

    private fun handleAction(ctx: Context, body: String, out: OutputStream) {
        when (val parsed = MplProtocol.parse(body)) {
            is MplProtocol.Parsed.Bad -> writeEmpty(out, parsed.httpStatus)

            is MplProtocol.Parsed.Biz -> writeJson(
                out, 200,
                MplProtocol.errJson(parsed.id, 0, parsed.code, parsed.message)
            )

            is MplProtocol.Parsed.Ok -> runAction(ctx, parsed.req, out)
        }
    }

    private fun runAction(ctx: Context, req: MplProtocol.Request, out: OutputStream) {
        val ledger = ledger(ctx)
        val start = System.currentTimeMillis()

        when (val begin = ledger.begin(req.id)) {
            is ActionLedger.Begin.Replay -> {
                Log.i(TAG, "[重放] ${req.id.take(8)} ${req.action}（没有执行第二次）")
                writeJson(out, 200, begin.response)
            }

            is ActionLedger.Begin.Unknown -> {
                val resp = MplProtocol.errJson(
                    req.id, 0, MplProtocol.Code.UNKNOWN_RESULT,
                    "上次执行到一半宏月被关了，结果不确定，让他看一眼"
                )
                writeJson(out, 200, resp)
            }

            is ActionLedger.Begin.InFlight -> {
                val waited = ledger.awaitDone(req.id, req.timeoutMs + 2_000)
                val resp = (waited as? ActionLedger.Begin.Replay)?.response
                    ?: MplProtocol.errJson(
                        req.id, System.currentTimeMillis() - start, MplProtocol.Code.UNKNOWN_RESULT,
                        "同一个请求还在跑，没等到结果"
                    )
                writeJson(out, 200, resp)
            }

            is ActionLedger.Begin.Fresh -> {
                val result = execute(ctx, req)
                // BUSY / NEEDS_CONFIRM 不能记账：对手要按同一个 id 重试，
                // 记成 done 之后重试只会拿到同一份错误，永远做不成。
                if (result.code == MplProtocol.Code.BUSY || result.code == MplProtocol.Code.NEEDS_CONFIRM) {
                    ledger.forget(req.id)
                } else {
                    ledger.done(req.id, result.json)
                }
                writeJson(out, 200, result.json)
                MplLog.add(
                    ctx,
                    MplLog.Entry(
                        at = System.currentTimeMillis(),
                        id = req.id,
                        action = req.action,
                        ms = System.currentTimeMillis() - start,
                        ok = result.code == null,
                        note = result.code ?: "ok"
                    )
                )
                Log.i(
                    TAG,
                    "[动作] ${req.id.take(8)} ${req.action} ${System.currentTimeMillis() - start}ms " +
                        (result.code ?: "ok")
                )
            }
        }
    }

    private data class Exec(val json: String, val code: String?)

    private fun execute(ctx: Context, req: MplProtocol.Request): Exec {
        val start = System.currentTimeMillis()

        if (!MplProtocol.isSupported(req.action)) {
            return Exec(
                MplProtocol.errJson(
                    req.id, 0, MplProtocol.Code.UNKNOWN_ACTION,
                    "本版本不开放动作「${req.action}」，先看 health.actions"
                ),
                MplProtocol.Code.UNKNOWN_ACTION
            )
        }

        busyReason(ctx, req.action)?.let { why ->
            return Exec(
                MplProtocol.errJson(
                    req.id, 0, MplProtocol.Code.BUSY, why, retryAfterMs = BUSY_RETRY_MS
                ),
                MplProtocol.Code.BUSY
            )
        }

        val fg = NexusAccessibilityService.currentForegroundPackage()
        return try {
            val outcome = runBlocking {
                withTimeout(req.timeoutMs) {
                    MplSafety.check(ctx, req.action, req.args, req.confirm, fg)
                    MplActionRouter.execute(ctx, req)
                }
            }
            Exec(
                MplProtocol.okJson(req.id, System.currentTimeMillis() - start, outcome.data, outcome.warnings),
                null
            )
        } catch (e: MplSafety.Denied) {
            Exec(
                MplProtocol.errJson(
                    req.id, System.currentTimeMillis() - start, e.code, e.message,
                    retryAfterMs = e.retryAfterMs, reason = e.reason
                ),
                e.code
            )
        } catch (e: TimeoutCancellationException) {
            // 必须排在 CancellationException 前面：前者是后者子类
            Exec(
                MplProtocol.errJson(
                    req.id, System.currentTimeMillis() - start, MplProtocol.Code.TIMEOUT,
                    "超过 ${req.timeoutMs}ms 还没做完"
                ),
                MplProtocol.Code.TIMEOUT
            )
        } catch (e: CancellationException) {
            Exec(
                MplProtocol.errJson(
                    req.id, System.currentTimeMillis() - start, MplProtocol.Code.CANCELLED,
                    "被中断了"
                ),
                MplProtocol.Code.CANCELLED
            )
        } catch (e: Throwable) {
            Log.w(TAG, "动作异常：${e.javaClass.simpleName} ${e.message}")
            Exec(
                MplProtocol.errJson(
                    req.id, System.currentTimeMillis() - start, MplProtocol.Code.UNKNOWN_RESULT,
                    "执行时出了意外（${e.javaClass.simpleName}），结果不确定"
                ),
                MplProtocol.Code.UNKNOWN_RESULT
            )
        }
    }

    /**
     * 忙闸。
     *
     * 只读动作不受限 —— "边说边看一眼屏幕"必须能做；
     * 写动作在三种情况下拦：引擎正在跑别的任务、宏正在回放、或者用户正停在宏月自己的界面上
     * （那时候 `click_text("设置")` 会点在宏月的设置页上，而不是他以为的那个应用）。
     */
    private fun busyReason(ctx: Context, action: String): String? {
        if (MplProtocol.isReadOnly(action)) {
            // 只有 open_app 算"会改变前台"，但它不碰界面内容，仍放行
            return null
        }
        if (CopilotEngine.isBusy) return "她正在处理别的请求"
        if (MacroPlayer.isPlaying) return "宏正在回放"
        val fg = NexusAccessibilityService.currentForegroundPackage()
        if (fg != null && fg == ctx.packageName) return "你现在就在宏月的界面上"
        return null
    }

    // ---------------------------------------------------------------- health

    fun healthJson(ctx: Context): String {
        val fg = NexusAccessibilityService.currentForegroundPackage()
        val accessibility = NexusAccessibilityService.isBound()
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager

        val provider = runCatching { ShizukuManager.getRunningProvider(ctx) }.getOrDefault("none")
        val injection = when {
            provider == "shizuku" || provider == "stellar" -> "shizuku"
            accessibility -> "accessibility"
            else -> "none"
        }

        return JSONObject().apply {
            put("v", MplProtocol.VERSION)
            put("app", MplProtocol.APP_NAME)
            put("version", versionName(ctx))
            put("port", currentPort().takeIf { it > 0 } ?: port(ctx))
            put("ts", System.currentTimeMillis())
            put("ready", accessibility)
            put("injection", injection)
            put("accessibility", accessibility)
            put("screenOn", pm?.isInteractive ?: true)
            put("keyguard", km?.isKeyguardLocked ?: false)
            put("foreground", fg ?: JSONObject.NULL)
            put("sensitiveUi", SafetyPolicy.isSensitiveUi(ctx, fg))
            put("busy", CopilotEngine.isBusy || MacroPlayer.isPlaying)
            put("actions", JSONArray().also { arr -> MplProtocol.actions().forEach { arr.put(it) } })
            put(
                "limits",
                JSONObject().apply {
                    put("defaultTimeoutMs", MplProtocol.DEFAULT_TIMEOUT_MS)
                    put("maxTimeoutMs", MplProtocol.MAX_TIMEOUT_MS)
                }
            )
        }.toString()
    }

    private fun versionName(ctx: Context): String = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    // ---------------------------------------------------------------- 账本

    private fun ledger(ctx: Context): ActionLedger = ledgerRef ?: synchronized(this) {
        ledgerRef ?: ActionLedger(PrefsStore(prefs(ctx)), bootId).also { ledgerRef = it }
    }

    /** 账本落 SharedPreferences。key 前缀 `ledger:` 与配置项区分开 */
    private class PrefsStore(private val sp: android.content.SharedPreferences) : ActionLedger.Store {
        override fun get(id: String): String? = sp.getString(PREFIX + id, null)
        override fun put(id: String, value: String) {
            sp.edit().putString(PREFIX + id, value).apply()
        }
        override fun all(): Map<String, String> =
            sp.all.entries.filter { it.key.startsWith(PREFIX) }
                .associate { it.key.removePrefix(PREFIX) to (it.value as? String).orEmpty() }

        override fun remove(id: String) {
            sp.edit().remove(PREFIX + id).apply()
        }

        companion object {
            const val PREFIX = "ledger:"
        }
    }

    /** 测试用：清掉账本（设置页"重置配对"时也顺手清） */
    fun clearLedger(ctx: Context) {
        ledgerRef = null
        prefs(ctx).edit().apply {
            prefs(ctx).all.keys.filter { it.startsWith(PrefsStore.PREFIX) }.forEach { remove(it) }
        }.apply()
    }

    // ---------------------------------------------------------------- HTTP 原语

    private fun readHead(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream(1024)
        var last = 0
        while (true) {
            val b = input.read()
            if (b == -1) break
            out.write(b)
            last = (last shl 8) or b
            if (last == 0x0D0A0D0A) break
            if (out.size() > MAX_HEAD) return ByteArray(0)
        }
        return out.toByteArray()
    }

    private fun readBody(input: InputStream, len: Int): String {
        if (len <= 0) return ""
        val buf = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = input.read(buf, read, len - read)
            if (n <= 0) break
            read += n
        }
        return String(buf, 0, read, Charsets.UTF_8)
    }

    private fun writeJson(out: OutputStream, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Connection: close\r\n")
            // 刻意不发 Access-Control-* 头
            append("\r\n")
        }
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(bytes)
        out.flush()
    }

    private fun writeEmpty(out: OutputStream, status: Int) {
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
            append("Content-Length: 0\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        413 -> "Payload Too Large"
        else -> "Error"
    }

    /** 给设置页看的"本机地址"（永远是 loopback，不是局域网 IP） */
    fun loopbackAddress(context: Context): String = "127.0.0.1:${if (isRunning()) currentPort() else port(context)}"

    /** token 显示用：只给前后各 4 位 */
    fun maskedToken(context: Context): String {
        val t = token(context)
        if (t.isEmpty()) return "未配对"
        if (t.length <= 12) return t
        return "${t.take(4)}…${t.takeLast(4)}（${t.length} 位）"
    }

    /** 单元测试 / 调试：造一个随机 token 便于本地自测 */
    fun randomToken(): String {
        val hex = "0123456789abcdef"
        return (1..64).map { hex[Random.nextInt(16)] }.joinToString("")
    }

    @Suppress("unused")
    private fun apiLevelNote() = Build.VERSION.SDK_INT
}
