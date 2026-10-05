package com.nexus.agent.audit

import android.graphics.Bitmap
import android.util.Log
import com.nexus.agent.agent.ScreenCapture
import com.nexus.agent.input.ShizukuManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 操作审计日志（单例）。
 *
 * 记录每次 Agent 任务的完整执行过程：每步动作、参数、坐标、截图、结果、耗时。
 * 存储到 `getExternalFilesDir("audit")/{taskId}/`，task.json 增量写入防崩溃丢失。
 */
object AuditLogger {

    private const val TAG = "AuditLogger"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var currentTask: TaskAuditLog? = null

    @Volatile
    private var currentDir: File? = null

    @Volatile
    private var stepCounter = 0

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /**
     * 开始一次任务审计，返回 taskId。
     */
    fun startTask(userCommand: String, gameName: String?): String {
        val taskId = UUID.randomUUID().toString()
        val ctx = ShizukuManager.appContext()
        val dir = ctx?.let { File(it.getExternalFilesDir("audit"), taskId) } ?: File("/dev/null")
        dir.mkdirs()
        File(dir, "screenshots").mkdirs()

        currentTask = TaskAuditLog(
            taskId = taskId,
            userCommand = userCommand,
            gameName = gameName,
            startTime = System.currentTimeMillis(),
            endTime = null,
            status = "running",
            plannedSteps = emptyList(),
            entries = emptyList(),
            summary = null
        )
        currentDir = dir
        stepCounter = 0
        writeTask()
        Log.i(TAG, "startTask $taskId: $userCommand")
        return taskId
    }

    /**
     * 记录规划步骤。
     */
    fun setPlannedSteps(steps: List<String>) {
        val task = currentTask ?: return
        currentTask = task.copy(plannedSteps = steps)
        writeTask()
    }

    /**
     * 记录一步执行，立即写入 task.json。
     */
    fun recordStep(entry: AuditEntry) {
        val task = currentTask ?: return
        currentTask = task.copy(entries = task.entries + entry)
        stepCounter++
        writeTask()
    }

    /**
     * 结束任务审计。
     */
    fun endTask(status: String, summary: String?) {
        val task = currentTask ?: return
        currentTask = task.copy(status = status, summary = summary, endTime = System.currentTimeMillis())
        writeTask()
        // 额外生成人类可读 task.md
        try {
            val dir = currentDir
            if (dir != null) {
                File(dir, "task.md").writeText(generateMarkdown(currentTask!!))
            }
        } catch (e: Throwable) {
            Log.e(TAG, "生成 task.md 失败", e)
        }
        Log.i(TAG, "endTask ${task.taskId}: $status")
        currentTask = null
        currentDir = null
        stepCounter = 0
    }

    fun getCurrentTask(): TaskAuditLog? = currentTask

