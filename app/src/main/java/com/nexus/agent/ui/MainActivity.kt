package com.nexus.agent.ui

import android.Manifest
import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.nexus.agent.accessibility.NexusAccessibilityService
import com.nexus.agent.agent.AgentRunner
import com.nexus.agent.agent.LlmClient
import com.nexus.agent.agent.LlmClientFactory
import com.nexus.agent.input.ShizukuManager
import com.nexus.agent.overlay.ThinkingOverlay
import com.nexus.agent.system.StabilityHelper
import com.nexus.agent.ui.theme.NexusAgentTheme
import com.nexus.agent.speech.VoiceAssistant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import rikka.shizuku.Shizuku

/**
 * 主界面 —— Scaffold + NavigationBar 三 Tab（首页 / 任务 / 设置）。
 */
class MainActivity : ComponentActivity() {

    /** 当前任务协程（供停止） */
    private var agentJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 首次启动判断：未完成引导则跳引导页
        val prefs = getSharedPreferences(OnboardingActivity.PREFS, MODE_PRIVATE)
        if (!prefs.getBoolean(OnboardingActivity.KEY_ONBOARDING, false)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        ShizukuManager.init(applicationContext)

        // 悬浮窗「停止」按钮 → 取消当前任务协程
        ThinkingOverlay.onStopRequested = { agentJob?.cancel() }

        // 语音助手接线：状态已由 VoiceAssistant 内部显示到悬浮窗（postStatus），
        // 这里不再重复 append；识别出指令后执行任务，完成播报
        VoiceAssistant.onCommand = { command ->
            runOnUiThread {
                UiState.pendingTab.value = 1
                runAgentTask(
                    command,
                    onStep = {},
                    onDone = {},
                    onSummary = { summary ->
                        VoiceAssistant.reportExecuteResult(summary ?: "任务失败")
                    }
                )
            }
        }

        setContent {
            NexusAgentTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NexusHome(
                        onRunTask = ::runAgentTask,
                        onPermissionStatus = ::permissionStatusText,
                        onOpenOverlaySettings = ::openOverlaySettings,
                        onOpenAccessibilitySettings = ::openAccessibilitySettings,
                        onOpenBatteryOptimization = ::openBatteryOptimizationSettings,
                        onOpenAutoStart = ::openAutoStartSettings,
                        onOpenBackgroundPower = ::openBackgroundPowerSettings,
                        onRecordPermissionStatus = ::recordPermissionStatus,
                        onToggleWakeWord = ::toggleWakeWord,
                        onSaveLlm = ::saveLlmConfig,
                        onLoadLlm = ::loadLlmConfig,
                        onTestLlm = ::testLlm
                    )
                }
            }
        }
    }

    private fun testLlm(scope: kotlinx.coroutines.CoroutineScope, onResult: (String) -> Unit) {
        val provider = LlmClientFactory.getProvider(this)
        val (apiKey, _, _) = LlmClientFactory.getConfig(this, provider)
        if (apiKey.isBlank()) {
            onResult("❌ 未配置 $provider API key\n请到「设置」页填写")
            return
        }
        val client = LlmClientFactory.create(provider, this)
        val messages = listOf(LlmClient.Message("user", "回复两个字：你好"))
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
        LlmClientFactory.saveProvider(this, provider)
        Toast.makeText(this, "已保存 $provider 配置", Toast.LENGTH_SHORT).show()
    }

    private fun loadLlmConfig(): LlmConfigData {
        val provider = LlmClientFactory.getProvider(this)
        val (apiKey, baseUrl, model) = LlmClientFactory.getConfig(this, provider)
        return LlmConfigData(provider, apiKey, baseUrl, model)
    }

    private fun hasOverlayPermission(): Boolean {
        return android.provider.Settings.canDrawOverlays(this)
    }

    private fun openOverlaySettings() {
        try {
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } catch (e: Throwable) {
            Toast.makeText(this, "无法跳转，请手动到 设置→应用→Nexus Agent→悬浮窗 开启", Toast.LENGTH_LONG).show()
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        return NexusAccessibilityService.isServiceRunning(this)
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Throwable) {
            Toast.makeText(this, "无法跳转，请手动到 设置→无障碍 开启", Toast.LENGTH_LONG).show()
        }
    }

    private fun permissionStatusText(): String {
        val sb = StringBuilder()
        sb.append(if (hasOverlayPermission()) "✅" else "❌").append(" 悬浮窗权限\n")
        sb.append(if (isAccessibilityEnabled()) "✅" else "❌").append(" 无障碍服务\n")
        sb.append(if (ShizukuManager.isShizukuAvailable() && ShizukuManager.hasShizukuPermission()) "✅" else "❌").append(" Shizuku\n")
        sb.append(if (StabilityHelper.isIgnoringBatteryOptimizations(this)) "✅" else "❌").append(" 忽略电池优化")
        return sb.toString()
    }

    private fun openBatteryOptimizationSettings() {
        StabilityHelper.requestIgnoreBatteryOptimizations(this)
    }

    private fun openAutoStartSettings() {
        StabilityHelper.openOriginOsAutoStart(this)
    }

    private fun openBackgroundPowerSettings() {
        StabilityHelper.openBackgroundPowerSettings(this)
    }

    private fun recordPermissionStatus(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun toggleWakeWord(enable: Boolean, onResult: (String) -> Unit) {
        if (enable) {
            if (!recordPermissionStatus()) {
                onResult("❌ 未授予录音权限\n请先申请录音权限")
                return
            }
            VoiceAssistant.start(this)
            onResult("✅ 语音助手已开启\n说「你好高总」即可唤醒")
        } else {
            VoiceAssistant.stop()
            onResult("已停止语音助手")
        }
    }

    private fun runAgentTask(
        goal: String,
        onStep: (String) -> Unit,
        onDone: (String) -> Unit,
        onSummary: ((String?) -> Unit)? = null
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
            onDone("❌ 无障碍服务未开启！\n没有它 Agent 就是\"瞎的\"，只能乱点。")
            return
        }
        if (!hasOverlayPermission()) {
            onDone("⚠️ 悬浮窗权限未开启，思考浮窗无法显示。")
        }

        ShizukuManager.bind { _ ->
            agentJob = lifecycleScope.launch {
                ThinkingOverlay.show(this@MainActivity, "准备执行：$goal")
                var finalSummary: String? = null
                try {
                    AgentRunner.runTask(goal, onThinking = { text ->
                        ThinkingOverlay.append(text)
                    }).collect { event ->
                        val line = buildString {
                            when (event.level) {
                                "Executor" -> append("[Executor] ")
                                "Planner" -> append("[Planner] ")
                                else -> append("[Agent] ")
                            }
                            append(event.message)
                        }
                        if (event.level == "Agent" && event.message.startsWith("任务完成：")) {
                            finalSummary = event.message.removePrefix("任务完成：")
                        }
                        onStep(line)
                        ThinkingOverlay.update(event.message.take(80))
                    }
                    ThinkingOverlay.update("✅ 任务结束")
                    onDone("任务流程结束")
                    onSummary?.invoke(finalSummary)
                } catch (e: CancellationException) {
                    ThinkingOverlay.update("⏹ 已停止")
                    onDone("任务已停止")
                    throw e
                } catch (e: Throwable) {
                    ThinkingOverlay.update("❌ ${e.message}")
                    onDone("任务异常：${e.message}")
                    onSummary?.invoke("任务失败：${e.message}")
                } finally {
                    kotlinx.coroutines.delay(1500)
                    ThinkingOverlay.hide()
                }
            }
        }
    }
}

