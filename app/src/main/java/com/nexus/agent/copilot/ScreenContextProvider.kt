package com.nexus.agent.copilot

import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.nexus.agent.accessibility.NexusAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「这一轮它到底怎么看见屏幕」—— 0.9.1 的感知层（无障碍文字优先）。
 *
 * ## 为什么要有这个类
 *
 * 0.9.0 之前，人格聊天**每一句**都会先截一张图，再把图丢给 1.4GB 的本地视觉模型：
 *
 * ```
 * 用户：「在吗」 → 截图 288×640 → :vision 子进程装载 Qwen2-VL → 看图推理 5~15 秒 → 才轮到聊天
 * ```
 *
 * 结果是「问一句话等十秒、手机发烫、掉电快」，而绝大多数场景（微信、淘宝、设置页）
 * 屏幕上**本来就有字**，读无障碍树几百毫秒就能拿到，还比 2B 模型的描述准得多。
 *
 * ## 判定规则
 *
 * | 抓到的有效文本节点 | 判定 | 后果 |
 * |---|---|---|
 * | > [MIN_TEXT_NODES]（3 个） | [ScreenContext.Text] | 直接把文字塞进 Prompt，**不截图、不碰视觉模型** |
 * | ≤ 3 个（游戏 / 视频 / Canvas 绘制界面） | [ScreenContext.NeedVision] | 才走截图 + 本地视觉 |
 * | 无障碍没连 / 拿不到窗口 / 前台是宏月自己 | [ScreenContext.Unavailable] | 两者都不做，明确告诉用户没看 |
 *
 * 「前台是宏月自己」单独拎出来：用户在人格页聊天时，读到的 UI 树是**我们自己的聊天界面**，
 * 拿它当屏幕上下文只会让模型对着自己的话接梗，既没用又要花 Token。
 *
 * 敏感应用（银行/支付/密码类）由调用方拦（[PersonaEngine.respond] 的 `allowScreenRead`），
 * 本类不做隐私判定 —— 一处判定，避免两套规则打架。
 */
sealed class ScreenContext {

    /** 前台应用名（拿不到为 null） */
    abstract val appName: String?

    /** 抓到的有效文本节点数（排障用） */
    abstract val nodeCount: Int

    /**
     * 界面文字够用 —— 常规应用走这条路。
     *
     * @param uiText 已去重、已截断的屏幕文字（`，` 分隔）
     */
    data class Text(
        override val appName: String?,
        val uiText: String,
        override val nodeCount: Int
    ) : ScreenContext() {

        /**
         * 拼成进 Prompt 的那一行：
         * `[当前应用：微信][屏幕文字：老张, 今晚吃饭]`
         */
        fun toPromptNote(): String = buildString {
            val app = appName?.trim().orEmpty()
            if (app.isNotEmpty()) append("[当前应用：").append(app).append(']')
            if (uiText.isNotBlank()) append("[屏幕文字：").append(uiText).append(']')
        }
    }

    /**
     * 屏幕上没字，得看图（游戏 / 视频 / 自绘界面）。
     *
     * @param reason 为什么要看图（写进日志，也进提示词的自证话术）
     */
    data class NeedVision(
        override val appName: String?,
        val reason: String,
        override val nodeCount: Int
    ) : ScreenContext()

    /**
     * 这轮压根没法感知（无障碍没连 / 拿不到窗口 / 前台是自家界面）。
     *
     * 和 [NeedVision] 的区别：这个**连截图都不值得做**。
     */
    data class Unavailable(
        override val appName: String?,
        val reason: String,
        override val nodeCount: Int = 0
    ) : ScreenContext()
}

/**
 * 屏幕上下文提供者（0.9.1）。
 *
 * 纯读操作，不截图、不起子进程、不发网络请求。
 * 唯一的外部依赖是 [NexusAccessibilityService]（未连接时退化成 [ScreenContext.Unavailable]）。
 */
object ScreenContextProvider {

    private const val TAG = "ScreenContext"

    /** 有效文本节点超过这个数，判定「界面有字，不用看图」 */
    const val MIN_TEXT_NODES = 3

