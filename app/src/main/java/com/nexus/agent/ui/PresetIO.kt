package com.nexus.agent.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.nexus.agent.copilot.CopilotConfig
import com.nexus.agent.util.MiniJson
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 设置的导出 / 导入（0.9.24）。
 *
 * ## 为什么只导「设置」不导「数据」
 *
 * 这个功能是为了解决一类很实在的问题：换手机、重装、或者某台机器上键盘高度怎么都调不对 ——
 * 用户希望把自己调好的一套东西带走。所以导的是**行为偏好**：名字、几个开关、键盘校准值。
 *
 * 刻意**不导出**：
 * - 任何 API Key（导出物会在剪贴板里流转，可能被贴到聊天框、备忘录、网盘）；
 * - 聊天记录 / 好感度 / 记忆（那是她的私事，不该变成一份可转发的文件）；
 * - 已学会的动作与轨迹池（同上，里面有用户的操作习惯）。
 *
 * ## 为什么用剪贴板而不是文件
 *
 * 文件要处理存储权限、路径、分享 Intent，每一步都能失败；剪贴板一次操作、
 * 跨 App 通用（贴到微信发给自己、贴到别的设备），失败的路径最短。
 *
 * ## 格式
 *
 * ```json
 * {
 *   "schema": "luna-preset",
 *   "version": 1,
 *   "exportedAt": "2026-11-12 12:30",
 *   "keyboardCalibrationPx": 980,
 *   "assistantName": "宏月",
 *   "island": false,
 *   "companion": true,
 *   ...
 * }
 * ```
 *
 * 导入时**逐项宽容**：认不出的键忽略、类型不对退回默认、`schema` 不符直接拒绝 ——
 * 这样旧版本导出的预设在新版本里仍然能导入大部分字段。
 */
object PresetIO {

    private const val TAG = "PresetIO"
    private const val SCHEMA = "luna-preset"
    private const val VERSION = 1

    /** 导出：返回要放进剪贴板的 JSON 文本 */
    fun export(context: Context): String {
        val name = CopilotConfig.assistantName(context)
        val calib = CopilotConfig.keyboardCalibrationPx(context)
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        return buildString {
            append("{\n")
            append("  \"schema\": \"").append(SCHEMA).append("\",\n")
            append("  \"version\": ").append(VERSION).append(",\n")
            append("  \"exportedAt\": \"").append(stamp).append("\",\n")
            append("  \"keyboardCalibrationPx\": ").append(calib).append(",\n")
            append("  \"assistantName\": ").append(quote(name)).append(",\n")
            append("  \"island\": ").append(CopilotConfig.isIslandEnabled(context)).append(",\n")
            append("  \"companion\": ").append(CopilotConfig.isCompanionEnabled(context)).append(",\n")
            append("  \"heartbeat\": ").append(CopilotConfig.isHeartbeatEnabled(context)).append(",\n")
            append("  \"habitLearning\": ").append(CopilotConfig.isHabitLearningEnabled(context)).append(",\n")
            append("  \"memory\": ").append(CopilotConfig.isMemoryEnabled(context)).append(",\n")
            append("  \"pad\": ").append(CopilotConfig.isPadEnabled(context)).append(",\n")
            append("  \"affectRecall\": ").append(CopilotConfig.isAffectRecallEnabled(context)).append(",\n")
            append("  \"diary\": ").append(CopilotConfig.isDiaryEnabled(context)).append(",\n")
            append("  \"autoConsolidate\": ").append(CopilotConfig.isAutoConsolidateEnabled(context)).append("\n")
            append("}\n")
        }
    }

    /**
     * 导入：把 JSON 应用到本机设置。
     *
     * @return 给用户看的一句话（成功了几项 / 为什么失败）
     */
    fun import(context: Context, json: String): String {
        val map = MiniJson.parseObject(json)
            ?: return "没认出这是预设：里面找不到 JSON。请把导出的整段文字都贴进来。"

        val schema = (map["schema"] as? String)?.trim()
        if (schema != SCHEMA) {
            return "这不是 LunaMacro 的预设（schema=${schema ?: "缺失"}）。"
        }

        var applied = 0
        var skipped = 0

        fun boolOf(key: String): Boolean? = when (val v = map[key]) {
            is Boolean -> v
            is String -> v.equals("true", ignoreCase = true)
            else -> null
        }

        // ---- 键盘校准：唯一需要「量」的东西，也是这个功能的主角 ----
        when (val v = map["keyboardCalibrationPx"]) {
            is Int -> {
                if (v >= 0) {
                    CopilotConfig.setKeyboardCalibrationPx(context, v)
                    applied++
                } else skipped++
            }
            is Double -> {
                val px = v.toInt()
                if (px >= 0) {
                    CopilotConfig.setKeyboardCalibrationPx(context, px)
                    applied++
                } else skipped++
            }
            else -> skipped++
        }

        // ---- 名字：走 setAssistantName（它自己会做长度校验和空值判断） ----
        (map["assistantName"] as? String)?.trim()?.takeIf { it.isNotEmpty() }?.let { n ->
            if (CopilotConfig.setAssistantName(context, n)) applied++ else skipped++
        }

        fun applyFlag(key: String, setter: (Boolean) -> Unit) {
            val b = boolOf(key) ?: run { skipped++; return }
            setter(b)
            applied++
        }

        applyFlag("island") { CopilotConfig.setIslandEnabled(context, it) }
        applyFlag("companion") { CopilotConfig.setCompanionEnabled(context, it) }
        applyFlag("heartbeat") { CopilotConfig.setHeartbeatEnabled(context, it) }
        applyFlag("habitLearning") { CopilotConfig.setHabitLearningEnabled(context, it) }
        applyFlag("memory") { CopilotConfig.setMemoryEnabled(context, it) }
        applyFlag("pad") { CopilotConfig.setPadEnabled(context, it) }
        applyFlag("affectRecall") { CopilotConfig.setAffectRecallEnabled(context, it) }
        applyFlag("diary") { CopilotConfig.setDiaryEnabled(context, it) }
        applyFlag("autoConsolidate") { CopilotConfig.setAutoConsolidateEnabled(context, it) }

        Log.i(TAG, "导入预设：应用 $applied 项，跳过 $skipped 项")
        return if (applied == 0) {
            "这份预设里没有能用的设置（跳过 $skipped 项）。可能来自更旧的版本。"
        } else {
            "已导入 $applied 项设置${if (skipped > 0) "，跳过 $skipped 项（认不出或类型不对）" else ""}。"
        }
    }

    /** 导出并写入剪贴板，返回给用户看的提示 */
    fun exportToClipboard(context: Context): String {
        val text = export(context)
        return if (writeClipboard(context, text, "LunaMacro 预设")) {
            "预设已复制到剪贴板，粘到备忘录或发给自己就能带走。"
        } else {
            "复制失败：剪贴板不可用。"
        }
    }

    /** 从剪贴板读文本（没有就给空串） */
    fun readClipboard(context: Context): String {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = cm?.primaryClip ?: return ""
        if (clip.itemCount <= 0) return ""
        return clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
    }

    fun importFromClipboard(context: Context): String {
        val text = readClipboard(context).trim()
        if (text.isEmpty()) return "剪贴板是空的。先把预设文字复制进来。"
        return import(context, text)
    }

    private fun writeClipboard(context: Context, text: String, label: String): Boolean = runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        true
    }.getOrDefault(false)

    /** 极简 JSON 字符串转义（只处理必要字符，够用且不引三方库） */
    private fun quote(s: String): String = buildString {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }
}
