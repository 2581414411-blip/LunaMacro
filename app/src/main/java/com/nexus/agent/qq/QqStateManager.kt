package com.nexus.agent.qq

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * QQ 自动回复的运行时状态（0.9.25 批次 E）。
 *
 * ## 为什么是单例 + StateFlow
 *
 * 产生状态的是 [QqNotificationService]（系统绑定的服务，可能在任意进程时机被拉起），
 * 消费状态的是 [com.nexus.agent.ui.room.RoomScreen]（Compose 界面）。
 * 两者之间没有任何直接引用关系 —— 一个全局的 StateFlow 是唯一不需要互相持有的通道。
 *
 * ## 为什么只暴露「在不在回复」而不是整条消息
 *
 * 房间里要的只是「她现在在跟人说话」这个事实。把对方的名字、内容也带进房间，
 * 就等于在界面上泄露聊天内容 —— 而房间可能正被别人看着。
 */
object QqStateManager {

    private val _isReplying = MutableStateFlow(false)

    /** 是否正在替主人回 QQ：true 时房间切 CHATTING，头顶显示「正在和群友对线…」 */
    val isReplying: StateFlow<Boolean> = _isReplying.asStateFlow()

    private val _lastSummary = MutableStateFlow<String?>(null)

    /**
     * 最近一次动作的一句话摘要（给设置页显示「刚才发生了什么」）。
     *
     * 只放「回了 X」「跳过了（原因）」，不放对方原话 —— 同上，少一处泄露面。
     */
    val lastSummary: StateFlow<String?> = _lastSummary.asStateFlow()

    private var depth = 0

    /** 开始思考/发送。嵌套调用安全（虽然实际不会嵌套） */
    @Synchronized
    fun begin() {
        depth += 1
        _isReplying.value = true
    }

    /** 结束。只有最外层结束时才真正复位，避免并发两条消息时提前关灯 */
    @Synchronized
    fun finish() {
        depth = (depth - 1).coerceAtLeast(0)
        if (depth == 0) _isReplying.value = false
    }

    fun note(summary: String) {
        _lastSummary.value = summary
    }
}
