package com.nexus.agent.agent.tools

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 语义化工具注册表。
 *
 * 集中注册所有工具，并负责：
 * 1. [get] —— 按名字取工具
 * 2. [toJsonSchema] —— 生成供 LLM 理解的工具描述 JSON
 *
 * 新增工具时：在 [all] 列表里加一个实例即可，schema 自动并入。
 */
object ToolRegistry {

    private const val TAG = "ToolRegistry"

    /** 全部已注册工具 */
    private val all: List<Tool> = listOf(
        ClickTextTool,
        ClickIdTool,
        InputTextTool,
        OpenAppTool,
        PressBackTool,
        PressHomeTool,
        ScrollTool,
        WaitTool,
        LongPressTool,
        DragTool,
        DoubleTapTool,
        MultiTouchTool,
        PlayMacroTool,
        ListMacrosTool,
        StartGameAutoTool,
        StopGameAutoTool,
        WebSearchTool,
        ScheduleTool
    )

    /** 工具名 → 工具 索引 */
    private val byName: Map<String, Tool> = all.associateBy { it.name }

    /**
     * 按名字取工具。
     */
    fun get(name: String): Tool? = byName[name.lowercase().trim()]

    /** 全部已注册工具名（供副驾驶校验模型输出、做正则兜底） */
    fun names(): Set<String> = byName.keys

    /**
     * 工具「一行一条」精简清单（`名字：用途`）。
     *
     * 给本地小模型的提示词用：完整 JSON Schema 动辄几千字，2B 模型吃不消。
     */
    fun briefList(): String = all.joinToString("\n") { "${it.name}：${describe(it.name)}" }

    /**
     * 生成所有工具的 JSON Schema 描述。
     *
     * 返回一个 JSON 数组字符串，每个元素形如：
     * {"name":"click_text","description":"...","parameters":{...}}
     *
     * 供 PromptBuilder 注入 system prompt，让 LLM 知道有哪些工具可用。
     */
    fun toJsonSchema(): String {
        val arr = JSONArray()
        all.forEach { tool ->
            arr.put(
                JSONObject().apply {
                    put("name", tool.name)
                    put("description", describe(tool.name))
                    put("parameters", tool.schema)
                }
            )
        }
        return arr.toString(2)
    }

    /**
     * 工具描述文本（供 schema 与 prompt 复用）。
     */
    private fun describe(name: String): String = when (name) {
        "click_text" -> "按文本点击屏幕上包含指定文字的控件，坐标由 UI 树自动解析，无需手算坐标"
        "click_id" -> "按资源 ID 精确点击控件（如 com.android.settings:id/switch_widget）"
        "input_text" -> "往输入框写入文字（中文可用，不依赖输入法），可指定输入框提示文字定位；先点击再输入更稳"
        "open_app" -> "按应用名启动应用（如\"设置\"、\"微信\"），返回包名"
        "press_back" -> "按返回键"
        "press_home" -> "按 Home 键回到桌面"
        "scroll" -> "按方向滑动屏幕（up/down/left/right）"
        "wait" -> "等待指定毫秒数（用于界面加载）"
        "long_press" -> "长按控件或坐标（触发长按菜单）"
        "drag" -> "精确拖拽（拖动图标/滑块，起点终点精确）"
        "double_tap" -> "双击控件或坐标"
        "multi_touch" -> "多指手势（双指缩放等）"
        "play_macro" -> "回放一个已保存的宏（预设动作序列），适合重复性游戏操作"
        "list_macros" -> "列出所有已保存的宏（名称、描述、目标应用、步骤数）"
        "start_game_auto" -> "启动游戏自动化（按 GameProfile 名称加载规则，挂机/刷材料/日常任务）"
        "stop_game_auto" -> "停止游戏自动化"
        "web_search" -> "联网搜索实时信息（今天/最新/每日任务/活动攻略），返回标题+摘要+链接"
        "schedule_task" -> "建一个定时任务：到点自动执行指定任务（每天/每周几/只一次）"
        else -> name
    }
}
