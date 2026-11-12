package com.nexus.agent.qq

import android.app.Notification
import android.content.Context
import android.os.Build
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * 一条从 QQ 通知里解析出来的消息（0.9.25 批次 E）。
 *
 * @property session      会话标识。冷却/指纹都按它分组 —— 群聊里不同群必须分开算，
 *                        否则 A 群回过一次会让 B 群也进冷却。
 * @property sender       发言人昵称（MessagingStyle 里更准；兜底用标题）
 * @property isGroup      是否群聊。群聊的标题通常是「群名」，单人标题是对方昵称
 * @property actions      通知上带的按钮（含能不能用 RemoteInput 直接回）
 */
data class QqMessage(
    val pkg: String,
    val userId: Int,
    val key: String,
    val session: String,
    val title: String,
    val sender: String,
    val text: String,
    val isGroup: Boolean,
    val actions: List<QqAction>,
    val fromMessagingStyle: Boolean
)

/**
 * 通知上的一个按钮。
 *
 * @property remoteInputs 可用的 RemoteInput 结果键。非空 = 能静默回复
 */
data class QqAction(
    val title: String,
    val remoteInputs: List<String>,
    /**
     * 原始 action。发送时必须用它 —— 里面才有 `PendingIntent` 和真正的 `RemoteInput[]`。
     *
     * 不参与 equals/hashCode 的语义（data class 会带上它，但没人拿 QqAction 做比较）。
     */
    val raw: android.app.Notification.Action? = null
) {
    val canReplySilently: Boolean get() = remoteInputs.isNotEmpty()
}

/**
 * 通知 → [QqMessage]（0.9.25 批次 E）。
 *
 * ## 为什么优先 MessagingStyle
 *
 * QQ 的通知有两条路径：
 * - 支持会话通知（Android 7+ 的 MessagingStyle）时，`EXTRA_TEXT` 常常是一句
 *   「你收到了一条消息」这种汇总文案，真正的内容在 style 里；
 * - 老版本或某些渠道只能读 `EXTRA_TITLE` / `EXTRA_TEXT`。
 *
 * 所以先拆 style，拆不到再退回 extras —— 反过来就会把「3 条新消息」当成对方说的话回过去。
 */
object QqMessageParser {

    private const val TAG = "QqParse"

    fun parse(context: Context, sbn: StatusBarNotification): QqMessage? {
        val n = sbn.notification ?: return null
        val extras = n.extras ?: return null

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val rawText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()

        // ---- 1. 先拆 MessagingStyle ----
        val style = runCatching {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        }.getOrNull()

        val lastMsg = style?.messages?.lastOrNull()
        val styleText = lastMsg?.text?.toString()?.trim().orEmpty()
        val styleSender = senderNameOf(lastMsg)

        // ---- 2. 消息内容优先级 ----
        val text = styleText.ifEmpty { rawText }
        val sender = styleSender.ifEmpty { title }
        if (text.isEmpty()) {
            Log.d(TAG, "通知里没有可读内容（pkg=${sbn.packageName} title=$title），丢弃")
            return null
        }

        // ---- 3. 群聊判定 ----
        // MessagingStyle 的 conversationTitle 非空通常是群名；退而求其次看标题是否含「(N)」
        val conversationTitle = runCatching { style?.conversationTitle?.toString() }.getOrNull().orEmpty()
        val isGroup = conversationTitle.isNotEmpty() && conversationTitle != sender

        // ---- 4. 会话标识 ----
        // 用「包名/用户/标题」而不是 sbn.key：QQ 每次推送的 key 都不一样，
        // 拿 key 当会话会让冷却闸形同虚设。
        val session = "${sbn.packageName}/${userIdOf(sbn)}/" +
            (conversationTitle.ifEmpty { title }).ifEmpty { sbn.key }

        return QqMessage(
            pkg = sbn.packageName,
            userId = userIdOf(sbn),
            key = sbn.key,
            session = session,
            title = title,
            sender = sender,
            text = text,
            isGroup = isGroup,
            actions = actionsOf(n),
            fromMessagingStyle = style != null && styleText.isNotEmpty()
        )
    }

    private fun actionsOf(n: Notification): List<QqAction> {
        val list = n.actions ?: return emptyList()
        return list.map { a ->
            val keys = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
                a.remoteInputs?.mapNotNull { it?.resultKey }?.filter { it.isNotBlank() } ?: emptyList()
            } else {
                emptyList()
            }
            QqAction(title = a.title?.toString().orEmpty(), remoteInputs = keys, raw = a)
        }
    }

    /**
     * 从 `StatusBarNotification` 拿用户 id（应用分身靠它区分）。
     *
     * `getUser()` 返回的是 `UserHandle`，而 `UserHandle.getIdentifier()` 是隐藏 API —— 直接调
     * 编译不过。`toString()` 的格式是稳定的 `UserHandle{0}`，从中取数字最省事，
     * 也不依赖任何反射白名单。取不到就按主用户 0 处理（最保守）。
     */
    fun userIdOf(sbn: StatusBarNotification): Int = runCatching {
        Regex("\\d+").find(sbn.user?.toString().orEmpty())?.value?.toIntOrNull() ?: 0
    }.getOrDefault(0)

    /**
     * MessagingStyle 里这条消息是谁发的。
     *
     * `Message.getSenderPerson()` 在不同 androidx 版本上暴露的属性名不一致（senderPerson / person），
     * 直接调会在某些版本上编译不过。反射一次、拿不到就退回已废弃的 `getSender()` —— 后者从
     * MessagingStyle 第一天就在，永远有值。
     */
    private fun senderNameOf(m: NotificationCompat.MessagingStyle.Message?): String {
        if (m == null) return ""
        runCatching {
            val person = m.javaClass.getMethod("getSenderPerson").invoke(m) ?: return@runCatching ""
            val name = person.javaClass.getMethod("getName").invoke(person)?.toString().orEmpty()
            return name.trim()
        }
        return runCatching {
            @Suppress("DEPRECATION")
            m.sender?.toString()?.trim().orEmpty()
        }.getOrDefault("")
    }

    /**
     * 挑一个能静默回复的按钮。
     *
     * 优先标题里带「回复」的（QQ 的按钮文案通常是「回复」），
     * 没有再退回第一个带 RemoteInput 的 —— 有些版本标题是空的，但照样能发。
     */
    fun replyActionOf(message: QqMessage): QqAction? {
        val candidates = message.actions.filter { it.canReplySilently }
        if (candidates.isEmpty()) return null
        return candidates.firstOrNull { it.title.contains("回复") || it.title.contains("Reply", true) }
            ?: candidates.first()
    }
}
