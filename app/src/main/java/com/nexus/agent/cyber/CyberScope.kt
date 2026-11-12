package com.nexus.agent.cyber

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 赛博网络的协程作用域（0.9.26）。
 *
 * 用应用级 scope 而不是 `viewModelScope` / `rememberCoroutineScope`：
 * 抓取是 3 分钟一次的低频任务，房间关掉之后**不该停** ——
 * 下次进来电视里应该已经有内容了，而不是先黑屏三分钟。
 *
 * `SupervisorJob`：一条抓取失败不该把整个轮询循环带走。微博接口挂掉很常见，
 * 挂掉之后 B 站还得继续抓。
 */
internal object CyberScope {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
