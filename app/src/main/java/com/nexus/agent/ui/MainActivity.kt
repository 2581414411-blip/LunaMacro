package com.nexus.agent.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.nexus.agent.accessibility.NexusAccessibilityService
import com.nexus.agent.accessibility.UiTreeParser
import com.nexus.agent.agent.AgentRunner
import com.nexus.agent.agent.LlmClient
import com.nexus.agent.agent.LlmClientFactory
import com.nexus.agent.agent.ScreenCapture
import com.nexus.agent.input.ShizukuManager
import com.nexus.agent.overlay.ThinkingOverlay
import com.nexus.agent.system.StabilityHelper
import com.nexus.agent.wakeword.WakeWordDetector
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

/**
 * 主界面 —— 全模块测试台。
 *
 * 按钮与对应模块：
 * 1. 「测试 Shizuku 连接」   —— 连接/授权状态
 * 2. 「测试注入点击」        —— Shizuku 输入注入（命中下方绿色按钮）
 * 3. 「测试被点击」          —— 注入目标
 * 4. 「测试截图」            —— ScreenCapture（screencap）
 * 5. 「测试读屏」            —— 无障碍服务 + UiTreeParser
 * 6. 「配置 LLM」            —— 弹窗填 provider/baseUrl/apiKey/model
 * 7. 「测试 LLM」            —— LlmClient（需先配置）
 */
class MainActivity : ComponentActivity() {

    /** 语音唤醒检测器（懒加载，onCreate 时初始化） */
    private lateinit var wakeWordDetector: WakeWordDetector

    /** 唤醒监听是否已开启 */
    @Volatile
    private var wakeListening = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ShizukuManager.init(applicationContext)

