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
     * 通过 Shizuku 注入一次点击；失败时降级为无障碍 performClick。
     *
     * @return 是否成功
     */
    fun injectTap(x: Float, y: Float): Boolean {
        val service = ShizukuManager.getService()
        if (service != null) {
            val ok = try {
                service.injectTap(x, y)
            } catch (e: Throwable) {
                Log.e(TAG, "injectTap($x,$y) failed", e)
                false
            }
            if (ok) return true
        }
        // 降级：Shizuku 不可用/反射失败时，用无障碍 dispatchGesture 点击
        val acc = NexusAccessibilityService.getInstance()
        if (acc != null) {
            return try {
                val r = acc.performClick(x, y)
                if (r) Log.w(TAG, "Shizuku 注入失败，降级为无障碍 performClick ($x,$y)")
                r
            } catch (e: Throwable) {
                Log.e(TAG, "performClick fallback failed", e)
                false
            }
        }
        return false
    }

    /**
     * 通过 Shizuku 注入一次滑动。
     */
    fun injectSwipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Int): Boolean {
        val service = ShizukuManager.getService() ?: return false
        return try {
            service.injectSwipe(x1, y1, x2, y2, duration)
        } catch (e: Throwable) {
            Log.e(TAG, "injectSwipe failed", e)
            false
        }
    }

    /**
     * 通过 Shizuku 注入一次长按。
     */
    fun injectLongPress(x: Float, y: Float, duration: Int): Boolean {
        val service = ShizukuManager.getService() ?: return false
        return try {
            service.injectLongPress(x, y, duration)
        } catch (e: Throwable) {
            Log.e(TAG, "injectLongPress($x,$y) failed", e)
            false
        }
    }

    /**
     * 通过 Shizuku 注入一次精确拖拽。
     */
    fun injectDrag(fromX: Float, fromY: Float, toX: Float, toY: Float, duration: Int): Boolean {
        val service = ShizukuManager.getService() ?: return false
        return try {
            service.injectDrag(fromX, fromY, toX, toY, duration)
        } catch (e: Throwable) {
            Log.e(TAG, "injectDrag failed", e)
            false
        }
    }

    /**
     * 通过 Shizuku 注入多指手势。
     *
     * @param points 每个点的 JSON 字符串列表（{"x":..,"y":..,"action":"down/up/move"}）
     */
    fun injectMultiTouch(points: List<String>): Boolean {
        val service = ShizukuManager.getService() ?: return false
        return try {
            service.injectMultiTouch(points.toMutableList())
        } catch (e: Throwable) {
            Log.e(TAG, "injectMultiTouch failed", e)
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
        // 优先通过 shell `wm size` 拿真实分辨率（含刘海/状态栏等场景更准）
        val out = execCommand("wm size")
        if (out != null) {
            val match = Regex("""(\d+)\s*[xX]\s*(\d+)""").find(out)
            if (match != null) {
                val w = match.groupValues[1].toIntOrNull()
                val h = match.groupValues[2].toIntOrNull()
                if (w != null && h != null && w > 0 && h > 0) {
                    return ScreenSize(w, h)
                }
            }
        }
        // 兜底：displayMetrics
        val ctx = ShizukuManager.appContext()
        val dm = ctx?.resources?.displayMetrics
        if (dm != null && dm.widthPixels > 0 && dm.heightPixels > 0) {
            return ScreenSize(dm.widthPixels, dm.heightPixels)
        }
        // 最终兜底
        return ScreenSize(1080, 2400)
    }
}