/** 全局 UI 状态（供唤醒回调等非 Compose 代码向 Compose 界面传递信号） */
object UiState {
    /** 待切换的 Tab（0=首页 1=任务 2=设置；0 表示无待切换） */
    val pendingTab = androidx.compose.runtime.mutableStateOf(0)
}

/** LLM 配置数据 */
data class LlmConfigData(
    val provider: String,
    val apiKey: String,
    val baseUrl: String,
    val model: String
)

/** 历史任务存储（SharedPreferences，最近 10 条） */
object TaskHistory {
    private const val PREFS = "task_history"
    private const val KEY = "history"
    private const val MAX = 10

    fun add(context: Context, task: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val list = load(prefs).toMutableList()
        list.add(0, task)
        val arr = JSONArray()
        list.take(MAX).forEach { arr.put(it) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun list(context: Context): List<String> {
        return load(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
    }

    private fun load(prefs: android.content.SharedPreferences): List<String> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.optString(it) }
        } catch (e: Throwable) {
            emptyList()
        }
    }
}

@Composable
fun NexusHome(
    onRunTask: (goal: String, onStep: (String) -> Unit, onDone: (String) -> Unit) -> Unit,
    onPermissionStatus: () -> String,
    onOpenOverlaySettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenBatteryOptimization: () -> Unit,
    onOpenAutoStart: () -> Unit,
    onOpenBackgroundPower: () -> Unit,
    onRecordPermissionStatus: () -> Boolean,
    onToggleWakeWord: (enable: Boolean, onResult: (String) -> Unit) -> Unit,
    onSaveLlm: (provider: String, apiKey: String, baseUrl: String, model: String) -> Unit,
    onLoadLlm: () -> LlmConfigData,
    onTestLlm: (scope: kotlinx.coroutines.CoroutineScope, onResult: (String) -> Unit) -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }

    // 监听唤醒信号：唤醒后自动切到「任务」Tab
    LaunchedEffect(Unit) {
        snapshotFlow { UiState.pendingTab.value }.collect { tab ->
            if (tab > 0) {
                selectedTab = tab
                UiState.pendingTab.value = 0
            }
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Text("🏠", fontSize = 20.sp) },
                    label = { Text("首页") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Text("📋", fontSize = 20.sp) },
                    label = { Text("任务") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Text("⚙️", fontSize = 20.sp) },
                    label = { Text("设置") }
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (selectedTab) {
                0 -> HomeTab(
                    onPermissionStatus = onPermissionStatus,
                    onToggleWakeWord = onToggleWakeWord,
                    onRecordPermissionStatus = onRecordPermissionStatus,
                    onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                    onStartChat = { selectedTab = 1 }
                )
                1 -> TaskTab(onRunTask = onRunTask)
                2 -> SettingsTab(
                    onPermissionStatus = onPermissionStatus,
                    onOpenOverlaySettings = onOpenOverlaySettings,
                    onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                    onOpenBatteryOptimization = onOpenBatteryOptimization,
                    onOpenAutoStart = onOpenAutoStart,
                    onOpenBackgroundPower = onOpenBackgroundPower,
                    onSaveLlm = onSaveLlm,
                    onLoadLlm = onLoadLlm,
                    onTestLlm = onTestLlm
                )
            }
        }
    }
}