    /**
     * 截图当前屏幕并保存到当前任务的 screenshots/，返回相对路径。
     * 截图失败返回 null。
     */
    fun saveScreenshot(): String? {
        val task = currentTask ?: return null
        val dir = currentDir ?: return null
        return try {
            val bitmap = ScreenCapture.captureBitmap(720) ?: return null
            val name = "step_${stepCounter + 1}.jpg"
            val file = File(dir, "screenshots/$name")
            scope.launch(Dispatchers.IO) {
                try {
                    FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out) }
                    bitmap.recycle()
                } catch (e: Throwable) {
                    Log.e(TAG, "保存截图失败", e)
                }
            }
            "screenshots/$name"
        } catch (e: Throwable) {
            Log.e(TAG, "截图失败", e)
            null
        }
    }

    // ------------------------------------------------------------------
    // 序列化（org.json 手动，避免 Pair 序列化问题）
    // ------------------------------------------------------------------

    private fun writeTask() {
        val task = currentTask ?: return
        val dir = currentDir ?: return
        try {
            File(dir, "task.json").writeText(taskToJson(task).toString())
        } catch (e: Throwable) {
            Log.e(TAG, "写入 task.json 失败", e)
        }
    }

    private fun taskToJson(task: TaskAuditLog): JSONObject {
        return JSONObject().apply {
            put("taskId", task.taskId)
            put("userCommand", task.userCommand)
            put("gameName", task.gameName ?: JSONObject.NULL)
            put("startTime", task.startTime)
            put("endTime", task.endTime ?: JSONObject.NULL)
            put("status", task.status)
            put("plannedSteps", JSONArray(task.plannedSteps))
            put("entries", JSONArray().apply {
                task.entries.forEach { e -> put(entryToJson(e)) }
            })
            put("summary", task.summary ?: JSONObject.NULL)
        }
    }

    private fun entryToJson(e: AuditEntry): JSONObject {
        return JSONObject().apply {
            put("timestamp", e.timestamp)
            put("stepIndex", e.stepIndex)
            put("stepDescription", e.stepDescription)
            put("actionType", e.actionType)
            put("actionParams", JSONObject(e.actionParams))
            put("targetElement", e.targetElement ?: JSONObject.NULL)
            put("coordinates", e.coordinates?.let { JSONArray().put(it.first).put(it.second) } ?: JSONObject.NULL)
            put("result", e.result)
            put("resultDetail", e.resultDetail ?: JSONObject.NULL)
            put("screenshotPath", e.screenshotPath ?: JSONObject.NULL)
            put("reasoning", e.reasoning ?: JSONObject.NULL)
            put("screenType", e.screenType ?: JSONObject.NULL)
            put("durationMs", e.durationMs)
        }
    }

    // ------------------------------------------------------------------
    // 列表 / 读取 / 导出（批次四、五使用）
    // ------------------------------------------------------------------

    data class TaskSummary(
        val taskId: String,
        val userCommand: String,
        val gameName: String?,
        val startTime: Long,
        val endTime: Long?,
        val status: String,
        val stepCount: Int
    )

    private fun auditRoot(): File? =
        ShizukuManager.appContext()?.getExternalFilesDir("audit")

    /**
     * 列出所有已结束的任务（按时间倒序）。
     */
    fun listTasks(): List<TaskSummary> {
        val root = auditRoot() ?: return emptyList()
        val dirs = root.listFiles { f -> f.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            val jsonFile = File(dir, "task.json")
            if (!jsonFile.exists()) return@mapNotNull null
            try {
                val obj = JSONObject(jsonFile.readText())
                val status = obj.optString("status")
                if (status == "running") return@mapNotNull null  // 跳过进行中的
                TaskSummary(
                    taskId = obj.optString("taskId"),
                    userCommand = obj.optString("userCommand"),
                    gameName = if (obj.isNull("gameName")) null else obj.optString("gameName"),
                    startTime = obj.optLong("startTime"),
                    endTime = if (obj.isNull("endTime")) null else obj.optLong("endTime"),
                    status = status,
                    stepCount = obj.optJSONArray("entries")?.length() ?: 0
                )
            } catch (e: Throwable) {
                Log.e(TAG, "解析 ${dir.name} 失败", e)
                null
            }
        }.sortedByDescending { it.startTime }
    }

    /**
     * 读取单个任务的完整日志。
     */
    fun loadTask(taskId: String): TaskAuditLog? {
        val root = auditRoot() ?: return null
        val jsonFile = File(root, "$taskId/task.json")
        if (!jsonFile.exists()) return null
        return try {
            parseTask(JSONObject(jsonFile.readText()))
        } catch (e: Throwable) {
            Log.e(TAG, "读取任务 $taskId 失败", e)
            null
        }
    }

    private fun parseTask(obj: JSONObject): TaskAuditLog {
        val entriesArr = obj.optJSONArray("entries") ?: JSONArray()
        val entries = mutableListOf<AuditEntry>()
        for (i in 0 until entriesArr.length()) {
            val e = entriesArr.getJSONObject(i)
            val coordArr = e.optJSONArray("coordinates")
            entries.add(
                AuditEntry(
                    timestamp = e.optLong("timestamp"),
                    stepIndex = e.optInt("stepIndex"),
                    stepDescription = e.optString("stepDescription"),
                    actionType = e.optString("actionType"),
                    actionParams = e.optJSONObject("actionParams")?.let { p ->
                        val map = mutableMapOf<String, String>()
                        val keys = p.keys()
                        while (keys.hasNext()) { val k = keys.next(); map[k] = p.optString(k) }
                        map
                    } ?: emptyMap(),
                    targetElement = if (e.isNull("targetElement")) null else e.optString("targetElement"),
                    coordinates = coordArr?.let { Pair(it.getDouble(0).toFloat(), it.getDouble(1).toFloat()) },
                    result = e.optString("result"),
                    resultDetail = if (e.isNull("resultDetail")) null else e.optString("resultDetail"),
                    screenshotPath = if (e.isNull("screenshotPath")) null else e.optString("screenshotPath"),
                    reasoning = if (e.isNull("reasoning")) null else e.optString("reasoning"),
                    screenType = if (e.isNull("screenType")) null else e.optString("screenType"),
                    durationMs = e.optLong("durationMs")
                )
            )
        }
        val stepsArr = obj.optJSONArray("plannedSteps") ?: JSONArray()
        val steps = mutableListOf<String>()
        for (i in 0 until stepsArr.length()) steps.add(stepsArr.getString(i))
        return TaskAuditLog(
            taskId = obj.optString("taskId"),
            userCommand = obj.optString("userCommand"),
            gameName = if (obj.isNull("gameName")) null else obj.optString("gameName"),
            startTime = obj.optLong("startTime"),
            endTime = if (obj.isNull("endTime")) null else obj.optLong("endTime"),
            status = obj.optString("status"),
            plannedSteps = steps,
            entries = entries,
            summary = if (obj.isNull("summary")) null else obj.optString("summary")
        )
    }

    /**
     * 导出单个任务为 zip（位于 cacheDir，供 FileProvider 分享）。
     */
    fun exportTask(taskId: String): File? {
        val root = auditRoot() ?: return null
        val taskDir = File(root, taskId)
        if (!taskDir.exists()) return null
        val ctx = ShizukuManager.appContext() ?: return null
        val zipFile = File(ctx.cacheDir, "audit_${taskId.take(8)}_${System.currentTimeMillis()}.zip")
        zipDir(taskDir, zipFile)
        return zipFile
    }

    /**
     * 批量导出所有任务为 zip。
     */
    fun exportAll(): File? {
        val root = auditRoot() ?: return null
        if (!root.exists()) return null
        val ctx = ShizukuManager.appContext() ?: return null
        val zipFile = File(ctx.cacheDir, "audit_all_${System.currentTimeMillis()}.zip")
        zipDir(root, zipFile)
        return zipFile
    }

    private fun zipDir(srcDir: File, zipFile: File) {
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            srcDir.walkTopDown().forEach { f ->
                if (f.isFile) {
                    val rel = f.relativeTo(srcDir).path.replace('\\', '/')
                    zos.putNextEntry(ZipEntry(rel))
                    zos.write(f.readBytes())
                    zos.closeEntry()
                }
            }
        }
    }

    /**
     * 生成人类可读的 task.md。
     */
    fun generateMarkdown(task: TaskAuditLog): String {
        val sb = StringBuilder()
        sb.append("# 任务审计日志\n\n")
        sb.append("- 任务ID: ${task.taskId}\n")
        sb.append("- 用户指令: ${task.userCommand}\n")
        if (task.gameName != null) sb.append("- 游戏: ${task.gameName}\n")
        sb.append("- 开始时间: ${formatTime(task.startTime)}\n")
        sb.append("- 结束时间: ${task.endTime?.let { formatTime(it) } ?: "-"}\n")
        sb.append("- 状态: ${task.status}\n")
        val duration = (task.endTime ?: System.currentTimeMillis()) - task.startTime
        sb.append("- 耗时: ${formatDuration(duration)}\n\n")

        if (task.plannedSteps.isNotEmpty()) {
            sb.append("## 规划步骤\n")
            task.plannedSteps.forEachIndexed { i, s -> sb.append("${i + 1}. $s\n") }
            sb.append("\n")
        }

        sb.append("## 执行记录\n\n")
        task.entries.forEach { e ->
            sb.append("### 步骤 ${e.stepIndex + 1}: ${e.stepDescription}\n")
            sb.append("- [${formatTime(e.timestamp)}] 动作: ${e.actionType}(${e.actionParams.entries.joinToString { "${it.key}=${it.value}" }})\n")
            e.screenType?.let { sb.append("- [${formatTime(e.timestamp)}] 识别界面: $it\n") }
            e.coordinates?.let { sb.append("- 坐标: (${"%.3f".format(it.first)}, ${"%.3f".format(it.second)})\n") }
            sb.append("- 结果: ${e.result}\n")
            e.resultDetail?.let { sb.append("- 说明: $it\n") }
            e.reasoning?.let { sb.append("- 原因: $it\n") }
            e.screenshotPath?.let { sb.append("- 截图: $it\n") }
            sb.append("\n")
        }

        if (task.summary != null) {
            sb.append("## 结果摘要\n${task.summary}\n")
        }
        return sb.toString()
    }

    private fun formatTime(ms: Long): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        return fmt.format(java.util.Date(ms))
    }

    private fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return "${s / 60}分${s % 60}秒"
    }
}
