package com.nexus.agent.qq

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 抓取测试探针（0.9.25 批次 E）。
 *
 * ## 为什么需要它
 *
 * 「通知里到底有什么」是这套功能唯一的未知数：不同 QQ 版本、主号 / 分身、
 * 群聊 / 单聊，`EXTRA_TEXT` 的含义、有没有 MessagingStyle、按钮上到底挂没挂
 * RemoteInput —— 全都可能不一样。而这些东西**只能真机上看**。
 *
 * 于是做一个最小的探针：用户在设置页点「抓取测试」，服务捕获下一条 QQ 通知，
 * 把原始字段丢回来弹窗显示。它不改任何配置、不触发模型、不发消息。
 *
 * ## 为什么用 StateFlow 而不是回调
 *
 * 服务可能在任意时刻被系统拉起/回收，持有 UI 回调会泄漏。StateFlow 只存一个值，
 * 谁在看谁拿，页面走了也没人惦记。
 */
object QqTestProbe {

    /**
     * 抓到的原始字段快照。
     *
     * @property actions 每个按钮一行：`标题 | RemoteInput 数 | 结果键`
     */
    data class Snapshot(
        val pkg: String,
        val userId: Int,
        val key: String,
        val session: String,
        val title: String,
        val sender: String,
        val text: String,
        val isGroup: Boolean,
        val fromMessagingStyle: Boolean,
        val actions: List<String>,
        val at: Long
    ) {
        fun toText(): String = buildString {
            appendLine("包名　　：$pkg")
            appendLine("用户 id ：$userId（0 = 主号，分身一般不是 0）")
            appendLine("通知 key：$key")
            appendLine("会话　　 ：$session")
            appendLine("标题　　：$title")
            appendLine("发送者　：$sender")
            appendLine("是否群聊：${if (isGroup) "是" else "否 / 判断不出"}")
            appendLine("来自 MessagingStyle：${if (fromMessagingStyle) "是（内容更准）" else "否（读的是 EXTRA_TEXT）"}")
            appendLine("正文　　：$text")
            appendLine()
            if (actions.isEmpty()) {
                append("按钮　　：（没有按钮，这条通知不能静默回复）")
            } else {
                appendLine("按钮　　：")
                actions.forEach { appendLine("　· $it") }
            }
        }
    }

    private val _result = MutableStateFlow<Snapshot?>(null)
    val result: StateFlow<Snapshot?> = _result.asStateFlow()

    /** 是否已经「上膛」——只有上膛期间才抓，平时一律不打扰 */
    @Volatile
    private var armed = false

    val isArmed: Boolean get() = armed

    /** 开始抓：清掉上次的结果，等下一张 QQ 通知 */
    fun arm() {
        _result.value = null
        armed = true
    }

    fun disarm() {
        armed = false
    }

    /**
     * 服务侧调用：如果上膛了就收下这一条。
     *
     * 收下之后**自动下膛** —— 抓一次就够了，连抓会让用户以为按了没反应
     * （弹窗还没关上，值又被下一条覆盖）。
     */
    fun offer(message: QqMessage, actionLines: List<String>) {
        if (!armed) return
        armed = false
        _result.value = Snapshot(
            pkg = message.pkg,
            userId = message.userId,
            key = message.key,
            session = message.session,
            title = message.title,
            sender = message.sender,
            text = message.text,
            isGroup = message.isGroup,
            fromMessagingStyle = message.fromMessagingStyle,
            actions = actionLines,
            at = System.currentTimeMillis()
        )
    }
}
