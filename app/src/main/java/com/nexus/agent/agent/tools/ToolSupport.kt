package com.nexus.agent.agent.tools

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.nexus.agent.accessibility.NexusAccessibilityService
import com.nexus.agent.input.ShizukuManager

/**
 * 语义化工具的公共支撑逻辑。
 *
 * 封装「从 UI 树找节点 / 计算中心坐标 / 注入点击滑动 / 读屏幕尺寸」等
 * 重复代码，供各 Tool 复用。核心原则：坐标一律从 UI 树的 bounds 解析，
 * 不让 LLM 从图片推算。
 */
object ToolSupport {

    private const val TAG = "ToolSupport"

    /** 找到的节点（含中心坐标，便于日志与返回值） */
    data class FoundNode(
        val text: String,
        val centerX: Float,
        val centerY: Float,
        val bounds: Rect
    )

    /**
     * 遍历 UI 树，收集所有满足 [predicate] 的节点。
     *
     * @param predicate 判定函数，返回 true 表示命中
     * @return 命中的节点列表（按 Y 坐标从上到下排序）
     */
    fun findNodes(predicate: (AccessibilityNodeInfo) -> Boolean): List<FoundNode> {
        val service = NexusAccessibilityService.getInstance()
        val root = service?.getRootNode() ?: return emptyList()

        val result = mutableListOf<FoundNode>()
        try {
            collect(root, predicate, result)
        } finally {
            root.recycle()
        }
        return result.sortedBy { it.bounds.top }
    }

    /**
     * 递归遍历节点树，收集命中节点。
     */
    private fun collect(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
        out: MutableList<FoundNode>
    ) {
        try {
            if (predicate(node)) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (bounds.width() > 0 && bounds.height() > 0) {
                    out.add(
                        FoundNode(
                            text = node.text?.toString()
                                ?: node.contentDescription?.toString()
                                ?: "",
                            centerX = bounds.exactCenterX(),
                            centerY = bounds.exactCenterY(),
                            bounds = Rect(bounds)
                        )
                    )
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try {
                    collect(child, predicate, out)
                } finally {
                    child.recycle()
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "collect failed", e)
        }
    }

    /**
     * 通过 Shizuku 注入一次点击。
     *
     * @return 是否成功
     */
    fun injectTap(x: Float, y: Float): Boolean {
        val service = ShizukuManager.getService() ?: return false
        return try {
            service.injectTap(x, y)
            true
        } catch (e: Throwable) {
            Log.e(TAG, "injectTap($x,$y) failed", e)
            false
        }
    }

    /**
     * 通过 Shizuku 注入一次滑动。
     */
    fun injectSwipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Int): Boolean {
        val service = ShizukuManager.getService() ?: return false
        return try {
            service.injectSwipe(x1, y1, x2, y2, duration)
            true
        } catch (e: Throwable) {
            Log.e(TAG, "injectSwipe failed", e)
            false
        }
    }

    /**
     * 执行 shell 命令。
     */
    fun execCommand(cmd: String): String? {
        val service = ShizukuManager.getService() ?: return null
        return try {
            service.execCommand(cmd)
        } catch (e: Throwable) {
            Log.e(TAG, "execCommand($cmd) failed", e)
            null
        }
    }

    /**
     * 获取屏幕尺寸（像素）。
     */
    data class ScreenSize(val width: Int, val height: Int)

    fun screenSize(): ScreenSize {
        val ctx = ShizukuManager.appContext()
        val dm = ctx?.resources?.displayMetrics
        return if (dm != null) ScreenSize(dm.widthPixels, dm.heightPixels)
        else ScreenSize(1080, 2400)
    }
}
