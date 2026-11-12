package com.nexus.agent.qq

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import com.nexus.agent.R
import com.nexus.agent.copilot.PersonaEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * QQ 通知监听 + 无感自动回复（0.9.25 批次 E）。
 *
 * ## 全链路
 *
 * ```
 * QQ 推通知 → 本服务 → 解析 → 四道闸 → 角色模型 → RemoteInput 静默发送
 *                                    ↘ 没有 RemoteInput → 本地通知「建议这样回」
 * ```
 *
 * ## 三条硬规矩
 *
 * 1. **绝不打开 QQ**。回复走 `Notification.Action.actionIntent` + `RemoteInput`，
 *    这是系统给通知配的「后台回复」通道，全程不需要拉起任何 Activity。
 *    这也是为什么不做「无障碍点进去发」—— 那会闪屏、会打断用户手上正在干的事。
 * 2. **先记账再发送**。`QqGuard.markSent()` 必须在 `actionIntent.send()` 之前调用：
 *    发送是异步的，等它回来再记，中间那几毫秒足够 QQ 推一条新通知绕开指纹闸。
 * 3. **默认只建议、不发送**。`QqConfig.isAutoSend` 默认 false —— 误发一条给老板的代价
 *    比少一个功能大得多，所以静默发送必须由用户显式打开。
 *
 * ## 为什么不用前台服务兜底
 *
 * `NotificationListenerService` 由系统绑定，本身就有较高的存活优先级（它是「系统需要一个
 * 监听器」而不是「App 想要常驻」）。再套一层前台服务只会多一条常驻通知，
 * 换不来额外的保活 —— 得不偿失。
 */
