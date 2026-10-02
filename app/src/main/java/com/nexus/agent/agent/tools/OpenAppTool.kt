package com.nexus.agent.agent.tools

import android.content.Intent
import android.util.Log
import org.json.JSONObject

/**
 * open_app —— 按应用名启动应用。
 *
 * 遍历 PackageManager 已安装应用，按 label 模糊匹配：
 * - 唯一匹配 → 启动，返回包名
 * - 多个匹配 → 返回候选列表
 * - 无匹配 → 返回错误
 */
object OpenAppTool : Tool {

    override val name = "open_app"

    override val schema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("appName", JSONObject().apply {
                put("type", "string")
                put("description", "应用中文名或英文名，如\"设置\"、\"微信\"")
            })
        })
        put("required", org.json.JSONArray().put("appName"))
    }

    override fun execute(args: Map<String, Any?>): String {
        val name = args["appName"]?.toString()?.trim().orEmpty()
        if (name.isEmpty()) return "错误：缺少 appName 参数"

        val ctx = com.nexus.agent.input.ShizukuManager.appContext()
            ?: return "错误：应用上下文未初始化"

        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveList = pm.queryIntentActivities(intent, 0)

        // 收集 (label, packageName)，按 label 匹配
        val candidates = mutableListOf<Pair<String, String>>()
        for (ri in resolveList) {
            val pkg = ri.activityInfo.packageName
            val label = try {
                ri.loadLabel(pm).toString()
            } catch (e: Throwable) {
                pkg
            }
            if (label.contains(name, ignoreCase = true) ||
                pkg.contains(name, ignoreCase = true)
            ) {
                candidates.add(label to pkg)
            }
        }

        if (candidates.isEmpty()) {
            return "未找到名为\"$name\"的应用"
        }

        // 去重（同包名只保留一个）
        val unique = candidates.distinctBy { it.second }

        if (unique.size > 1) {
            val list = unique.joinToString("、") { "${it.first}(${it.second})" }
            return "找到 ${unique.size} 个匹配：$list，请用更精确的名称"
        }

        val (label, pkg) = unique.first()
        return try {
            val launch = pm.getLaunchIntentForPackage(pkg)
                ?: return "无法获取 $pkg 的启动 Intent"
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(launch)
            Log.i("NexusTool", "open_app 启动 $label($pkg)")
            "成功启动 $label，包名 $pkg"
        } catch (e: Throwable) {
            Log.e("NexusTool", "open_app failed", e)
            "启动失败：${e.message}"
        }
    }
}