        wakeWordDetector = WakeWordDetector(applicationContext)
        wakeWordDetector.onWakeWord = {
            // 唤醒后：弹 Toast 提示 + 把 App 带到前台待命
            runOnUiThread {
                Toast.makeText(this, "已唤醒：你好高总！", Toast.LENGTH_SHORT).show()
                // 聚焦输入框待命（把 App 带到前台）
                try {
                    val intent = android.content.Intent(this, MainActivity::class.java).apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    }
                    startActivity(intent)
                } catch (e: Throwable) {
                    Log.w("WakeWord", "前台化失败", e)
                }
            }
        }

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NexusHome(
                        onTestShizuku = ::testShizukuConnection,
                        onTestInject = ::testInjection,
                        onTestCapture = ::testCapture,
                        onTestUiTree = ::testUiTree,
                        onTestLlm = ::testLlm,
                        onSaveLlm = ::saveLlmConfig,
                        onLoadLlm = ::loadLlmConfig,
                        onRunTask = ::runAgentTask,
                        onPermissionStatus = ::permissionStatusText,
                        onOpenOverlaySettings = ::openOverlaySettings,
                        onOpenAccessibilitySettings = ::openAccessibilitySettings,
                        onOpenBatteryOptimization = ::openBatteryOptimizationSettings,
                        onOpenAutoStart = ::openAutoStartSettings,
                        onOpenBackgroundPower = ::openBackgroundPowerSettings,
                        onRecordPermissionStatus = ::recordPermissionStatus,
                        onToggleWakeWord = ::toggleWakeWord
                    )
                }
            }
        }
    }

    private fun testShizukuConnection() {
        val status = when {
            Shizuku.pingBinder() && ShizukuManager.hasShizukuPermission() ->
                "Shizuku 已连接且已授权 ✓"
            Shizuku.pingBinder() ->
                "Shizuku 已连接，但未授权"
            else ->
                "Shizuku 未连接（请确认 Shizuku App 已启动）"
        }
        Toast.makeText(this, status, Toast.LENGTH_SHORT).show()
    }

    private fun testInjection(
        targetX: Int,
        targetY: Int,
        onResult: (String) -> Unit
    ) {
        if (!ShizukuManager.isShizukuAvailable()) {
            onResult("Shizuku 未连接")
            return
        }
        if (!ShizukuManager.hasShizukuPermission()) {
            ShizukuManager.requestShizukuPermission()
            onResult("正在请求 Shizuku 权限...")
            return
        }
        if (targetX < 0 || targetY < 0) {
            onResult("目标按钮坐标未就绪")
            return
        }

        ShizukuManager.bind { service ->
            val latency = ShizukuManager.injectTapAt(targetX.toFloat(), targetY.toFloat())
            val result = if (latency >= 0) {
                "✅ 已注入点击 (${targetX}, ${targetY})，耗时 ${latency}ms\n请观察下方按钮是否变绿"
            } else {
                "❌ 注入失败（服务不可用）"
            }
            onResult(result)
            Toast.makeText(this, result, Toast.LENGTH_SHORT).show()
        }
    }

    private fun testCapture(onResult: (String) -> Unit) {
        if (!ShizukuManager.isShizukuAvailable()) {
            onResult("Shizuku 未连接，无法截图")
            return
        }
        if (!ShizukuManager.hasShizukuPermission()) {
            ShizukuManager.requestShizukuPermission()
            onResult("正在请求 Shizuku 权限...")
            return
        }

        ShizukuManager.bind { service ->
            val start = System.nanoTime()
            val b64 = ScreenCapture.captureBase64()
            val costMs = (System.nanoTime() - start) / 1_000_000
            val result = if (b64 != null) {
                "✅ 截图成功，Base64 长度 ${b64.length} 字符，耗时 ${costMs}ms"
            } else {
                "❌ 截图失败"
            }
            onResult(result)
            Toast.makeText(this, result, Toast.LENGTH_SHORT).show()
        }
    }

    private fun testUiTree(onResult: (String) -> Unit) {
        val service = NexusAccessibilityService.getInstance()
        if (service == null) {
            onResult("❌ 无障碍服务未开启\n请到 设置→无障碍→Nexus Agent 开启")
            return
        }

        val root = service.getRootNode()
        if (root == null) {
            onResult("❌ 获取根节点失败（可能处于受保护窗口）")
            return
        }

        try {
            val json = UiTreeParser.parse(root)
            val preview = if (json.length > 300) json.substring(0, 300) + "..." else json
            onResult("✅ 读屏成功（包名: ${service.getCurrentPackageName()}）\n$preview")
        } catch (e: Throwable) {
            onResult("❌ 解析失败: ${e.message}")
        }
    }

    private fun testLlm(scope: kotlinx.coroutines.CoroutineScope, onResult: (String) -> Unit) {
        val client = LlmClientFactory.create("deepseek", this)
        val (apiKey, _, _) = LlmClientFactory.getConfig(this, "deepseek")
        if (apiKey.isBlank()) {
            onResult("❌ 未配置 API key\n请先点「配置 LLM」填写")
            return
        }
        val messages = listOf(
            LlmClient.Message("user", "回复两个字：你好")
        )
        scope.launch {
            val result = try {
                val reply = client.chat(messages)
                "✅ LLM 回复：$reply"
            } catch (e: Throwable) {
                "❌ LLM 调用失败：${e.message}"
            }
            onResult(result)
        }
    }

    private fun saveLlmConfig(provider: String, apiKey: String, baseUrl: String, model: String) {
        LlmClientFactory.saveConfig(this, provider, apiKey, baseUrl, model)
        Toast.makeText(this, "已保存 $provider 配置", Toast.LENGTH_SHORT).show()
    }

    private fun loadLlmConfig(): LlmConfigData {
        // 固定读 deepseek（当前测试台默认 provider）
        val (apiKey, baseUrl, model) = LlmClientFactory.getConfig(this, "deepseek")
        return LlmConfigData("deepseek", apiKey, baseUrl, model)
    }

    /**
     * 检查悬浮窗权限是否已授予。
     */
    private fun hasOverlayPermission(): Boolean {
        return android.provider.Settings.canDrawOverlays(this)
    }

    /**
     * 跳转到悬浮窗权限设置页。
     */
    private fun openOverlaySettings() {
        try {
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } catch (e: Throwable) {
            Toast.makeText(this, "无法跳转，请手动到 设置→应用→Nexus Agent→悬浮窗 开启", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 检查无障碍服务是否已启用。
     */
    private fun isAccessibilityEnabled(): Boolean {
        val expected = "$packageName/com.nexus.agent.accessibility.NexusAccessibilityService"
        val enabled = android.provider.Settings.Secure.getString(
            contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        return enabled?.split(':')?.any { it.equals(expected, ignoreCase = true) } == true
    }

    /**
     * 跳转到无障碍服务设置页。
     */
    private fun openAccessibilitySettings() {
        try {
            startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Throwable) {
            Toast.makeText(this, "无法跳转，请手动到 设置→无障碍 开启", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 返回权限状态的文本描述（供 UI 展示）。
     */
    private fun permissionStatusText(): String {
        val sb = StringBuilder()
        sb.append(if (hasOverlayPermission()) "✅" else "❌").append(" 悬浮窗权限\n")
        sb.append(if (isAccessibilityEnabled()) "✅" else "❌").append(" 无障碍服务\n")
        sb.append(if (ShizukuManager.isShizukuAvailable() && ShizukuManager.hasShizukuPermission()) "✅" else "❌").append(" Shizuku\n")
        sb.append(if (StabilityHelper.isIgnoringBatteryOptimizations(this)) "✅" else "❌").append(" 忽略电池优化")
        return sb.toString()
    }

    /**
     * 跳转到忽略电池优化设置页。
     */
    private fun openBatteryOptimizationSettings() {
        StabilityHelper.openBatteryOptimizationSettings(this)
    }

    /**
     * 跳转到自启动管理页。
     */
    private fun openAutoStartSettings() {
        StabilityHelper.openAutoStartSettings(this)
    }

    /**
     * 跳转到后台耗电管理页。
     */
    private fun openBackgroundPowerSettings() {
        StabilityHelper.openBackgroundPowerSettings(this)
    }

    /**
     * 检查录音权限是否已授予。
     */
    private fun recordPermissionStatus(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 切换语音唤醒监听。
     *
     * @param enable true 开启 / false 关闭
     * @param onResult 结果回调（文本描述）
     */
    private fun toggleWakeWord(enable: Boolean, onResult: (String) -> Unit) {
        if (enable) {
            // 开启前检查录音权限
            if (!recordPermissionStatus()) {
                onResult("❌ 未授予录音权限\n请先点击下方「申请录音权限」")
                return
            }
            lifecycleScope.launch {
                val ok = wakeWordDetector.start()
                if (ok) {
                    wakeListening = true
                    onResult("✅ 语音唤醒已开启\n说「你好高总」即可唤醒")
                } else {
                    onResult("❌ 唤醒启动失败（模型加载或麦克风异常）")
                }
            }
        } else {
            lifecycleScope.launch {
                wakeWordDetector.stop()
                wakeListening = false
                onResult("已停止语音唤醒")
            }
        }
    }

    /** 当前唤醒监听状态（供 UI 显示） */
    private fun wakeListeningStatus(): Boolean = wakeListening

    /**
     * 运行 Agent 主循环任务。
     *
     * @param goal 用户目标指令
     * @param onStep 每步进度回调（参数为格式化的日志文本）
     * @param onDone 任务结束回调（参数为最终总结文本）
     */
    private fun runAgentTask(
        goal: String,
        onStep: (String) -> Unit,
        onDone: (String) -> Unit
    ) {
        if (goal.isBlank()) {
            onDone("指令为空")
            return
        }
        if (!ShizukuManager.isShizukuAvailable()) {
            onDone("Shizuku 未连接")
            return
        }
        if (!isAccessibilityEnabled()) {
            onDone("❌ 无障碍服务未开启！\n没有它 Agent 就是\"瞎的\"，只能乱点。\n请点下方「打开无障碍设置」开启后再试。")
            return
        }
        if (!hasOverlayPermission()) {
            onDone("⚠️ 悬浮窗权限未开启，思考浮窗无法显示。\n点下方「打开悬浮窗设置」开启后即可看到实时思考过程。")
            // 不 return，浮窗只是增强，不阻塞主流程
        }

        // 确保 UserService 已绑定
        ShizukuManager.bind { _ ->
            lifecycleScope.launch {
                // 显示思考浮窗（若有悬浮窗权限）
                ThinkingOverlay.show(this@MainActivity, "准备执行：$goal")
                try {
                    AgentRunner.runTask(goal, onThinking = { text ->
                        ThinkingOverlay.append(text)
                    }).collect { event ->
                        // 按层级渲染：Planner / Executor / Agent 前缀 + 缩进
                        val line = buildString {
                            when (event.level) {
                                "Executor" -> append("[Executor] ")
                                "Planner" -> append("[Planner] ")
                                else -> append("[Agent] ")
                            }
                            // Executor 内部的"观察/执行/结果"已带缩进，这里原样保留
                            append(event.message)
                        }
                        onStep(line)
                        // 同步刷新浮窗，让思考过程实时可见
                        ThinkingOverlay.update(event.message.take(80))
                    }
                    ThinkingOverlay.update("✅ 任务结束")
                    onDone("任务流程结束")
                } catch (e: Throwable) {
                    ThinkingOverlay.update("❌ ${e.message}")
                    onDone("任务异常：${e.message}")
                } finally {
                    // 延迟 2 秒后隐藏浮窗，给用户看清结果
                    kotlinx.coroutines.delay(2000)
                    ThinkingOverlay.hide()
                }
            }
        }
    }

}

/** LLM 配置数据（provider + apiKey + baseUrl + model） */
data class LlmConfigData(
    val provider: String,
    val apiKey: String,
    val baseUrl: String,
    val model: String
)

@Composable
fun NexusHome(
    onTestShizuku: () -> Unit,
    onTestInject: (targetX: Int, targetY: Int, onResult: (String) -> Unit) -> Unit,
    onTestCapture: (onResult: (String) -> Unit) -> Unit,
    onTestUiTree: (onResult: (String) -> Unit) -> Unit,
    onTestLlm: (scope: kotlinx.coroutines.CoroutineScope, onResult: (String) -> Unit) -> Unit,
    onSaveLlm: (provider: String, apiKey: String, baseUrl: String, model: String) -> Unit,
    onLoadLlm: () -> LlmConfigData,
    onRunTask: (goal: String, onStep: (String) -> Unit, onDone: (String) -> Unit) -> Unit,
    onPermissionStatus: () -> String,
    onOpenOverlaySettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenBatteryOptimization: () -> Unit,
    onOpenAutoStart: () -> Unit,
    onOpenBackgroundPower: () -> Unit,
    onRecordPermissionStatus: () -> Boolean,
    onToggleWakeWord: (enable: Boolean, onResult: (String) -> Unit) -> Unit
) {
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf("") }
    var targetX by remember { mutableStateOf(-1) }
    var targetY by remember { mutableStateOf(-1) }
    var hit by remember { mutableStateOf(false) }

    // LLM 配置对话框状态
    var showLlmDialog by remember { mutableStateOf(false) }

    // Agent 任务状态
    var taskGoal by remember { mutableStateOf("") }
    var taskLog by remember { mutableStateOf("") }
    var taskRunning by remember { mutableStateOf(false) }

    // 语音唤醒状态
    var wakeEnabled by remember { mutableStateOf(false) }
    var wakeStatusText by remember { mutableStateOf("") }
    var hasRecordPerm by remember { mutableStateOf(false) }

    // 录音权限申请
    val recordPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasRecordPerm = granted
        wakeStatusText = if (granted) "✅ 录音权限已授予，可开启语音唤醒" else "❌ 录音权限被拒绝"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Nexus Agent 测试台",
            style = MaterialTheme.typography.headlineLarge
        )
        Spacer(modifier = Modifier.height(16.dp))

        // ===== 权限状态区块 =====
        var permissionText by remember { mutableStateOf("") }
        LaunchedEffect(Unit) { permissionText = onPermissionStatus() }
        Text(
            text = "权限状态：\n${permissionText.ifBlank { onPermissionStatus() }}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            lineHeight = 20.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row {
            TextButton(onClick = {
                onOpenOverlaySettings()
                permissionText = onPermissionStatus()
            }) { Text("悬浮窗设置") }
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(onClick = {
                onOpenAccessibilitySettings()
                permissionText = onPermissionStatus()
            }) { Text("无障碍设置") }
        }
        Row {
            TextButton(onClick = {
                onOpenBatteryOptimization()
                permissionText = onPermissionStatus()
            }) { Text("忽略电池优化") }
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(onClick = {
                onOpenAutoStart()
            }) { Text("自启动管理") }
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(onClick = {
                onOpenBackgroundPower()
            }) { Text("后台耗电") }
        }
        Text(
            text = "提示：要「最稳定」，请在以上三处把 Nexus Agent 全部设为允许（电池优化选「不优化」、自启动开启、后台耗电选「不限制」）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
            lineHeight = 18.sp
        )
        Spacer(modifier = Modifier.height(24.dp))

        Button(onClick = onTestShizuku) {
            Text(text = "测试 Shizuku 连接")
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(onClick = {
            result = ""
            hit = false
            onTestInject(targetX, targetY) { r -> result = r }
        }) {
            Text(text = "测试注入点击")
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = { hit = true },
            modifier = Modifier.onGloballyPositioned { coords ->
                val bounds = coords.boundsInWindow()
                targetX = (bounds.left + bounds.width / 2).toInt()
                targetY = (bounds.top + bounds.height / 2).toInt()
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (hit) Color(0xFF2E7D32) else MaterialTheme.colorScheme.secondary
            )
        ) {
            Text(text = if (hit) "我被点中了！✓" else "测试被点击")
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(onClick = { result = ""; onTestCapture { r -> result = r } }) {
            Text(text = "测试截图")
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(onClick = { result = ""; onTestUiTree { r -> result = r } }) {
            Text(text = "测试读屏")
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row {
            OutlinedButton(onClick = { showLlmDialog = true }) {
                Text(text = "配置 LLM")
            }
            Spacer(modifier = Modifier.width(12.dp))
            Button(onClick = { result = ""; onTestLlm(scope) { r -> result = r } }) {
                Text(text = "测试 LLM")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ===== 语音唤醒 =====
        Text(
            text = "语音唤醒",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "录音权限：${if (onRecordPermissionStatus()) "✅ 已授予" else "❌ 未授予"}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row {
            OutlinedButton(onClick = {
                if (!onRecordPermissionStatus()) {
                    recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    wakeStatusText = "录音权限已授予"
                }
            }) {
                Text(text = "申请录音权限")
            }
            Spacer(modifier = Modifier.width(12.dp))
            Button(
                onClick = {
                    wakeEnabled = !wakeEnabled
                    onToggleWakeWord(wakeEnabled) { msg -> wakeStatusText = msg }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (wakeEnabled) Color(0xFFC62828) else MaterialTheme.colorScheme.primary
                )
            ) {
                Text(text = if (wakeEnabled) "关闭唤醒" else "开启唤醒")
            }
        }

        if (wakeStatusText.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = wakeStatusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
                lineHeight = 18.sp
            )
        }
        Text(
            text = "开启后，说「你好高总」即可唤醒，App 会自动到前台待命。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
            lineHeight = 18.sp
        )

        Spacer(modifier = Modifier.height(24.dp))

        // ===== Agent 主循环 =====
        Text(
            text = "运行 Agent 任务",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = taskGoal,
            onValueChange = { v -> taskGoal = v },
            label = { Text("任务指令（如：打开设置并进入无线网络）") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !taskRunning
        )
        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                if (taskGoal.isBlank()) {
                    taskLog = "请输入任务指令"
                } else {
                    taskLog = "开始执行...\n"
                    taskRunning = true
                    onRunTask(
                        taskGoal,
                        { line -> taskLog += line + "\n\n" },
                        { summary ->
                            taskLog += "--- $summary ---\n"
                            taskRunning = false
                        }
                    )
                }
            },
            enabled = !taskRunning
        ) {
            Text(text = if (taskRunning) "执行中..." else "运行任务")
        }

        if (taskLog.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium
            ) {
                Text(
                    text = taskLog,
                    modifier = Modifier.padding(12.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (result.isNotEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium
            ) {
                Text(
                    text = result,
                    modifier = Modifier.padding(12.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }

    if (showLlmDialog) {
        LlmConfigDialog(
            initial = onLoadLlm(),
            onDismiss = { showLlmDialog = false },
            onSave = { provider, apiKey, baseUrl, model ->
                onSaveLlm(provider, apiKey, baseUrl, model)
                showLlmDialog = false
            }
        )
    }
}

@Composable
fun LlmConfigDialog(
    initial: LlmConfigData,
    onDismiss: () -> Unit,
    onSave: (provider: String, apiKey: String, baseUrl: String, model: String) -> Unit
) {
    var provider by remember { mutableStateOf(initial.provider) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var model by remember { mutableStateOf(initial.model) }
    var providerMenu by remember { mutableStateOf(false) }

    val providers = listOf("openai", "deepseek", "qwen")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("配置 LLM") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // provider 下拉
                OutlinedButton(onClick = { providerMenu = true }) {
                    Text("Provider: $provider")
                }
                DropdownMenu(
                    expanded = providerMenu,
                    onDismissRequest = { providerMenu = false }
                ) {
                    providers.forEach { p ->
                        DropdownMenuItem(
                            text = { Text(p) },
                            onClick = {
                                provider = p
                                providerMenu = false
                            }
                        )
                    }
                }

                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { v -> baseUrl = v },
                    label = { Text("Base URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { v -> apiKey = v },
                    label = { Text("API Key") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { v -> model = v },
                    label = { Text("Model") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(provider, apiKey.trim(), baseUrl.trim(), model.trim()) }) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
