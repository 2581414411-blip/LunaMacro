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
 * {"text":"登录","type":"button","center":[540,1200],"editable":false,"id":"login","dep":4,"sib":2}
 *
 * 字段精简规则：
 * - text 为空时用 contentDescription 填充
 * - **editable 节点（输入框）的 text 一律掩码**，见 [MASKED_INPUT]
 * - className 只保留简短 type（去掉包名路径）
 * - bounds 四值 → 只留 center 两点
 * - 移除 isEnabled=true 等默认值
 * - id 非空才输出，且截取 "id/" 之后的部分
 * - 元素上限 30，按 Y 坐标从上到下排序取前 30
 *
 * ## 0.9.34：`dep` / `sib` 两个字段
 *
 * 界面指纹在 id 全空时要用结构 token `c:<短类名>#<dep>.<sib>` 兜底，
 * 这两个数必须**同一套算法**算出来，否则两边（指纹侧 / MPL 侧）永远对不上。
 * 约定写在 `docs/mpl-protocol-v1.md`：
 *
 * - `dep`：从根算，根节点 = 0（`DEP_ROOT = 0`）；
 * - `sib`：**同 dep、同短类名**的出现次序，从 0 起；
 * - 计数范围是**整棵可见树**，不是"可交互子集"——
 *   按钮一禁用就会掉出可交互子集，它后面所有同类节点的序号会整体前移；
 * - 不可见的整枝剪掉、**且不计数**（两边都拿不到那些节点，规则必须唯一）。
 *
 * 关键实现细节：sib 必须在**遍历时**计数。不能拿输出数组的下标去推——
 * 输出最后按 `center.y` 排序过，下标是 Y 序，不是树序。
 */
object UiTreeParser {

    private const val TAG = "UiTreeParser"

    /**
     * 根节点深度。写进协议（`docs/mpl-protocol-v1.md`），两边共用。
     *
     * 这个数不进协议的话，两边各写各的，差一位不会报错——
     * 只会表现为"指纹命中率莫名很低"，最难查的那类问题。
     */
    const val DEP_ROOT = 0

    /** 最大递归深度 */
    private const val MAX_DEPTH = 20

    /** 元素上限 */
    private const val MAX_ELEMENTS = 30

    /** 交互类控件类名（不含包名） */
    private val INTERACTIVE_CLASSES = setOf(
        "Button", "EditText", "ImageButton", "CheckBox", "Switch",
        "RadioButton", "SeekBar", "Spinner", "ToggleButton"
    )

    /** 输入框内容的替代符：有内容但不说是什么 */
    const val MASKED_INPUT = "***"

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
            // 屏幕范围取根的 bounds：分屏/弹窗时根本身就是本窗口的范围，
            // 拿它判"和屏幕有没有交集"比拿 DisplayMetrics 更准（多窗口下后者是整块屏）。
            val screen = Rect()
            root.getBoundsInScreen(screen)
            // 同 dep 同短类名的计数表，遍历时累加
            val sibCounters = HashMap<String, Int>(64)
            walk(root, elements, DEP_ROOT, screen, sibCounters)
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
     * 递归遍历**整棵可见树**，收集可交互元素并为每个节点算 dep/sib。
     *
     * 注意这里和 0.9.33 之前的 `collectInteractive` 有两处不同：
     * 1. 以前只往下走"可能是可交互节点"的分支，现在整棵树都走（否则 sib 数不准）；
     * 2. 以前 dep 只是参数没落盘，现在写进输出。
     *
     * 三处 return 的顺序很重要——**先判可见、再计数**。
     * 被剪掉的节点如果先计数，它后面的同类节点序号会整体 +1，
     * 而对方那边（拿不到这个节点）不会 +1，两边就永远错位。
     */
    private fun walk(
        node: AccessibilityNodeInfo,
        out: MutableList<JSONObject>,
        depth: Int,
        screen: Rect,
        sibCounters: MutableMap<String, Int>
    ) {
        if (depth >= MAX_DEPTH) return
        if (!node.isVisibleToUser) return

        // 与屏幕无交集 → 按不可见处理，且不计数。
        // isVisibleToUser 不够：RecyclerView 里已 inflate 但滚出屏幕的项它仍可能回 true，
        // 滚动一下这些项的 sib 全变，指纹跟着漂。
        if (!screen.isEmpty) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (!Rect.intersects(bounds, screen)) return
        }

        val cls = shortClassName(node.className?.toString().orEmpty())
        val key = "$depth|$cls"
        val sib = sibCounters[key] ?: 0
        sibCounters[key] = sib + 1

        if (isInteractive(node)) {
            val obj = toElementJson(node, depth, sib)
            if (obj != null) out.add(obj)
        }
        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            try {
                walk(child, out, depth + 1, screen, sibCounters)
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
            // 注意：不 recycle parent —— 它是 UI 树的一部分，recycle 可能破坏后续遍历
            if (parent != null && parent.isClickable) return true
        }
        return false
    }

    /**
     * 将可交互节点转为精简 JSON 元素；空内容返回 null。
     *
     * @param dep 从根算的深度（根 = [DEP_ROOT]）
     * @param sib 同 dep、同短类名的出现次序，从 0 起
     */
    private fun toElementJson(node: AccessibilityNodeInfo, dep: Int, sib: Int): JSONObject? {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() <= 0 || bounds.height() <= 0) return null

        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()

        val obj = JSONObject()

        if (node.isEditable) {
            // 0.9.34：输入框内容一律不外发。
            //
            // 以前这里无条件 `put("text", label)`，整份 uiJson 会被发给云端模型。
            // 密码框是硬红线，但更常见的是验证码框、手机号框、搜索框里用户刚打的字。
            // 打码方式：有内容 → "***"（模型知道"这里填了东西"，但不知道是什么），
            // 空 → 空串（保持"未填写"的语义，别让模型以为要清空重填）。
            // contentDescription 对输入框也不出——那里面常写着"请输入密码"。
            obj.put("text", if (text.isNotEmpty()) MASKED_INPUT else "")
            obj.put("editable", true)
        } else {
            // text 为空时用 contentDescription 填充
            obj.put("text", text.ifBlank { desc })
        }

        obj.put("type", shortClassName(node.className?.toString().orEmpty()))
        obj.put(
            "center",
            JSONArray().put(bounds.exactCenterX().toInt()).put(bounds.exactCenterY().toInt())
        )
        // 结构坐标：给指纹算 c:<类名>#dep.sib 用
        obj.put("dep", dep)
        obj.put("sib", sib)

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
