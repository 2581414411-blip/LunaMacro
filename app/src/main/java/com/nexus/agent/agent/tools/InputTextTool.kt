package com.nexus.agent.agent.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.nexus.agent.accessibility.NexusAccessibilityService
import com.nexus.agent.input.ShizukuManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * input_text —— 往目标输入框写入文字。
 *
 * 背景：早期工具体系只有点击/滑动/长按等「触摸类」动作，缺了「打字」这一环，
 * 导致任何需要输入框的任务（搜索、聊天、账号、游戏内发言）都做不下去。
 *
 * 实现参考了同类产品的做法（直接对输入框节点塞文本，不经过输入法），
 * 因此**不依赖用户装的是哪种输入法**（九宫格/手写/第三方），中文也能写。
 *
 * 三级兜底，按可靠性从高到低：
 *  1. **无障碍直写**：定位可编辑节点 → ACTION_FOCUS → ACTION_SET_TEXT。
 *     这是首选，等价于「粘贴进输入框」，中文/emoji 都行。
 *  2. **剪贴板 + ACTION_PASTE**：部分 App（Compose / WebView 混合）忽略 SET_TEXT，
 *     但认粘贴，此时写剪贴板再触发节点原生的 ACTION_PASTE。
 *  3. **Shizuku shell `input text`**：无障碍完全拿不到节点时（游戏、自绘界面）的最后手段，
 *     **只支持 ASCII**（Android 的 input text 会吞掉中文），含中文直接跳过。
 *
 * 语义说明：[text] 是**替换**输入框内容，不是追加（与 ACTION_SET_TEXT 行为一致）。
 */
object InputTextTool : Tool {

    private const val TAG = "NexusTool"

    override val name = "input_text"

