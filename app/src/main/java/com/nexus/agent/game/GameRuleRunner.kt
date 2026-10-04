package com.nexus.agent.game

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 游戏规则运行器。
 *
 * 主循环：取帧 → 识别状态 → 评估规则 → 执行命中的规则 → 等待 200ms。
 * 单条规则执行失败不中断整体循环。
 */
object GameRuleRunner {

    private const val TAG = "GameRuleRunner"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var running = false

    private var job: Job? = null

    private var rules: List<GameRule> = emptyList()

    @Volatile
    private var perceptionFps = 5

    val isRunning: Boolean get() = running

    /** 设置规则列表 */
    fun setRules(list: List<GameRule>) {
        rules = list
    }

    /** 设置感知帧率 */
    fun setPerceptionFps(fps: Int) {
        perceptionFps = fps.coerceIn(1, 10)
    }

    /**
     * 启动规则循环（同时启动感知截图循环）。
     */
    fun start() {
        if (running) return
        running = true
        GamePerceptionLoop.start(perceptionFps)
        job = scope.launch {
            while (running) {
                try {
                    val frame = GamePerceptionLoop.getLatestFrame()
                    if (frame != null) {
                        val state = GameStateRecognizer.recognize(frame)
                        val matched = RuleEngine.evaluate(state, rules)
                        for (rule in matched) {
                            RuleEngine.execute(rule)
                        }
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "规则循环异常", e)
                }
                delay(200)
            }
        }
        Log.i(TAG, "规则循环启动，规则数=${rules.size}")
    }

    /**
     * 停止规则循环（同时停止感知截图循环）。
     */
    fun stop() {
        running = false
        job?.cancel()
        job = null
        GamePerceptionLoop.stop()
        Log.i(TAG, "规则循环已停止")
    }
}