    /** 进 Prompt 的屏幕文字总长上限（再多会把角色 LLM 的上下文挤走） */
    const val MAX_TEXT_CHARS = 320

    /** 单个节点的文字上限（列表项偶尔会把整段文章塞进一个节点） */
    private const val MAX_NODE_CHARS = 60

    /** 遍历节点上限（防止极端界面把主线程拖住；正常界面几十个就够） */
    private const val MAX_NODES = 600

    /** 遍历深度上限 */
    private const val MAX_DEPTH = 24

    /** 系统 UI 的包名 —— 状态栏的时间/电量/信号全是噪音 */
    private const val SYS_UI_PKG = "com.android.systemui"

    /** 明确的系统装饰节点（viewId 关键字） */
    private val NOISE_ID_HINTS = listOf(
        "status_bar", "navigation_bar", "nav_bar", "system_bar", "notch", "gesture_hint"
    )

    /**
     * 读一次当前屏幕。
     *
     * `suspend` 是因为要挪到 IO 线程：无障碍 IPC 读整棵树在卡顿的机器上可能几十毫秒，
     * 不应该压在协程调度线程上。
     */
    suspend fun getScreenContext(): ScreenContext = withContext(Dispatchers.IO) { readNow() }

    /**
     * 同步版本（调用方自己保证不在主线程）。
     *
     * 抽出来是为了单测能直接调纯逻辑，也方便别处（自检页）复用一个入口。
     */
    fun readNow(): ScreenContext {
        val service = NexusAccessibilityService.getInstance()
        if (service == null) {
            Log.d(TAG, "无障碍服务未连接，本轮无法读取屏幕文字")
            return ScreenContext.Unavailable(null, "无障碍服务未连接", 0)
        }
        val pkg = runCatching { service.getCurrentPackageName() }.getOrNull()
        val appName = labelOf(service, pkg)

        // 前台就是宏月自己：读到的是我们自己的聊天/设置界面，没有任何参考价值
        if (!pkg.isNullOrBlank() && pkg == service.packageName) {
            Log.d(TAG, "前台是宏月自己（$pkg），跳过屏幕感知")
            return ScreenContext.Unavailable(appName, "前台是宏月自己的界面", 0)
        }

        val root = runCatching { service.getRootNode() }.getOrNull()
        if (root == null) {
            Log.d(TAG, "拿不到当前窗口的界面树（可能是受保护窗口/锁屏）")
            return ScreenContext.Unavailable(appName, "拿不到当前窗口的界面树", 0)
        }

        val texts = ArrayList<String>(24)
        val seen = HashSet<String>(32)
        var visited = 0
        try {
            visited = collect(root, pkg, texts, seen, 0)
        } catch (e: Throwable) {
            Log.w(TAG, "读取界面树时出错（已拿到的 ${texts.size} 条照用）：${e.javaClass.simpleName}")
        } finally {
            // API 33+ 起 recycle() 是空实现，但老版本上不回收会漏 native 引用
            runCatching { root.recycle() }
        }

        val verdict = decide(appName, texts)
        Log.d(TAG, "模式: ${modeOf(verdict)}，文本长度: ${lengthOf(verdict)}，节点数: ${verdict.nodeCount}，" +
            "遍历: $visited，应用: ${appName ?: pkg ?: "未知"}")
        return verdict
    }

    /**
     * 判定逻辑（**纯函数**，单测直接打靶，不依赖无障碍环境）。
     *
     * 规则就是一行：**有 4 个以上不同的字，就当它看懂了。**
     * 少于等于 3 个说明这是个「没什么字」的界面（游戏血条、视频封面、画布），只能看图。
     */
    internal fun decide(appName: String?, texts: List<String>): ScreenContext {
        val body = joinWithinBudget(texts)
        return if (texts.size > MIN_TEXT_NODES) {
            ScreenContext.Text(appName, body, texts.size)
        } else {
            ScreenContext.NeedVision(
                appName = appName,
                reason = "界面只有 ${texts.size} 处文字（可能是游戏/视频/自绘界面）",
                nodeCount = texts.size
            )
        }
    }

