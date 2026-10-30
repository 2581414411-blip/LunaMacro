package com.nexus.agent.plugin

import android.content.Context
import android.net.Uri
import android.util.Log
import com.nexus.agent.copilot.CopilotEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * 插件仓库（本地文件系统）。
 *
 * 目录结构：
 * ```
 * filesDir/plugins/
 *   genshin_daily/plugin.json      ← 一个插件一个目录
 *   starrail_daily/plugin.json
 *   随便写的.json                   ← 兼容：根目录散装 json 也认
 * ```
 *
 * 设计取舍：
 * - **插件不含可执行代码**，只是「声明 + 配置」。避免动态加载 dex 带来的安全风险与合规问题，
 *   也便于二创作者只改 JSON 就能产出新玩法。
 * - 导入 zip 时先在 cacheDir 解压到临时目录，校验 `plugin.json` 合法后再搬进正式目录，
 *   避免坏包把已有插件覆盖成空目录。
 */
object PluginManager {

    private const val TAG = "PluginManager"

    /** 插件根目录 */
    const val DIR_NAME = "plugins"

    /** 插件配置（模型选择 + 表单值） */
    private const val PREFS = "plugin_settings"

    /** 内置示例是否已写入过的标记 */
    private const val KEY_BUILTIN = "builtin_seeded_v1"

    /** 安装结果 */
    sealed class InstallResult {
        data class Ok(val manifest: PluginManifest, val overwritten: Boolean) : InstallResult()
        data class Fail(val reason: String) : InstallResult()
    }

    // ------------------------------------------------------------------ 目录 / 读取

    fun dir(context: Context): File = File(context.filesDir, DIR_NAME).apply { mkdirs() }

    /** 列出全部插件（按名称排序；坏 JSON 直接跳过并打日志） */
    fun list(context: Context): List<PluginManifest> {
        ensureBuiltins(context)
        val root = dir(context)
        val out = mutableListOf<PluginManifest>()

        // 1) 子目录里的 plugin.json
        root.listFiles()?.forEach { f ->
            if (f.isDirectory) {
                val mf = File(f, "plugin.json")
                if (mf.isFile) parseFile(mf)?.let { out.add(it) }
            } else if (f.isFile && f.name.endsWith(".json", ignoreCase = true)) {
                // 2) 根目录散装 json
                parseFile(f)?.let { out.add(it) }
            }
        }
        return out.distinctBy { it.id }.sortedBy { it.name }
    }

    private fun parseFile(f: File): PluginManifest? = try {
        val m = PluginManifest.parse(f.readText(Charsets.UTF_8))
        if (m == null) Log.w(TAG, "插件无效（缺 id/name）：${f.absolutePath}")
        m
    } catch (e: Throwable) {
        Log.w(TAG, "读取插件失败 ${f.name}：${e.message}")
        null
    }

    /** 按 id 取插件 */
    fun load(context: Context, id: String): PluginManifest? = list(context).firstOrNull { it.id == id }

    // ------------------------------------------------------------------ 安装 / 删除

