package com.nexus.agent.accessibility

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * UI 元素树解析器（可交互元素精简模式）。
 *
 * 目标：把 Observe 输出从 10KB+ 压缩到 1~2KB，只保留 LLM 决策真正需要的
 * 可交互元素，去掉嵌套结构和冗余字段。
 *
 * 只保留满足以下任一条件的节点：
 * a) clickable = true
 * b) editable = true
 * c) className 属于 [Button, EditText, ImageButton, CheckBox, Switch]
 * d) 有 text 且父节点 clickable = true
 *
 * 每个元素输出精简格式（JSON 数组，不嵌套）：
 * {"text":"登录","type":"button","center":[540,1200],"editable":false,"id":"xxx:id/login"}
 *
 * 字段精简规则：
 * - text 为空时用 contentDescription 填充
 * - className 只保留简短 type（去掉包名路径）
 * - bounds 四值 → 只留 center 两点
 * - 移除 isEnabled=true 等默认值
 * - id 非空才输出，且截取 "id/" 之后的部分
 * - 元素上限 30，按 Y 坐标从上到下排序取前 30
 */
object UiTreeParser {

    private const val TAG = "UiTreeParser"

    /** 最大递归深度 */
    private const val MAX_DEPTH = 20

    /** 元素上限 */
    private const val MAX_ELEMENTS = 30

    /** 交互类控件类名（不含包名） */
    private val INTERACTIVE_CLASSES = setOf(
        "Button", "EditText", "ImageButton", "CheckBox", "Switch",
        "RadioButton", "SeekBar", "Spinner", "ToggleButton"
    )

    /**
     * 解析整棵 UI 树，返回精简的可交互元素 JSON 数组字符串。
     *
     * @param root 无障碍根节点（可为 null）
     * @return JSON 数组字符串；根节点为 null 时返回 "[]"
     */
    fun parse(root: AccessibilityNodeInfo?): String {
        if (root == null) return "[]"

        val elements = mutableListOf<JSONObject>()
        try {
            collectInteractive(root, elements, 0)
        } catch (e: Throwable) {
            Log.e(TAG, "parse failed", e)
        } finally {
            root.recycle()
        }

        // 按 Y 坐标（center 第二项）从上到下排序
        val sorted = elements.sortedBy { it.optJSONArray("center")?.optInt(1) ?: 0 }
        val limited = sorted.take(MAX_ELEMENTS)

        val arr = JSONArray()
        limited.forEach { arr.put(it) }
        return arr.toString()
    }

    /**
     * 递归遍历，收集可交互元素。
     */
    private fun collectInteractive(
        node: AccessibilityNodeInfo,
        out: MutableList<JSONObject>,
        depth: Int
    ) {
        if (depth >= MAX_DEPTH) return
        if (!node.isVisibleToUser) return

        if (isInteractive(node)) {
            val obj = toElementJson(node)
            if (obj != null) out.add(obj)
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collectInteractive(child, out, depth + 1)
            } finally {
                child.recycle()
            }
        }
    }

    /**
     * 判定节点是否为可交互元素。
     */
    private fun isInteractive(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable || node.isEditable) return true

        val cls = shortClassName(node.className?.toString().orEmpty())
        if (cls in INTERACTIVE_CLASSES) return true

        // 有 text 且父节点 clickable
        val hasText = !node.text.isNullOrEmpty() || !node.contentDescription.isNullOrEmpty()
        if (hasText) {
            val parent = node.parent
            if (parent != null) {
                try {
                    if (parent.isClickable) return true
                } finally {
                    parent.recycle()
                }
            }
        }
        return false
    }

    /**
     * 将可交互节点转为精简 JSON 元素；空内容返回 null。
     */
    private fun toElementJson(node: AccessibilityNodeInfo): JSONObject? {
        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        // text 为空时用 contentDescription 填充
        val label = text.ifBlank { desc }

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() <= 0 || bounds.height() <= 0) return null

        val obj = JSONObject()
        obj.put("text", label)
        obj.put("type", shortClassName(node.className?.toString().orEmpty()))
        obj.put(
            "center",
            JSONArray().put(bounds.exactCenterX().toInt()).put(bounds.exactCenterY().toInt())
        )
        // editable 仅在 true 时输出
        if (node.isEditable) obj.put("editable", true)

        // id 非空才输出，截取 "id/" 之后
        val vid = node.viewIdResourceName?.toString()
        if (!vid.isNullOrEmpty()) {
            val short = vid.substringAfterLast("/", vid)
            if (short.isNotEmpty()) obj.put("id", short)
        }

        return obj
    }

    /**
     * 截取类名的简短部分（去包名路径）。
     */
    private fun shortClassName(full: String): String {
        return full.substringAfterLast('.').ifBlank { full.substringAfterLast('/') }.ifBlank { "view" }
    }
}