@Composable
private fun HomeTab(
    onPermissionStatus: () -> String,
    onToggleWakeWord: (enable: Boolean, onResult: (String) -> Unit) -> Unit,
    onRecordPermissionStatus: () -> Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    onStartChat: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var permissionText by remember { mutableStateOf("") }
    var wakeEnabled by remember { mutableStateOf(false) }
    var wakeStatusText by remember { mutableStateOf("") }

    // 实时监测状态（无障碍 / Shizuku），每 2 秒刷新一次
    // 无障碍分两维：开关是否开（设置列表）+ 服务实例是否真正绑定
    var accessibilityEnabled by remember {
        mutableStateOf(NexusAccessibilityService.isEnabledInSettings(context))
    }
    var accessibilityBound by remember {
        mutableStateOf(NexusAccessibilityService.isBound())
    }
    var shizukuOk by remember {
        mutableStateOf(Shizuku.pingBinder() && ShizukuManager.hasShizukuPermission())
    }
    var showAccessibilityGuide by remember { mutableStateOf(false) }
    var accessibilityWarned by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        permissionText = onPermissionStatus()
        while (true) {
            accessibilityEnabled = NexusAccessibilityService.isEnabledInSettings(context)
            accessibilityBound = NexusAccessibilityService.isBound()
            shizukuOk = Shizuku.pingBinder() && ShizukuManager.hasShizukuPermission()
            // 开关没开时才弹引导；开了后重置标志，下次再关能再提醒
            if (!accessibilityEnabled && !accessibilityWarned) {
                accessibilityWarned = true
                showAccessibilityGuide = true
            } else if (accessibilityEnabled) {
                accessibilityWarned = false
            }
            kotlinx.coroutines.delay(2000)
        }
    }

    // 无障碍未开启的引导弹窗
    if (showAccessibilityGuide) {
        AlertDialog(
            onDismissRequest = { showAccessibilityGuide = false },
            title = { Text("无障碍服务未开启") },
            text = {
                Text("没有无障碍服务，Agent 就无法读取屏幕内容（会变成\u201C瞎子\u201D乱点）。\n请开启后使用。")
            },
            confirmButton = {
                TextButton(onClick = {
                    showAccessibilityGuide = false
                    onOpenAccessibilitySettings()
                }) { Text("去开启") }
            },
            dismissButton = {
                TextButton(onClick = { showAccessibilityGuide = false }) { Text("稍后") }
            }
        )
    }

    val recordPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        wakeStatusText = if (granted) "✅ 录音权限已授予" else "❌ 录音权限被拒绝"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Nexus Agent", style = MaterialTheme.typography.headlineLarge)

        Spacer(modifier = Modifier.height(24.dp))

        // Shizuku 状态卡片
        StatusCard(
            title = "Shizuku",
            ok = shizukuOk,
            detail = if (shizukuOk) "已连接" else "未连接"
        )
        Spacer(modifier = Modifier.height(12.dp))

        // 无障碍服务状态卡片（实时，未开启可点击跳转）
        StatusCard(
            title = "无障碍服务",
            ok = accessibilityEnabled,
            detail = when {
                !accessibilityEnabled -> "未开启 · 点击去开启"
                accessibilityBound -> "已开启"
                else -> "已开启 · 连接中…"
            },
            onClick = if (accessibilityEnabled) null else onOpenAccessibilitySettings
        )
        Spacer(modifier = Modifier.height(24.dp))

        // 语音唤醒开关
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("语音唤醒", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (wakeEnabled) "✅ 已开启" else "说「你好高总」唤醒",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        wakeEnabled = !wakeEnabled
                        if (wakeEnabled && !onRecordPermissionStatus()) {
                            recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            wakeEnabled = false
                            return@Button
                        }
                        onToggleWakeWord(wakeEnabled) { msg -> wakeStatusText = msg }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (wakeEnabled) Color(0xFFC62828) else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(if (wakeEnabled) "关闭唤醒" else "开启唤醒")
                }
                if (wakeStatusText.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(wakeStatusText, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 大按钮：开始对话
        Button(
            onClick = onStartChat,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Text("开始对话", fontSize = 20.sp)
        }
    }
}