    /**
     * 从 zip 安装插件。
     *
     * 兼容三种打包方式：`plugin.json` 在根、在单层目录里、外面还套了一层（GitHub 下载常见）。
     */
    suspend fun installZip(context: Context, uri: Uri): InstallResult = withContext(Dispatchers.IO) {
        val temp = File(context.cacheDir, "plugin_import_${System.currentTimeMillis()}")
        try {
            temp.mkdirs()
            val opened = context.contentResolver.openInputStream(uri)
                ?: return@withContext InstallResult.Fail("无法打开所选文件")

            var entries = 0
            opened.use { input ->
                ZipInputStream(BufferedInputStream(input)).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val rel = sanitizeEntry(entry.name)
                        if (rel != null && !entry.isDirectory) {
                            val out = File(temp, rel)
                            val parent = out.parentFile
                            if (parent != null && !parent.exists()) parent.mkdirs()
                            try {
                                FileOutputStream(out).use { fos -> zip.copyTo(fos) }
                                entries++
                            } catch (e: Throwable) {
                                Log.w(TAG, "解压 ${entry.name} 失败：${e.message}")
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }
            if (entries == 0) return@withContext InstallResult.Fail("压缩包是空的或格式不对")

            // 找 plugin.json（取层级最浅的那个）
            val manifestFile = temp.walkTopDown()
                .filter { it.isFile && it.name.equals("plugin.json", ignoreCase = true) }
                .minByOrNull { it.absolutePath.count { c -> c == File.separatorChar } }
                ?: return@withContext InstallResult.Fail("压缩包里没有 plugin.json")

            val manifest = try {
                PluginManifest.parse(manifestFile.readText(Charsets.UTF_8))
            } catch (e: Throwable) {
                null
            } ?: return@withContext InstallResult.Fail("plugin.json 解析失败（需要 id 和 name 字段）")

            val sourceRoot = manifestFile.parentFile ?: temp
            val target = File(dir(context), manifest.id)
            val overwritten = target.exists()
            if (overwritten) target.deleteRecursively()
            target.mkdirs()

            copyTree(sourceRoot, target)
            Log.i(TAG, "插件安装成功：${manifest.id}（$entries 个文件，覆盖=$overwritten）")
            InstallResult.Ok(manifest, overwritten)
        } catch (e: Throwable) {
            Log.e(TAG, "安装插件失败", e)
            InstallResult.Fail("安装失败：${e.message ?: e.javaClass.simpleName}")
        } finally {
            try {
                temp.deleteRecursively()
            } catch (_: Throwable) {
            }
        }
    }

    /** 复制目录树（跳过临时目录自身） */
    private fun copyTree(src: File, dst: File) {
        src.listFiles()?.forEach { f ->
            val out = File(dst, f.name)
            if (f.isDirectory) {
                out.mkdirs()
                copyTree(f, out)
            } else {
                try {
                    f.inputStream().use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                } catch (e: Throwable) {
                    Log.w(TAG, "复制 ${f.name} 失败：${e.message}")
                }
            }
        }
    }

    /**
     * zip 条目名安全化：拒绝绝对路径与 `..`，防止解压逃逸。
     *
     * @return 相对路径；非法返回 null
     */
    private fun sanitizeEntry(name: String): String? {
        var n = name.replace('\\', '/').trim()
        if (n.isEmpty()) return null
        n = n.trimStart('/')
        val parts = n.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        if (parts.size > 8) return null
        return parts.joinToString(File.separator)
    }

    /** 删除插件（内置插件也允许删，重装 App 才会回来） */
    fun delete(context: Context, id: String): Boolean {
        val target = File(dir(context), id)
        val ok = if (target.exists()) target.deleteRecursively() else false
        // 散装 json 形式
        val loose = File(dir(context), "$id.json")
        val ok2 = if (loose.exists()) loose.delete() else false
        // 顺手清配置
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("$id.model")
            .apply()
        Log.i(TAG, "删除插件 $id：目录=$ok 文件=$ok2")
        return ok || ok2
    }

    // ------------------------------------------------------------------ 配置持久化

    /** 读取插件配置（schema 默认值 + 用户已保存值） */
    fun settings(context: Context, manifest: PluginManifest): MutableMap<String, String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val map = mutableMapOf<String, String>()
        manifest.settingsSchema.forEach { f ->
            map[f.key] = prefs.getString("${manifest.id}.${f.key}", f.default) ?: f.default
        }
        return map
    }

    fun saveSettings(context: Context, manifest: PluginManifest, values: Map<String, String>) {
        val e = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        values.forEach { (k, v) -> e.putString("${manifest.id}.$k", v) }
        e.apply()
    }

    /** 该插件选用的模型（用户覆盖 > 清单推荐） */
    fun model(context: Context, manifest: PluginManifest): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("${manifest.id}.model", null)
            ?: manifest.recommendedModel

    fun setModel(context: Context, manifest: PluginManifest, model: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("${manifest.id}.model", model)
            .apply()
    }

    // ------------------------------------------------------------------ 运行

    /**
     * 运行插件：把「目标 + 当前配置」拼成一句任务，交给副驾驶大脑执行。
     *
     * @param onReply 过程/结果回调（主线程）
     * @param onDone  结束回调
     */
    fun run(
        context: Context,
        manifest: PluginManifest,
        onReply: ((String) -> Unit)? = null,
        onDone: (() -> Unit)? = null
    ) {
        val task = buildTaskText(context, manifest)
        val model = model(context, manifest)
        Log.i(TAG, "运行插件 ${manifest.id}：model=$model task=$task")
        CopilotEngine.ask(
            context = context,
            userText = task,
            onReply = onReply,
            onDone = onDone,
            preferCloud = model == PluginManifest.MODEL_CLOUD
        )
    }

    /** 目标 + 配置摘要（只带非空、非默认噪音的项） */
    fun buildTaskText(context: Context, manifest: PluginManifest): String {
        val values = settings(context, manifest)
        val sb = StringBuilder(manifest.taskText)
        val parts = manifest.settingsSchema.mapNotNull { f ->
            val v = values[f.key]?.trim().orEmpty()
            if (v.isEmpty()) null else "${f.label}=$v"
        }
        if (parts.isNotEmpty()) {
            sb.append("\n（配置：").append(parts.joinToString("，")).append("）")
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ 内置示例

    /**
     * 首次运行时写入两个示例插件，让「插件」页不空着，也给二创者一个可抄的模板。
     */
    fun ensureBuiltins(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_BUILTIN, false)) return
        try {
            val root = dir(context)
            writeBuiltin(
                root, "genshin_daily", "原神日常",
                "每日委托 + 树脂清理 + 顺手采集，按配置执行",
                "com.miHoYo.Yuanshen",
                "完成原神今天的每日委托并领取奖励",
                PluginManifest.MODEL_CLOUD,
                listOf(
                    PluginField("times", "执行次数", PluginFieldType.NUMBER, "1", hint = "一次日常通常填 1"),
                    PluginField("collect", "顺手采集", PluginFieldType.BOOL, "false", hint = "路上看到材料就采"),
                    PluginField("style", "操作风格", PluginFieldType.SELECT, "保守", listOf("保守", "激进"))
                )
            )
            writeBuiltin(
                root, "starrail_daily", "星穹铁道日常",
                "清日常委托、领取奖励、消耗开拓力",
                "com.miHoYo.hkrpg",
                "完成崩坏星穹铁道今天的日常任务并领取奖励",
                PluginManifest.MODEL_CLOUD,
                listOf(
                    PluginField("power", "是否清开拓力", PluginFieldType.BOOL, "true"),
                    PluginField("times", "执行次数", PluginFieldType.NUMBER, "1")
                )
            )
            prefs.edit().putBoolean(KEY_BUILTIN, true).apply()
            Log.i(TAG, "内置示例插件已写入")
        } catch (e: Throwable) {
            Log.w(TAG, "写入内置插件失败：${e.message}")
        }
    }

    private fun writeBuiltin(
        root: File,
        id: String,
        name: String,
        desc: String,
        pkg: String,
        goal: String,
        model: String,
        schema: List<PluginField>
    ) {
        val d = File(root, id)
        if (File(d, "plugin.json").exists()) return
        d.mkdirs()
        val m = PluginManifest(
            id = id,
            name = name,
            description = desc,
            packageName = pkg,
            recommendedModel = model,
            goal = goal,
            settingsSchema = schema,
            builtin = true
        )
        File(d, "plugin.json").writeText(m.toJson().toString(2), Charsets.UTF_8)
    }
}