    /**
     * 拼屏幕文字，总长不超 [MAX_TEXT_CHARS]。
     *
     * 超了就从尾巴丢 —— 列表类界面（微信会话列表）有价值的信息通常在上面几条，
     * 而越往后越是重复的「昨天」「星期二」。
     */
    internal fun joinWithinBudget(texts: List<String>): String {
        val sb = StringBuilder()
        for (t in texts) {
            val add = if (sb.isEmpty()) t else "，$t"
            if (sb.length + add.length > MAX_TEXT_CHARS) break
            sb.append(add)
        }
        return sb.toString()
    }

    /** 日志用的模式名（用户报障时会念这三个词） */
    internal fun modeOf(ctx: ScreenContext): String = when (ctx) {
        is ScreenContext.Text -> "Text"
        is ScreenContext.NeedVision -> "Vision"
        is ScreenContext.Unavailable -> "None"
    }

    private fun lengthOf(ctx: ScreenContext): Int = when (ctx) {
        is ScreenContext.Text -> ctx.uiText.length
        else -> 0
    }

    // ---------------------------------------------------------------- 遍历

    /**
     * 深度优先收集文字节点，返回访问过的节点数。
     *
     * 过滤三件事：
     *  ① 不可见 / 尺寸为 0（很多 App 会在树上挂着隐藏的骨架屏）；
     *  ② 系统 UI（状态栏、导航栏）；
     *  ③ 没信息的短串（单个标点、纯数字时间、纯 emoji）。
     */
    private fun collect(
        node: AccessibilityNodeInfo,
        selfPkg: String?,
        out: MutableList<String>,
        seen: MutableSet<String>,
        depth: Int
    ): Int {
        if (depth > MAX_DEPTH || out.size >= 200) return 0
        var visited = 1

        val nodePkg = runCatching { node.packageName?.toString() }.getOrNull()
        if (nodePkg == SYS_UI_PKG) return visited

        val vid = runCatching { node.viewIdResourceName }.getOrNull().orEmpty()
        if (NOISE_ID_HINTS.any { vid.contains(it, ignoreCase = true) }) return visited

        val visible = runCatching { node.isVisibleToUser }.getOrDefault(true)
        if (!visible) return visited

        val text = acceptLabel(runCatching { node.text }.getOrNull())
            ?: acceptLabel(runCatching { node.contentDescription }.getOrNull())
        if (text != null && seen.add(text)) out.add(text)

        val count = runCatching { node.childCount }.getOrDefault(0)
        for (i in 0 until count) {
            if (out.size >= 200 || visited >= MAX_NODES) break
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            try {
                visited += collect(child, selfPkg, out, seen, depth + 1)
            } finally {
                // 根节点由调用方回收；子节点在这里用完就还
                runCatching { child.recycle() }
            }
        }
        return visited
    }

    /**
     * 判断一段文字值不值得进 Prompt。
     *
     * @return 清洗后的文字；无意义返回 null
     */
    internal fun acceptLabel(raw: CharSequence?): String? {
        val s = raw?.toString()?.trim()?.replace(Regex("\\s+"), " ").orEmpty()
        if (s.length < 2) return null
        // 纯标点/符号（"|" "•••" "»"）
        if (s.none { it.isLetterOrDigit() || isCjk(it) }) return null
        // 纯数字与分隔符（"12:30" "86%" "1/8"）—— 时间、电量、进度，全是噪音
        if (s.all { it.isDigit() || it in ":%.／/-+~ " }) return null
        return if (s.length > MAX_NODE_CHARS) s.take(MAX_NODE_CHARS) + "…" else s
    }

    private fun isCjk(c: Char): Boolean = c.code in 0x4E00..0x9FFF

    /** 包名 → 应用显示名（拿不到就退回包名，再不行 null） */
    private fun labelOf(context: Context, pkg: String?): String? {
        if (pkg.isNullOrBlank()) return null
        return try {
            val info = context.packageManager.getApplicationInfo(pkg, 0)
            context.packageManager.getApplicationLabel(info).toString()
        } catch (e: Throwable) {
            pkg
        }
    }
}