@Composable
private fun StatusCard(title: String, ok: Boolean, detail: String, onClick: (() -> Unit)? = null) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(if (ok) "✅" else "❌", fontSize = 24.sp)
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TaskTab(
    onRunTask: (goal: String, onStep: (String) -> Unit, onDone: (String) -> Unit) -> Unit
) {
    val context = LocalContext.current
    var taskGoal by remember { mutableStateOf("") }
    var taskLog by remember { mutableStateOf("") }
    var taskRunning by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(TaskHistory.list(context)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text("运行 Agent 任务", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = taskGoal,
            onValueChange = { v -> taskGoal = v },
            label = { Text("任务指令（如：打开设置并进入 WLAN）") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !taskRunning
        )
        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = {
                if (taskGoal.isBlank()) {
                    taskLog = "请输入任务指令"
                } else {
                    taskLog = "开始执行...\n"
                    taskRunning = true
                    TaskHistory.add(context, taskGoal)
                    history = TaskHistory.list(context)
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
            enabled = !taskRunning,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (taskRunning) "执行中..." else "运行任务")
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

        // 历史任务
        Text("历史任务", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        if (history.isEmpty()) {
            Text("暂无历史任务", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            history.forEachIndexed { i, task ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(modifier = Modifier.padding(12.dp)) {
                        Text("${i + 1}.", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(task)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsTab(
    onPermissionStatus: () -> String,
    onOpenOverlaySettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenBatteryOptimization: () -> Unit,
    onOpenAutoStart: () -> Unit,
    onOpenBackgroundPower: () -> Unit,
    onSaveLlm: (provider: String, apiKey: String, baseUrl: String, model: String) -> Unit,
    onLoadLlm: () -> LlmConfigData,
    onTestLlm: (scope: kotlinx.coroutines.CoroutineScope, onResult: (String) -> Unit) -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var showLlmDialog by remember { mutableStateOf(false) }
    var llmResult by remember { mutableStateOf("") }
    var permissionText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { permissionText = onPermissionStatus() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        // LLM 配置
        Text("LLM 配置", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Row {
            OutlinedButton(onClick = { showLlmDialog = true }) { Text("配置 LLM") }
            Spacer(modifier = Modifier.width(12.dp))
            Button(onClick = { llmResult = ""; onTestLlm(scope) { r -> llmResult = r } }) { Text("测试 LLM") }
        }
        if (llmResult.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(llmResult, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 权限管理
        Text("权限管理", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(permissionText, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        Spacer(modifier = Modifier.height(8.dp))
        Row {
            TextButton(onClick = { onOpenOverlaySettings(); permissionText = onPermissionStatus() }) { Text("悬浮窗") }
            TextButton(onClick = { onOpenAccessibilitySettings(); permissionText = onPermissionStatus() }) { Text("无障碍") }
        }
        Row {
            TextButton(onClick = { onOpenBatteryOptimization(); permissionText = onPermissionStatus() }) { Text("电池优化") }
            TextButton(onClick = onOpenAutoStart) { Text("自启动") }
            TextButton(onClick = onOpenBackgroundPower) { Text("后台耗电") }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 关于
        Text("关于", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        val versionName = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Throwable) { "0.3.1" }
        Text("Nexus Agent v$versionName\n系统级 AI 助手 · 本地离线", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

    val context = LocalContext.current
    val providers = listOf("deepseek", "openai", "qwen", "qwen-vl")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("配置 LLM") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                                // 切换 provider：baseUrl/model 用纯默认值（忽略历史保存的
                                // 可能被污染的旧值），apiKey 回填该 provider 已保存的 key
                                apiKey = LlmClientFactory.getConfig(context, p).first
                                baseUrl = LlmClientFactory.getDefaultBaseUrl(p)
                                model = LlmClientFactory.getDefaultModel(p)
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
