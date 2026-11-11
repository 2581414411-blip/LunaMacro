package com.nexus.agent.memory

import android.util.Log
import com.nexus.agent.accessibility.NexusAccessibilityService
import com.nexus.agent.accessibility.UiTreeParser

/**
 * 采集「当前是哪一屏」的快照（0.9.13 外挂记忆）。
 *
 * 只做一件事：把无障碍的 UI 树读出来，算出指纹和关键节点明文。
 * 所有判断都在 [ScreenFingerprint]（纯函数、可单测），这里只负责「读」。
 */
object MemoryScreen {

    private const val TAG = "MemDebug"

    /**
     * 一屏的快照。
     *
     * @param fingerprint 空串 = 这次读不到界面（没开无障碍 / 空界面），
     *                    拿到空串时调用方**必须放弃**记忆匹配，不能拿它去撞
     * @param keysCsv     关键节点明文（`ScreenFingerprint.keyString`）
     */
    data class Snapshot(
        val pkg: String,
        val uiJson: String?,
        val elementCount: Int,
        val fingerprint: String,
        val keysCsv: String
    ) {
        val usable: Boolean get() = fingerprint.isNotBlank()

        /** 给 Planner 用的那行摘要，和 0.9.12 之前的 `observeInitial()` 输出一致 */
        fun summary(): String = "界面=${pkg.ifBlank { "未知" }}, 元素=$elementCount"
    }

    /**
     * 读一次当前界面。
     *
     * 失败一律返回 [fingerprint] 为空的快照，不抛异常 ——
     * 记忆是加速层，读不到界面只该「这次不加速」，不该让任务起不来。
     */
    fun capture(): Snapshot {
        val pkg = try {
            NexusAccessibilityService.getInstance()?.getCurrentPackageName().orEmpty()
        } catch (e: Throwable) {
            ""
        }
        val uiJson = try {
            val service = NexusAccessibilityService.getInstance()
            val root = service?.getRootNode()
            if (root == null) null else UiTreeParser.parse(root)
        } catch (e: Throwable) {
            Log.w(TAG, "读 UI 树失败：${e.javaClass.simpleName} ${e.message}")
            null
        }
        val elementCount = if (uiJson == null) 0 else Regex("\"center\"").findAll(uiJson).count()
        val keys = ScreenFingerprint.keysOf(uiJson)
        val fp = ScreenFingerprint.of(pkg, uiJson)
        if (fp.isBlank()) {
            Log.i(TAG, "[指纹] 读不到有效界面（元素=$elementCount，关键节点=${keys.size}），本轮不参与记忆匹配")
        } else {
            Log.i(TAG, "[指纹] pkg=$pkg 元素=$elementCount 关键节点=${keys.size} → $fp")
        }
        return Snapshot(
            pkg = pkg,
            uiJson = uiJson,
            elementCount = elementCount,
            fingerprint = fp,
            keysCsv = ScreenFingerprint.keyString(keys)
        )
    }
}
