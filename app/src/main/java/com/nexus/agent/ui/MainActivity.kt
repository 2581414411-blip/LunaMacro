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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.core.content.FileProvider
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import com.nexus.agent.audit.AuditLogger
import com.nexus.agent.input.ShizukuManager
import com.nexus.agent.overlay.ThinkingOverlay
import com.nexus.agent.overlay.RunningIndicator
import com.nexus.agent.safety.SafetyPolicy
import com.nexus.agent.system.LogExporter
import com.nexus.agent.system.StabilityHelper
import com.nexus.agent.ui.components.CrossStarDecoration
import com.nexus.agent.ui.components.GeometricBackground
import com.nexus.agent.ui.components.GeometricTitle
import com.nexus.agent.ui.components.LunaButton
import com.nexus.agent.ui.components.LunaCard
import com.nexus.agent.ui.components.LunaNavigationBar
import com.nexus.agent.ui.theme.LunaErrBg
import com.nexus.agent.ui.theme.LunaErrFg
import com.nexus.agent.ui.theme.LunaOkBg
import com.nexus.agent.ui.theme.LunaOnSurfaceVariant
import com.nexus.agent.ui.theme.LunaPrimary
import com.nexus.agent.ui.theme.NexusAgentTheme
import com.nexus.agent.util.AnnouncementChecker
import com.nexus.agent.speech.VoiceAssistant
import com.nexus.agent.speech.WakeWordConfig
import com.nexus.agent.game.DailyCollector
import com.nexus.agent.game.ModelManager
import com.nexus.agent.util.UpdateChecker
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

        // 悬浮窗「取消」按钮 → 取消当前任务协程 + 取消语音流程（回待机）
        ThinkingOverlay.onStopRequested = {
            agentJob?.cancel()
            VoiceAssistant.cancel()
        }

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
                // 每日公告：每天第一次启动拉一次，有公告就弹窗（一个「确定」按钮）
                var announcement by remember {
                    mutableStateOf<AnnouncementChecker.Announcement?>(null)
                }
                LaunchedEffect(Unit) {
                    announcement = AnnouncementChecker.fetchIfNeeded(this@MainActivity)
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    GeometricBackground()
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

                announcement?.let { ann ->
                    AnnouncementDialog(ann) { announcement = null }
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
            Toast.makeText(this, "无法跳转，请手动到 设置→应用→宏月→悬浮窗 开启", Toast.LENGTH_LONG).show()
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
                // 点亮屏幕四周运行灯（任务执行开始）
                RunningIndicator.acquire(this@MainActivity)
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
                    // 熄灭运行灯（任务执行结束；语音链路侧由 VoiceAssistant 再释放一次）
                    RunningIndicator.release()
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
        containerColor = Color.Transparent,
        bottomBar = {
            LunaNavigationBar(
                selectedIndex = selectedTab,
                tabs = listOf(
                    "🏠" to "首页",
                    "📋" to "任务",
                    "⚙️" to "设置",
                    "🎮" to "宏",
                    "🕹️" to "游戏"
                ),
                onSelect = { selectedTab = it }
            )
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
                3 -> MacroTab()
                4 -> GameTab()
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
    var wakeWord by remember { mutableStateOf(WakeWordConfig.getWakeWord(context)) }
    var wakeThreshold by remember { mutableStateOf(WakeWordConfig.getThreshold(context)) }
    var dailyCollecting by remember { mutableStateOf(DailyCollector.isCollecting) }
    var dailyCount by remember { mutableStateOf(DailyCollector.getCount) }
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
    var stellarInstalled by remember { mutableStateOf(ShizukuManager.isStellarInstalled(context)) }
    var shizukuInstalled by remember { mutableStateOf(ShizukuManager.isShizukuInstalled(context)) }
    var runningProvider by remember { mutableStateOf(ShizukuManager.getRunningProvider(context)) }
    var showAccessibilityGuide by remember { mutableStateOf(false) }
    var accessibilityWarned by remember { mutableStateOf(false) }
    var showStellarGuide by remember { mutableStateOf(false) }
    var stellarCircuitOpen by remember { mutableStateOf(ShizukuManager.circuitOpen) }
    var stellarUnstable by remember { mutableStateOf(StabilityHelper.isStellarUnstable(context)) }
    var showShizukuGuide by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        permissionText = onPermissionStatus()
        ShizukuManager.onCircuitOpen = { stellarCircuitOpen = true }
        while (true) {
            accessibilityEnabled = NexusAccessibilityService.isEnabledInSettings(context)
            accessibilityBound = NexusAccessibilityService.isBound()
            shizukuOk = Shizuku.pingBinder() && ShizukuManager.hasShizukuPermission()
            stellarInstalled = ShizukuManager.isStellarInstalled(context)
            shizukuInstalled = ShizukuManager.isShizukuInstalled(context)
            runningProvider = ShizukuManager.getRunningProvider(context)
            stellarCircuitOpen = ShizukuManager.circuitOpen
            stellarUnstable = StabilityHelper.isStellarUnstable(context)
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

    // Stellar 配置引导
    if (showStellarGuide) {
        StellarSetupGuide(onClose = { showStellarGuide = false })
        return
    }

    // 原版 Shizuku 手动激活说明
    if (showShizukuGuide) {
        AlertDialog(
            onDismissRequest = { showShizukuGuide = false },
            title = { Text("如何手动激活 Shizuku") },
            text = {
                Text("1. 手机开启「开发者选项 → 无线调试」\n2. 安装原版 Shizuku（rikka.app）\n3. 打开 Shizuku，选择「无线调试」配对\n4. 完成配对授权\n5. Shizuku 启动后即可使用（重启后需重新激活）")
            },
            confirmButton = {
                TextButton(onClick = {
                    showShizukuGuide = false
                    StabilityHelper.openShizukuWebsite(context)
                }) { Text("去官网") }
            },
            dismissButton = {
                TextButton(onClick = { showShizukuGuide = false }) { Text("知道了") }
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
        GeometricTitle("宏月", modifier = Modifier.fillMaxWidth())

        Spacer(modifier = Modifier.height(24.dp))

        // Shizuku / Stellar 双检测状态卡片
        StatusCard(
            title = "Stellar",
            ok = shizukuOk && runningProvider == "stellar",
            detail = when {
                shizukuOk && runningProvider == "stellar" -> "已连接（支持开机自启）"
                stellarInstalled -> "未运行 · 点击重连"
                else -> "未安装 · 点击安装"
            },
            onClick = if (!shizukuOk && stellarInstalled) {
                {
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        ShizukuManager.tryWakeUpShizuku(context)
                    }
                }
            } else if (!stellarInstalled) {
                { StabilityHelper.openStellarDownloadPage(context) }
            } else null
        )
        Spacer(modifier = Modifier.height(8.dp))
        StatusCard(
            title = "Shizuku（原版）",
            ok = shizukuOk && runningProvider == "shizuku",
            detail = when {
                shizukuOk && runningProvider == "shizuku" -> "已连接（重启后需手动激活）"
                shizukuInstalled -> "未运行"
                else -> "未安装"
            }
        )
        Spacer(modifier = Modifier.height(12.dp))

        if (stellarCircuitOpen) {
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0x33FF5252))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "⚠️ Stellar 服务异常（多次唤醒失败），已停止自动重试。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = { showStellarGuide = true }) { Text("去配置") }
                }
            }
        }
        if (stellarUnstable) {
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0x33FFC107))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "Stellar 在此设备上持续不稳定，建议改用原版 Shizuku + 手动激活（放弃开机自启换稳定性）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row {
                        TextButton(onClick = { showShizukuGuide = true }) { Text("了解手动激活") }
                        TextButton(onClick = { StabilityHelper.openShizukuWebsite(context) }) { Text("切换到原版 Shizuku") }
                    }
                }
            }
        }
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
                LunaButton(
                    onClick = {
                        wakeEnabled = !wakeEnabled
                        if (wakeEnabled && !onRecordPermissionStatus()) {
                            recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            wakeEnabled = false
                            return@LunaButton
                        }
                        onToggleWakeWord(wakeEnabled) { msg -> wakeStatusText = msg }
                    },
                    danger = wakeEnabled,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (wakeEnabled) "关闭唤醒" else "开启唤醒")
                }

                // 唤醒词选择
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "唤醒词",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WakeWordConfig.PRESET.keys.forEach { word ->
                        FilterChip(
                            selected = word == wakeWord,
                            onClick = {
                                wakeWord = word
                                WakeWordConfig.setWakeWord(context, word)
                            },
                            label = { Text(word, fontSize = 12.sp) }
                        )
                    }
                }

                // 唤醒灵敏度（阈值）
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "唤醒灵敏度（越右越严格，误触发越少）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = wakeThreshold,
                    onValueChange = { wakeThreshold = it },
                    valueRange = 0.2f..0.95f,
                    onValueChangeFinished = {
                        WakeWordConfig.setThreshold(context, wakeThreshold)
                    }
                )
                Text(
                    "当前阈值 ${"%.2f".format(wakeThreshold)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "修改唤醒词/灵敏度后，重新开启唤醒生效",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (wakeStatusText.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(wakeStatusText, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 日常收集开关
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("日常收集", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (dailyCollecting) "✅ 采集中 · $dailyCount 张" else "后台静默截屏，训练全能识别",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                LunaButton(
                    onClick = {
                        if (dailyCollecting) {
                            DailyCollector.stop()
                            dailyCollecting = false
                            dailyCount = DailyCollector.getCount
                        } else {
                            val ok = DailyCollector.start()
                            dailyCollecting = ok
                            if (!ok) {
                                android.widget.Toast.makeText(context, "开启失败：需先开启无障碍服务", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    danger = dailyCollecting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (dailyCollecting) "停止收集" else "开启收集")
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 大按钮：开始对话
        LunaButton(
            onClick = onStartChat,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
        ) {
            Text("开始对话", fontSize = 20.sp)
        }
    }
}

@Composable
private fun StatusCard(title: String, ok: Boolean, detail: String, onClick: (() -> Unit)? = null) {
    val container = if (ok) LunaOkBg else LunaErrBg
    val accent = if (ok) LunaPrimary else LunaErrFg
    LunaCard(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        container = container
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 方形状态色块（几何感，替代 emoji）
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .background(accent)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, color = accent)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = LunaOnSurfaceVariant)
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
    var auditTasks by remember { mutableStateOf(AuditLogger.listTasks()) }
    var selectedAuditId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            auditTasks = AuditLogger.listTasks()
            kotlinx.coroutines.delay(3000)
        }
    }

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

        LunaButton(
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
            Text(if (taskRunning) "[ ■ ■ □ ] 执行中..." else "运行任务")
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

        Spacer(modifier = Modifier.height(24.dp))

        // 审计记录（结构化操作日志）
        Text("审计记录", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        if (auditTasks.isEmpty()) {
            Text("暂无审计记录", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            auditTasks.forEach { t ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { selectedAuditId = t.taskId },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(t.userCommand, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, maxLines = 1)
                        Text(
                            "${auditStatusLabel(t.status)} · ${t.stepCount} 步 · ${t.gameName ?: "通用任务"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    // 审计详情对话框
    selectedAuditId?.let { id ->
        val task = remember(id) { AuditLogger.loadTask(id) }
        if (task != null) {
            AlertDialog(
                onDismissRequest = { selectedAuditId = null },
                title = { Text(task.userCommand, fontSize = 16.sp) },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        Text("状态：${task.status} · 规划 ${task.plannedSteps.size} 步 · 动作 ${task.entries.size} 个")
                        Spacer(modifier = Modifier.height(8.dp))
                        if (task.plannedSteps.isNotEmpty()) {
                            Text("规划步骤：", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            task.plannedSteps.forEachIndexed { i, s -> Text("${i + 1}. $s", fontSize = 12.sp) }
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        Text("执行记录：", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        task.entries.take(20).forEach { e ->
                            Text(
                                "${e.actionType}(${e.actionParams.values.joinToString(",")}) → ${e.result}",
                                fontSize = 11.sp,
                                color = Color.Gray
                            )
                        }
                    }
                },
                confirmButton = {
                    Row {
                        TextButton(onClick = { exportAudit(context, id) }) { Text("导出") }
                        TextButton(onClick = { selectedAuditId = null }) { Text("关闭") }
                    }
                }
            )
        }
    }
}

private fun auditStatusLabel(s: String) = when (s) {
    "completed" -> "✅ 完成"
    "failed" -> "❌ 失败"
    "cancelled" -> "⏹️ 取消"
    else -> s
}

private fun exportAudit(context: android.content.Context, taskId: String) {
    val zip = AuditLogger.exportTask(taskId) ?: return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zip)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "导出审计日志"))
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
    var safetyEnabled by remember { mutableStateOf(SafetyPolicy.isEnabled(context)) }
    var showSafetyConfirm by remember { mutableStateOf(false) }
    var showAsrDialog by remember { mutableStateOf(false) }
    var asrKeyConfigured by remember { mutableStateOf(LlmClientFactory.getAsrApiKey(context).isNotBlank()) }
    var updateInfo by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }
    var checkingUpdate by remember { mutableStateOf(false) }

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
            LunaButton(onClick = { llmResult = ""; onTestLlm(scope) { r -> llmResult = r } }) { Text("测试 LLM") }
        }
        if (llmResult.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(llmResult, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 语音识别（ASR）配置
        Text("语音识别（ASR）", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { showAsrDialog = true }) { Text("配置 ASR Key") }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                if (asrKeyConfigured) "✅ 已配置" else "⚠️ 未配置（回退 qwen Key）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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

        // 安全策略
        Text("安全策略", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = safetyEnabled,
                onCheckedChange = { checked ->
                    if (checked) {
                        safetyEnabled = true
                        SafetyPolicy.setEnabled(context, true)
                    } else {
                        showSafetyConfirm = true
                    }
                }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                if (safetyEnabled) "危险操作拦截已开启" else "危险操作拦截已关闭",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 日志导出
        Text("日志", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Row {
            LunaButton(onClick = { LogExporter.export(context) }) {
                Text("一键导出日志")
            }
            Spacer(modifier = Modifier.width(12.dp))
            OutlinedButton(onClick = {
                val zip = AuditLogger.exportAll()
                if (zip != null) {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zip)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "导出审计日志"))
                } else {
                    Toast.makeText(context, "暂无审计日志", Toast.LENGTH_SHORT).show()
                }
            }) { Text("导出所有审计日志") }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 本地模型（瘦身版 APK 需下载）
        ModelDownloadSection()

        Spacer(modifier = Modifier.height(24.dp))

        // 关于
        Text("关于", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        val versionName = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.3.1"
        } catch (e: Throwable) { "0.3.1" }
        Text("宏月 v$versionName\n系统级 AI 助手 · 本地离线", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = {
            scope.launch {
                checkingUpdate = true
                val info = UpdateChecker.checkLatest()
                checkingUpdate = false
                if (info != null && isNewerVersion(info.version, versionName)) {
                    updateInfo = info
                } else {
                    Toast.makeText(context, "已是最新版本", Toast.LENGTH_SHORT).show()
                }
            }
        }) {
            Text(if (checkingUpdate) "检查中..." else "检查更新")
        }
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

    if (showAsrDialog) {
        AsrConfigDialog(
            context = context,
            onDismiss = { showAsrDialog = false },
            onSave = { apiKey, model ->
                if (apiKey.isNotEmpty()) LlmClientFactory.saveAsrApiKey(context, apiKey)
                if (model.isNotEmpty()) LlmClientFactory.saveAsrModel(context, model)
                asrKeyConfigured = LlmClientFactory.getAsrApiKey(context).isNotBlank()
                showAsrDialog = false
            }
        )
    }

    if (showSafetyConfirm) {
        AlertDialog(
            onDismissRequest = { showSafetyConfirm = false },
            title = { Text("关闭安全策略？") },
            text = {
                Text("关闭后，危险操作（卸载/删除/格式化/转账/打开银行、支付、证券类应用等）将不再拦截。\n仅建议高级用户关闭。")
            },
            confirmButton = {
                TextButton(onClick = {
                    safetyEnabled = false
                    SafetyPolicy.setEnabled(context, false)
                    showSafetyConfirm = false
                }) { Text("确认关闭") }
            },
            dismissButton = {
                TextButton(onClick = { showSafetyConfirm = false }) { Text("取消") }
            }
        )
    }

    updateInfo?.let { info ->
        AlertDialog(
            onDismissRequest = { updateInfo = null },
            title = { Text("发现新版本 v${info.version}") },
            text = {
                Text(
                    if (info.notes.isBlank()) "有新版本可下载，是否前往更新？" else info.notes,
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val url = info.apkUrl ?: UpdateChecker.releasesPageUrl()
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                    } catch (e: Throwable) {
                        Toast.makeText(context, "无法打开下载链接", Toast.LENGTH_SHORT).show()
                    }
                    updateInfo = null
                }) { Text("去更新") }
            },
            dismissButton = {
                TextButton(onClick = { updateInfo = null }) { Text("稍后") }
            }
        )
    }
}

/** 版本比较：remote 是否比 current 新（按点分数字段比较） */
private fun isNewerVersion(remote: String, current: String): Boolean {
    val r = remote.split(".").map { it.toIntOrNull() ?: 0 }
    val c = current.split(".").map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(r.size, c.size)) {
        val rv = r.getOrElse(i) { 0 }
        val cv = c.getOrElse(i) { 0 }
        if (rv != cv) return rv > cv
    }
    return false
}

/**
 * 本地模型下载区（瘦身版 APK 用）。
 * 显示视觉/语音模型状态，未下载时提供「下载模型」按钮 + 进度。
 */
@Composable
private fun ModelDownloadSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var visionReady by remember { mutableStateOf(ModelManager.isVisionModelReady(context)) }
    var senseVoiceReady by remember { mutableStateOf(ModelManager.isSenseVoiceReady(context)) }
    var downloading by remember { mutableStateOf(ModelManager.downloading) }
    var progress by remember { mutableStateOf(ModelManager.lastStage) }
    var fraction by remember { mutableStateOf(-1f) }

    Column {
        Text(
            "视觉模型：${if (visionReady) "● 已就绪" else "○ 未下载"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "语音模型：${if (senseVoiceReady) "● 已就绪" else "○ 未下载"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (!visionReady || !senseVoiceReady) {
            Spacer(modifier = Modifier.height(8.dp))
            if (downloading) {
                if (fraction >= 0f) {
                    LinearProgressIndicator(
                        progress = { fraction.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }
                Text(
                    progress.ifEmpty { "准备中…" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "下载中请保持应用在前台，中途退出会从断点续传",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LunaButton(onClick = {
                    scope.launch {
                        downloading = true
                        fraction = -1f
                        progress = "连接下载源…"
                        val ok = ModelManager.ensureModels(context) { p, stage ->
                            fraction = p
                            progress = stage
                        }
                        downloading = false
                        visionReady = ModelManager.isVisionModelReady(context)
                        senseVoiceReady = ModelManager.isSenseVoiceReady(context)
                        fraction = if (ok) 1f else -1f
                        progress = if (ok) "● 模型下载完成，可以开始使用了"
                        else progress.ifEmpty { "下载失败，请检查网络后重试" }
                    }
                }) {
                    Text(if (progress.startsWith("下载失败") || progress.contains("不足")) "重试下载" else "下载模型（约 1.5GB）")
                }
                if (progress.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        progress,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
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

@Composable
fun AsrConfigDialog(
    context: Context,
    onDismiss: () -> Unit,
    onSave: (apiKey: String, model: String) -> Unit
) {
    var apiKey by remember { mutableStateOf(LlmClientFactory.getAsrApiKey(context)) }
    var model by remember { mutableStateOf(LlmClientFactory.getAsrModel(context)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("配置 ASR") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { v -> apiKey = v },
                    label = { Text("ASR API Key（留空则用 qwen Key）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { v -> model = v },
                    label = { Text("ASR 模型") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(apiKey.trim(), model.trim()) }) {
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

/**
 * 每日公告弹窗。
 *
 * 极简几何风：直角、1px 线框（由主题统一覆盖），正文可滚动，只有一个「确定」按钮。
 * 点击确定 / 点外部 / 返回键都直接关闭（当天不再弹）。
 */
@Composable
private fun AnnouncementDialog(
    announcement: AnnouncementChecker.Announcement,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onConfirm,
        title = {
            Column {
                Text(
                    announcement.title,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .width(24.dp)
                            .height(2.dp)
                            .background(LunaPrimary)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .width(10.dp)
                            .height(2.dp)
                            .background(LunaOnSurfaceVariant)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    announcement.content,
                    style = MaterialTheme.typography.bodyMedium
                )
                if (announcement.date.isNotBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        announcement.date,
                        style = MaterialTheme.typography.bodySmall,
                        color = LunaOnSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("确定")
            }
        }
    )
}