class QqNotificationService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "通知监听已连接：可以收到 QQ 通知了")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "通知监听断开（系统回收 / 用户关了权限）")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val s = sbn ?: return
        // 这个回调必须在几秒内返回，否则系统会认为监听器卡死。
        // 所以这里只做「解析 + 判断」，真正的模型调用丢到协程里。
        try {
            handle(s)
        } catch (t: Throwable) {
            Log.w(TAG, "处理通知时抛了 ${t.javaClass.simpleName}：${t.message}")
        }
    }

    private fun handle(sbn: StatusBarNotification) {
        val ctx = applicationContext
        val wantPkg = QqConfig.packageName(ctx)
        if (sbn.packageName != wantPkg) return

        val msg = QqMessageParser.parse(ctx, sbn) ?: return

        // ---- 抓取测试：上膛期间无论开关/身份都先把它交出去 ----
        // 用户就是为了「看清楚这条通知里到底有什么」才点的按钮，
        // 这时候用配置去过滤反而会让他抓不到想抓的那条（比如还没填对 userId）。
        if (QqTestProbe.isArmed) {
            val lines = msg.actions.map { a ->
                if (a.canReplySilently) {
                    "「${a.title}」可静默回复（RemoteInput 键：${a.remoteInputs.joinToString()}）"
                } else {
                    "「${a.title}」没有 RemoteInput"
                }
            }
            QqTestProbe.offer(msg, lines)
            Log.i(TAG, "[抓取] ${msg.pkg}/${msg.userId} ${msg.sender}: ${msg.text.take(30)}")
            return
        }

        if (!QqConfig.isEnabled(ctx)) return

        // ---- 闸 0：身份（应用分身靠 user.identifier 区分）----
        if (msg.userId != QqConfig.userId(ctx)) {
            Log.d(TAG, "跳过：userId=${msg.userId}，配置的是 ${QqConfig.userId(ctx)}")
            return
        }

        // ---- 闸 1~4：自发 / 回环 / 会话冷却 / 全局限流 ----
        val gate = QqGuard.check(
            title = msg.sender,
            content = msg.text,
            session = msg.session,
            selfNick = QqConfig.selfNickname(ctx)
        )
        if (gate != QqGuard.Gate.PASS) {
            Log.i(TAG, "跳过（${gate.zh}）：${msg.sender} → ${msg.text.take(20)}")
            QqStateManager.note("跳过：${gate.zh}")
            return
        }

        Log.i(TAG, "准备回复：${msg.sender}（群聊=${msg.isGroup}）→ ${msg.text.take(30)}")
        scope.launch { reply(ctx, msg) }
    }

    private suspend fun reply(ctx: Context, msg: QqMessage) {
        QqStateManager.begin()
        try {
            val draft = draftOf(ctx, msg)
            val action = QqMessageParser.replyActionOf(msg)

            if (action != null && QqConfig.isAutoSend(ctx)) {
                // 先记账再发：发送是异步的，中间来一条新通知就会绕开指纹闸
                QqGuard.markSent(msg.session, draft)
                val ok = sendSilently(action, draft)
                if (ok) {
                    Log.i(TAG, "已静默回复 ${msg.sender}：$draft")
                    QqStateManager.note("已回复 ${msg.sender}：$draft")
                } else {
                    // 发送本身失败（QQ 侧拒绝 / 通知已失效）：退化成建议，别让这条消息石沉大海
                    notifySuggestion(ctx, msg, draft)
                    QqStateManager.note("发送失败，改为建议：$draft")
                }
            } else {
                // 没开静默发送 / 通知上没有 RemoteInput —— 一律只给建议
                notifySuggestion(ctx, msg, draft)
                QqStateManager.note(
                    if (action == null) "通知上没有回复按钮，已给出建议：$draft"
                    else "未开静默发送，已给出建议：$draft"
                )
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.w(TAG, "自动回复失败：${t.javaClass.simpleName} ${t.message}")
            QqStateManager.note("回复失败：${t.message?.take(40)}")
        } finally {
            QqStateManager.finish()
        }
    }

    /**
     * 让角色模型写一句回复。
     *
     * 喂给模型的是**场景**而不是「用户说的话」—— 这跟房间里点她是同一个套路：
     * 直接丢一句「对方叫小明，说了在吗」会让模型以为自己在跟主人聊天，
     * 回一句「在的呀」就发给小明了。
     */
    private suspend fun draftOf(ctx: Context, msg: QqMessage): String {
        val scene = buildString {
            append("（你正在替主人回复 QQ。")
            append("对方叫「${msg.sender}」")
            if (msg.isGroup) append("，这是群聊（群里还有别人）")
            append("，他刚说：「${msg.text.take(120)}」。")
            append("请用你自己的语气给一句**简短**的回复，")
            append("像微信里随手回的那种，不要解释你是怎么想的，不要加引号，只给这一句话。）")
        }

        val raw = withTimeoutOrNull(REPLY_TIMEOUT_MS) {
            PersonaEngine.respond(
                context = ctx,
                userText = scene,
                // 不读屏：这一轮的主角是 QQ 消息，看手机屏幕只会让回答跑偏
                allowScreenRead = false
            )
        }?.trim()

        val cleaned = sanitize(raw)
        if (cleaned.isNullOrBlank()) {
            Log.i(TAG, "模型没给出可用回复，用本地兜底")
            return fallbackOf(msg)
        }
        return cleaned
    }

    /**
     * 把模型输出削成「能直接发出去的一句话」。
     *
     * 小模型常见的三种坏输出：
     * - 带引号（`"好的呀"`）—— 发出去对方会看到引号；
     * - 带前缀（`回复：好的呀`）—— 同上看得出是机器人；
     * - 写太长 —— QQ 通知回复框塞不下，而且不像人说话。
     */
    private fun sanitize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var s = raw.trim()
        // 去掉整体包裹的引号
        s = s.removeSurrounding("\"", "\"").removeSurrounding("“", "”").trim()
        // 去掉「回复：」「答复：」这类前缀
        s = s.replace(Regex("^(回复|答复|回答|我说)[:：]\\s*"), "")
        // 多段的只留第一段（模型爱写解释）
        s = s.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (s.isEmpty()) return null
        return s.take(MAX_REPLY_LEN)
    }

    /** 模型不可用时的兜底：宁可回一句普通的，也别让对方干等 */
    private fun fallbackOf(msg: QqMessage): String = when {
        msg.text.contains("?") || msg.text.contains("？") -> "在的，稍等一下哈"
        msg.isGroup -> "哈哈哈哈"
        else -> "在呢，等会儿回你"
    }

    /**
     * 真正发出去。
     *
     * `RemoteInput.addResultsToIntent` + `actionIntent.send()` 是系统给通知配的后台回复通道，
     * 全程不拉起 QQ，也不会闪一下界面 —— 这就是「静默」的全部含义。
     */
    private fun sendSilently(action: QqAction, text: String): Boolean {
        val raw = action.raw ?: return false
        val inputs = raw.remoteInputs ?: return false
        if (inputs.isEmpty()) return false
        return runCatching {
            val intent = Intent()
            val results = Bundle()
            for (ri in inputs) {
                results.putCharSequence(ri.resultKey, text)
            }
            android.app.RemoteInput.addResultsToIntent(inputs, intent, results)
            raw.actionIntent.send(this, 0, intent)
            true
        }.getOrElse {
            Log.w(TAG, "静默发送失败：${it.javaClass.simpleName} ${it.message}")
            false
        }
    }

    /**
     * 发一条本机通知「建议这样回」。
     *
     * 没有 RemoteInput 的 QQ 版本、或者用户没开静默发送时走这里。
     * 点通知直接进 App 聊天页 —— 用户看完可以自己复制过去发。
     */
    private fun notifySuggestion(ctx: Context, msg: QqMessage, draft: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            nm.getNotificationChannel(CHANNEL_ID) == null
        ) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "QQ 回复建议",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = "她替你想的回复，你可以自己发出去" }
            )
        }

        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
        val pi = launch?.let {
            PendingIntent.getActivity(
                ctx, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("${msg.sender}：${msg.text.take(20)}")
            .setContentText("建议回复：$draft")
            .setStyle(NotificationCompat.BigTextStyle().bigText("建议回复：$draft"))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pi)
            .build()

        runCatching { nm.notify(SUGGESTION_NOTIFY_ID, n) }
            .onFailure { Log.w(TAG, "建议通知发不出去（可能没给通知权限）", it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "监听服务销毁")
    }

    companion object {
        private const val TAG = "QqAutoReply"
        private const val CHANNEL_ID = "luna_qq_reply"
        private const val SUGGESTION_NOTIFY_ID = 0x0A01
        private const val REPLY_TIMEOUT_MS = 25_000L

        /** 一句话的上限：再长就不像随手回的了，QQ 的通知回复框也放不下 */
        private const val MAX_REPLY_LEN = 60
    }
}
