package com.nexus.agent.bridge

import android.view.accessibility.AccessibilityNodeInfo

/**
 * 给 MPL 的「读屏」—— 只出文字，**永不出坐标**（协议 §5.1）。
 *
 * ## 为什么不复用 `UiTreeParser`
 *
 * `UiTreeParser` 的输出是给**模型看界面结构**用的：30 条可交互元素的 JSON，带 bounds。
 * 对 MPL 来说那份东西既太吵（它只要文字），又多一样不该给的东西（坐标）。
 * 坐标一旦出去，对面就能"绕过语义直接点像素"，而协议存在的意义正是禁止这件事。
 *
 * 所以这里是一套更窄的读法：**深搜全树 → 取 text/contentDescription → 去重 → 限长**。
 *
 * ## 密码框
 *
 * `isPassword=true` 的节点**连同子树整枝跳过**。不是脱敏，是不读——
 * 一个字段被标成密码，它和它里面的东西就都不该出现在任何 JSON 里。
 */
object ScreenTextReader {

    data class Result(
        val text: String,
        val chars: Int,
        val truncated: Boolean,
        val redacted: Boolean,
        val nodes: Int
    ) {
        companion object {
            val EMPTY = Result("", 0, false, false, 0)
            fun redacted() = Result("（敏感界面，不读取）", 0, false, true, 0)
        }
    }

    /** 默认上限（协议 §5.1 的 `maxChars` 默认值） */
    const val DEFAULT_MAX_CHARS = 2000

    /** 单个节点的文本上限：按钮文字一般不超过 20 字，超过的多半是正文 */
    private const val MAX_NODE_TEXT = 120

    /** 最多走多少个节点（防止超长列表把线程卡住） */
    private const val MAX_NODES = 600

    /** 最深走几层 */
    private const val MAX_DEPTH = 40

    fun read(root: AccessibilityNodeInfo?, maxChars: Int = DEFAULT_MAX_CHARS): Result {
        if (root == null) return Result.EMPTY
        val labels = ArrayList<String>(64)
        var visited = 0
        var depthCut = false

        // 显式栈深搜：递归在无障碍树上很容易爆栈（列表型界面嵌套很深）
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.addLast(root to 0)
        while (stack.isNotEmpty() && visited < MAX_NODES) {
            val (node, depth) = stack.removeLast()
            visited++
            if (depth > MAX_DEPTH) {
                depthCut = true
                continue
            }
            // 密码框整枝跳过：不读、不猜、不留
            if (node.isPassword) continue

            node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { labels.add(it) }
            node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { labels.add(it) }

            for (i in 0 until node.childCount) {
                val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                stack.addLast(child to depth + 1)
            }
        }

        val assembled = assemble(labels, maxChars)
        return if (depthCut || visited >= MAX_NODES) {
            assembled.copy(truncated = assembled.truncated || assembled.text.length >= maxChars)
        } else {
            assembled
        }
    }

    /**
     * 纯逻辑部分：去重 → 逐行拼 → 限长。抽出来是为了能在纯 JVM 单测里打靶
     * （[read] 依赖 AccessibilityNodeInfo，只能在真机验证）。
     */
    fun assemble(labels: List<String>, maxChars: Int = DEFAULT_MAX_CHARS): Result {
        if (labels.isEmpty()) return Result.EMPTY
        val limit = if (maxChars <= 0) DEFAULT_MAX_CHARS else maxChars
        val seen = LinkedHashSet<String>()
        for (raw in labels) {
            val t = raw.trim().replace('\n', ' ').take(MAX_NODE_TEXT)
            if (t.isNotEmpty()) seen.add(t)
        }
        if (seen.isEmpty()) return Result.EMPTY

        val sb = StringBuilder()
        var truncated = false
        for (t in seen) {
            // 第一条不需要分隔符 —— 少算这一个字符，第一行就会莫名其妙少一个字
            val need = sb.length + (if (sb.isEmpty()) 0 else 1) + t.length
            if (need > limit) {
                truncated = true
                break
            }
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(t)
        }
        val text = sb.toString()
        return Result(text, text.length, truncated, false, seen.size)
    }
}