    override val schema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("text", JSONObject().apply {
                put("type", "string")
                put("description", "要输入的文字，中文可用；会替换输入框原有内容")
            })
            put("target", JSONObject().apply {
                put("type", "string")
                put("description", "输入框的提示文字/描述/资源 ID，用于定位（如\"搜索\"、\"说点什么\"）；省略则用当前焦点框或页面上唯一的输入框")
            })
            put("pressEnter", JSONObject().apply {
                put("type", "boolean")
                put("description", "输入后是否按回车提交，默认 false")
            })
        })
        put("required", JSONArray().put("text"))
    }

    override fun execute(args: Map<String, Any?>): String {
        val text = args["text"]?.toString() ?: return "错误：缺少 text 参数"
        if (text.isEmpty()) return "错误：text 为空"
        val target = args["target"]?.toString()?.trim().orEmpty()
        val pressEnter = when (val v = args["pressEnter"]) {
            is Boolean -> v
            null -> false
            else -> v.toString().equals("true", ignoreCase = true)
        }

        val ctx = ShizukuManager.appContext()

        // ① 无障碍直写（最优）
        var result = tryAccessibility(text, target)

        // 节点没找到但给了 target：先点一下目标，让它抢到焦点，再试一次
        if (result == null && target.isNotEmpty()) {
            val activated = tapTarget(target)
            if (activated) {
                Thread.sleep(350)
                result = tryAccessibility(text, target)
            }
        }

        // ② 剪贴板 + 粘贴已在 tryAccessibility 内部完成；这里处理它返回失败文案的情况
        if (result != null) {
            if (pressEnter) sendEnterKey()
            return result
        }

        // ③ Shizuku shell 注入（仅 ASCII）
        val shell = tryShell(text)
        if (shell != null) {
            if (pressEnter) sendEnterKey()
            return shell
        }

        val reason = buildString {
            append("输入失败：")
            if (!NexusAccessibilityService.isBound()) {
                append("无障碍服务未连接；")
            } else {
                append("当前界面没有可写入的输入框（可能是游戏或自绘界面）；")
            }
            if (ctx != null && text.any { it.code > 0x7e }) {
                append("内容含中文，Shizuku 的 input text 不支持；")
            } else if (ShizukuManager.getService() == null) {
                append("Shizuku 未连接；")
            }
            append("建议先 click_text 点一下输入框再重试")
        }
        Log.w(TAG, "input_text 全部通道失败：target=$target")
        return reason
    }

    /**
     * 无障碍通道。
     *
     * @return 成功/失败的可读结果；返回 null 表示「拿不到节点，可以走下一级兜底」
     */
    private fun tryAccessibility(text: String, target: String): String? {
        if (!NexusAccessibilityService.isBound()) return null
        val service = NexusAccessibilityService.getInstance() ?: return null
        val root = try {
            service.getRootNode()
        } catch (e: Throwable) {
            Log.w(TAG, "getRootNode 失败：${e.message}")
            null
        } ?: return null

        var node: AccessibilityNodeInfo? = null
        try {
            node = pickEditableNode(root, target) ?: return null

            // 聚焦：部分 App 不聚焦会静默忽略 SET_TEXT
            try {
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            } catch (_: Throwable) {
            }

            // ① ACTION_SET_TEXT —— 直接写文本，不经过输入法
            val bundle = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
            }
            val setOk = try {
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
            } catch (e: Throwable) {
                Log.w(TAG, "ACTION_SET_TEXT 异常：${e.message}")
                false
            }
            if (setOk && verifyWritten(node, text)) {
                Log.i(TAG, "input_text 成功（无障碍直写）len=${text.length}")
                return "已输入\"$text\"（无障碍直写）"
            }

            // ② 剪贴板 + 节点原生粘贴（对付忽略 SET_TEXT 的 App）
            if (writeClipboard(text)) {
                val pasteOk = try {
                    node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                } catch (e: Throwable) {
                    Log.w(TAG, "ACTION_PASTE 异常：${e.message}")
                    false
                }
                if (pasteOk && verifyWritten(node, text)) {
                    Log.i(TAG, "input_text 成功（剪贴板粘贴）len=${text.length}")
                    return "已输入\"$text\"（剪贴板粘贴）"
                }
            }

            // 节点在但两种方式都没生效：把原因带出去，但允许外层继续试 shell
            Log.w(TAG, "无障碍通道未生效（节点存在但写入被忽略）")
            return null
        } finally {
            safeRecycle(node)
            safeRecycle(root)
        }
    }

    /**
     * 校验是否真的写进去了。
     *
     * 密码框读出来是空的/掩码，无法比对，直接信任 performAction 的返回值。
     */
    private fun verifyWritten(node: AccessibilityNodeInfo, text: String): Boolean {
        return try {
            if (node.isPassword) return true
            node.refresh()
            val now = node.text?.toString().orEmpty()
            // 输入框可能做了格式过滤（如手机号框只留数字），用「首字符命中」放宽判定
            now.contains(text) || (now.isNotEmpty() && text.isNotEmpty() && now.contains(text.first()))
        } catch (e: Throwable) {
            // refresh 失败不判死，交给上层决定
            true
        }
    }

    /**
     * 在 UI 树里挑一个最可能的输入框。
     *
     * 优先级：target 命中 > 当前焦点 > 页面上唯一输入框 > 面积最大的那个。
     */
    private fun pickEditableNode(
        root: AccessibilityNodeInfo,
        target: String
    ): AccessibilityNodeInfo? {
        val editables = mutableListOf<AccessibilityNodeInfo>()
        collectEditable(root, editables)

        if (target.isNotEmpty()) {
            val hit = editables.firstOrNull { matches(it, target) }
            if (hit != null) {
                editables.forEach { if (it !== hit) safeRecycle(it) }
                return hit
            }
        }

        val focused = try {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } catch (e: Throwable) {
            null
        }
        if (focused != null && focused.isEditable) {
            editables.forEach { if (it !== focused) safeRecycle(it) }
            return focused
        }
        safeRecycle(focused)

        if (editables.size == 1) return editables[0]
        if (editables.isEmpty()) return null

        // 多个输入框：取面积最大的，其余回收
        var best = editables[0]
        var bestArea = areaOf(best)
        for (i in 1 until editables.size) {
            val a = areaOf(editables[i])
            if (a > bestArea) {
                safeRecycle(best)
                best = editables[i]
                bestArea = a
            } else {
                safeRecycle(editables[i])
            }
        }
        return best
    }

    /** 递归收集可编辑节点（复制引用，避免父节点回收后失效） */
    private fun collectEditable(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        try {
            if (node.isEditable) {
                out.add(AccessibilityNodeInfo.obtain(node))
                return
            }
            for (i in 0 until node.childCount) {
                val child = try {
                    node.getChild(i)
                } catch (e: Throwable) {
                    null
                } ?: continue
                try {
                    collectEditable(child, out)
                } finally {
                    safeRecycle(child)
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "遍历 UI 树异常：${e.message}")
        }
    }

    /** 节点是否对应 target（提示文字 / 文本 / 描述 / 资源 ID） */
    private fun matches(node: AccessibilityNodeInfo, target: String): Boolean {
        val hint = node.hintText?.toString().orEmpty()
        val t = node.text?.toString().orEmpty()
        val d = node.contentDescription?.toString().orEmpty()
        val id = node.viewIdResourceName.orEmpty()
        return hint.contains(target, ignoreCase = true) ||
            t.contains(target, ignoreCase = true) ||
            d.contains(target, ignoreCase = true) ||
            id.contains(target, ignoreCase = true)
    }

    /** 点一下 target（激活输入框用） */
    private fun tapTarget(target: String): Boolean {
        val hits = ToolSupport.findNodes { node ->
            val t = node.text?.toString().orEmpty()
            val d = node.contentDescription?.toString().orEmpty()
            val h = node.hintText?.toString().orEmpty()
            t.contains(target, ignoreCase = true) ||
                d.contains(target, ignoreCase = true) ||
                h.contains(target, ignoreCase = true)
        }
        val first = hits.firstOrNull() ?: return false
        val ok = ToolSupport.injectTap(first.centerX, first.centerY)
        if (ok) Log.i(TAG, "input_text 先点击 \"$target\" 激活输入框")
        return ok
    }

    private fun writeClipboard(text: String): Boolean {
        val ctx: Context = ShizukuManager.appContext() ?: return false
        return try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return false
            cm.setPrimaryClip(ClipData.newPlainText("luna_input", text))
            true
        } catch (e: Throwable) {
            Log.w(TAG, "写剪贴板失败：${e.message}")
            false
        }
    }

    /**
     * Shizuku shell 通道：`input text`。
     *
     * Android 的 input text 只接受 ASCII 可见字符，中文会被静默吞掉，
     * 因此含非 ASCII 直接返回 null（交给调用方报错，别假装成功）。
     */
    private fun tryShell(text: String): String? {
        if (text.any { it.code < 0x20 || it.code > 0x7e }) return null
        val service = ShizukuManager.getService() ?: return null
        // input 命令用 %s 表示空格；单引号需转义，其余特殊字符在单引号内无害
        val escaped = text.replace("'", "'\\''").replace(" ", "%s")
        return try {
            val out = service.execCommand("input text '$escaped'")
            val failed = out.contains("ERROR", ignoreCase = true) ||
                out.contains("Exception", ignoreCase = true) ||
                out.contains("Usage", ignoreCase = true)
            if (failed) {
                Log.w(TAG, "shell input text 失败：$out")
                null
            } else {
                Log.i(TAG, "input_text 成功（shell）len=${text.length}")
                "已输入\"$text\"（Shizuku 键注入）"
            }
        } catch (e: Throwable) {
            Log.w(TAG, "shell 输入异常：${e.message}")
            null
        }
    }

    private fun sendEnterKey() {
        val service = ShizukuManager.getService() ?: return
        try {
            service.execCommand("input keyevent 66")
        } catch (e: Throwable) {
            Log.w(TAG, "发送回车失败：${e.message}")
        }
    }

    private fun areaOf(node: AccessibilityNodeInfo): Int {
        val r = android.graphics.Rect()
        return try {
            node.getBoundsInScreen(r)
            r.width() * r.height()
        } catch (e: Throwable) {
            0
        }
    }

    private fun safeRecycle(node: AccessibilityNodeInfo?) {
        if (node == null) return
        try {
            node.recycle()
        } catch (_: Throwable) {
        }
    }
}
