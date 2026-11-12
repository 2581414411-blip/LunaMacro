package com.nexus.agent.ui

// 设置页（0.7.11 从 MainActivity 拆出）：LLM/ASR 配置、权限状态、模型下载与导入、更新检查。
// 只做展示与回调编排，业务逻辑仍在各自 util/manager 里。

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.nexus.agent.accessibility.NexusAccessibilityService
import com.nexus.agent.agent.AgentRunner
import com.nexus.agent.agent.ApiDiagnostics
import com.nexus.agent.agent.LlmClient
import com.nexus.agent.agent.LlmClientFactory
import com.nexus.agent.audit.AuditLogger
import com.nexus.agent.input.ShizukuManager
import com.nexus.agent.overlay.RunningIndicator
import com.nexus.agent.safety.SafetyPolicy
import com.nexus.agent.server.LocalAgentServer
import com.nexus.agent.system.AutomationPolicy
import com.nexus.agent.system.CrashHandler
import com.nexus.agent.system.LogExporter
import com.nexus.agent.system.RomProfile
import com.nexus.agent.system.StabilityHelper
import com.nexus.agent.ui.components.LunaOutlinedButton
import com.nexus.agent.ui.components.LunaTextButton
import com.nexus.agent.ui.components.CrossStarDecoration
import com.nexus.agent.ui.components.GeometricBackground
import com.nexus.agent.ui.components.GeometricTitle
import com.nexus.agent.ui.components.LunaButton
import com.nexus.agent.ui.components.LunaCard
import com.nexus.agent.ui.components.LunaNavigationBar
import com.nexus.agent.ui.theme.LunaErrBg
import com.nexus.agent.ui.theme.LunaErrFg
import com.nexus.agent.ui.theme.LunaOkBg
import com.nexus.agent.ui.theme.LunaOkFg
import com.nexus.agent.ui.theme.LunaOnBackground
import com.nexus.agent.ui.theme.LunaOnSurfaceVariant
import com.nexus.agent.ui.theme.LunaWarnFg
import com.nexus.agent.ui.theme.LunaPrimary
import com.nexus.agent.ui.theme.LunaOutline
import com.nexus.agent.ui.theme.LunaSurface
import com.nexus.agent.ui.theme.LunaSurfaceAlt
import com.nexus.agent.ui.theme.NexusAgentTheme
import com.nexus.agent.util.AnnouncementChecker
import com.nexus.agent.util.AppUpdater
import com.nexus.agent.copilot.CopilotConfig
import com.nexus.agent.copilot.CopilotEngine
import com.nexus.agent.copilot.CopilotVoiceInput
import com.nexus.agent.overlay.CopilotIslandOverlay
import com.nexus.agent.speech.PinyinConverter
import com.nexus.agent.speech.VoiceAssistant
import com.nexus.agent.speech.WakeWordConfig
import com.nexus.agent.game.DailyCollector
import com.nexus.agent.game.ModelManager
import com.nexus.agent.game.VisionHub
import com.nexus.agent.game.VisionSelfCheck
import com.nexus.agent.game.VisionWorkerClient
import com.nexus.agent.habit.HabitCollector
import com.nexus.agent.habit.HabitMemory
import com.nexus.agent.habit.HabitSkill
import com.nexus.agent.habit.HabitSkillBridge
import com.nexus.agent.habit.HabitSkillStore
import com.nexus.agent.habit.HabitStore
import com.nexus.agent.habit.HabitTerms
import com.nexus.agent.habit.HabitTrace
import com.nexus.agent.util.UpdateChecker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import rikka.shizuku.Shizuku

@Composable
internal fun SettingsTab(
    onPermissionStatus: () -> String,
    onOpenOverlaySettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenBatteryOptimization: () -> Unit,
    onOpenAutoStart: () -> Unit,
    onOpenBackgroundPower: () -> Unit,
    onSaveLlm: (provider: String, apiKey: String, baseUrl: String, model: String) -> Unit,
    onLoadLlm: () -> LlmConfigData,
    onTestLlm: (
        scope: kotlinx.coroutines.CoroutineScope,
        onResult: (ApiDiagnostics.Report) -> Unit
    ) -> Unit,
    onCheckUpdate: () -> Unit,
    onOpenTaskArchive: () -> Unit,
    /** 实验版更新（非空 → 「检查更新」下方显示红字，点它直接进更新流程） */
    experimentalUpdate: UpdateChecker.UpdateInfo? = null,
    onInstallUpdate: (UpdateChecker.UpdateInfo) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var showLlmDialog by remember { mutableStateOf(false) }
    var permissionText by remember { mutableStateOf("") }
    var safetyEnabled by remember { mutableStateOf(SafetyPolicy.isEnabled(context)) }
    var showSafetyConfirm by remember { mutableStateOf(false) }
    var showAsrDialog by remember { mutableStateOf(false) }
    var asrKeyConfigured by remember { mutableStateOf(LlmClientFactory.getAsrApiKey(context).isNotBlank()) }

    // 副驾驶视觉模型：默认跟随主 provider（之前写死 qwen-vl，导致配了 Key 也用不上）
    var visionProvider by remember { mutableStateOf(LlmClientFactory.getEffectiveVisionProvider(context)) }
    var visionMenu by remember { mutableStateOf(false) }

    // 0.9.7 批次二：独立的云端视觉配置（发图时优先用它提取描述）
    var showVisionConfigDialog by remember { mutableStateOf(false) }
    var visionKeyConfigured by remember { mutableStateOf(LlmClientFactory.getVisionApiKey(context).isNotBlank()) }

    // 助手名字（0.7.11）：显示名 + 提示词里的自称，改了立即生效，不用重启
    var assistantName by remember { mutableStateOf(CopilotConfig.assistantName(context)) }
    var nameError by remember { mutableStateOf("") }

    // 灵动岛 / 情绪陪玩（0.7.11 从副驾驶页挪过来：那个页面已改成聊天流）
    var islandEnabled by remember { mutableStateOf(CopilotConfig.isIslandEnabled(context)) }
    var companionEnabled by remember { mutableStateOf(CopilotConfig.isCompanionEnabled(context)) }

    LaunchedEffect(Unit) { permissionText = onPermissionStatus() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        // 助手身份
        Text("助手身份", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "给它起个名字，聊天/弹幕里它就用这个名字自称",
            style = MaterialTheme.typography.bodySmall,
            color = LunaOnSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = assistantName,
            onValueChange = { raw ->
                // 只挡换行，长度在下面统一校验（超长直接拒绝，不悄悄截断）
                val clean = raw.replace("\n", "").replace("\r", "")
                assistantName = clean
                nameError = if (clean.trim().isEmpty()) {
                    "" // 空 = 恢复默认，不算错误
                } else if (!CopilotConfig.setAssistantName(context, clean)) {
                    "名字最长 12 个字"
                } else {
                    ""
                }
            },
            label = { Text("助手名称", fontSize = 12.sp) },
            placeholder = { Text(CopilotConfig.DEFAULT_ASSISTANT_NAME, fontSize = 13.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        if (nameError.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(nameError, style = MaterialTheme.typography.bodySmall, color = LunaErrFg)
        } else {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "留空 = 恢复默认「${CopilotConfig.DEFAULT_ASSISTANT_NAME}」",
                style = MaterialTheme.typography.bodySmall,
                color = LunaOnSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 灵动岛：开着才有顶部胶囊（0.7.11 支持拖动 + 左右吸附）
        SettingSwitchRow(
            title = "灵动岛",
            desc = "屏幕顶部的黑色胶囊，可以拖动、会自动贴边",
            checked = islandEnabled,
            onCheckedChange = { on ->
                islandEnabled = on
                CopilotConfig.setIslandEnabled(context, on)
                if (on) {
                    if (!CopilotIslandOverlay.show(context)) {
                        islandEnabled = false
                        CopilotConfig.setIslandEnabled(context, false)
                        Toast.makeText(
                            context,
                            "需要悬浮窗权限：设置 → 应用 → 宏月 → 悬浮窗",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    CopilotIslandOverlay.hide()
                }
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        SettingSwitchRow(
            title = "情绪陪玩",
            desc = "画面突变（死亡 / 爆炸 / 秀操作）时自动发弹幕",
            checked = companionEnabled,
            onCheckedChange = { on ->
                companionEnabled = on
                CopilotConfig.setCompanionEnabled(context, on)
                if (on) {
                    if (!CopilotIslandOverlay.isShowing()) CopilotIslandOverlay.show(context)
                    CopilotEngine.start(context)
                    if (!NexusAccessibilityService.isServiceRunning(context)) {
                        Toast.makeText(
                            context,
                            "提示：情绪陪玩靠无障碍截图，请先在首页开启无障碍",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    CopilotEngine.stop()
                }
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 0.9.0 批次三：自动化工具总开关。
        // 这是「纯数字生命」模式的唯一入口：关掉之后工具 Schema 不注入、动作全被拦截，
        // 它只会用文字教用户自己点。默认开，老用户升级后行为不变。
        var automationEnabled by remember { mutableStateOf(AutomationPolicy.isEnabled(context)) }
        SettingSwitchRow(
            title = "允许它操作手机",
            desc = "关掉后只剩聊天：不注入工具、不执行任何点击，AI 只会用文字教你怎么点。" +
                "只想把它当数字生命养的时候关掉它。",
            checked = automationEnabled,
            onCheckedChange = { on ->
                automationEnabled = on
                AutomationPolicy.setEnabled(context, on)
                Toast.makeText(
                    context,
                    if (on) "已开启：它可以帮你点点按按了" else "已切换到纯对话模式（只能聊天）",
                    Toast.LENGTH_SHORT
                ).show()
            }
        )

        // 定制 ROM 专属保活引导（0.9.0 批次三）
        var showMimuTips by remember { mutableStateOf(false) }
        if (RomProfile.isMimu()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "检测到 ${RomProfile.label()}：这类系统会掐后台，建议把自启动、锁后台、" +
                    "电池无限制都打开，否则读屏会时好时坏。",
                style = MaterialTheme.typography.bodySmall,
                color = LunaOnSurfaceVariant,
                lineHeight = 17.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            LunaOutlinedButton(onClick = { showMimuTips = true }) { Text("查看保活设置步骤") }
            if (showMimuTips) {
                AlertDialog(
                    onDismissRequest = { showMimuTips = false },
                    title = { Text("${RomProfile.label()}保活三步") },
                    text = {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            Text("① 应用信息 → 自启动：打开", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "② 最近任务卡片下拉 → 锁定（别被一键清理掉）",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                "③ 电池 → 后台耗电管理 → 允许后台高耗电 / 无限制",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                "三步都做完还是唤不醒 Shizuku 的话，上面的「允许它操作手机」会被" +
                                    "自动关掉 —— 它只陪聊，不再反复弹失败提示。",
                                style = MaterialTheme.typography.bodySmall,
                                color = LunaOnSurfaceVariant,
                                lineHeight = 18.sp
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showMimuTips = false }) { Text("知道了") }
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        Text(
            "隐私熔断：前台是银行 / 支付 / 证券 / 密码类应用时自动停止截屏，离开后恢复；" +
                "截图只在内存里分析，不上传。",
            style = MaterialTheme.typography.bodySmall,
            color = LunaOnSurfaceVariant,
            lineHeight = 17.sp
        )

        Spacer(modifier = Modifier.height(20.dp))

        // LLM 配置
        Text("LLM 配置", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))

        // 0.9.1：按钮从「测试 LLM」改成「测试连通性 & 查余额」。
        // 老按钮只发一句「回复两个字：你好」，失败时把异常 message 直接糊到界面上，
        // 用户看到「HTTP 402」也不知道该去充值还是该换 Key。现在一次点完就能分别拿到
        // 连通性结论 + 余额数字 + 精确错误码。
        var llmTesting by remember { mutableStateOf(false) }
        var llmReport by remember { mutableStateOf<ApiDiagnostics.Report?>(null) }
        // 界面上显示的必须是「真正会发出去」的那组值，不是输入框里残留的内容
        var routingTick by remember { mutableStateOf(0) }
        val routing = remember(routingTick, llmReport) { LlmClientFactory.routing(context) }

        Row(verticalAlignment = Alignment.CenterVertically) {
            LunaOutlinedButton(enabled = !llmTesting, onClick = { showLlmDialog = true }) {
                Text("配置 LLM")
            }
            Spacer(modifier = Modifier.width(12.dp))
            LunaButton(
                enabled = !llmTesting,
                onClick = {
                    // 防重复点击：一次诊断最多 25 秒，期间按钮必须点不动
                    if (llmTesting) return@LunaButton
                    llmTesting = true
                    llmReport = null
                    onTestLlm(scope) { report ->
                        llmReport = report
                        llmTesting = false
                        routingTick++
                    }
                }
            ) { Text(if (llmTesting) "诊断中…" else "测试连通性 & 查余额") }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            "当前路由：${routing.provider} · ${routing.model}" +
                if (routing.hasKey) " · Key 尾号 ${routing.keyTail}" else " · 未填 Key",
            style = MaterialTheme.typography.labelSmall,
            color = LunaOnSurfaceVariant,
            lineHeight = 16.sp
        )
        Text(
            routing.baseUrl,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = LunaOnSurfaceVariant
        )
        if (llmTesting) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "正在探测接口，最多等 25 秒…",
                style = MaterialTheme.typography.labelSmall,
                color = LunaOnSurfaceVariant
            )
        }

        llmReport?.let { report ->
            AlertDialog(
                onDismissRequest = { llmReport = null },
                title = {
                    Text(if (report.ok) "连接正常" else "诊断结果")
                },
                text = {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            report.toDisplayText(),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            // 失败用红字：用户扫一眼就知道「这条要看」
                            color = if (report.ok) LunaOnBackground else LunaErrFg,
                            lineHeight = 19.sp
                        )
                        if (!report.ok) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                "修好之后点「测试连通性 & 查余额」再验一次；" +
                                    "还不行就把上面这段原文发我。",
                                style = MaterialTheme.typography.labelSmall,
                                color = LunaOnSurfaceVariant,
                                lineHeight = 16.sp
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { llmReport = null }) { Text("知道了") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        llmReport = null
                        showLlmDialog = true
                    }) { Text("去配置") }
                }
            )
        }

        // 视觉模型实拍测试（0.8.2）：真的截当前屏幕 → 真的跑本地视觉模型 → 原文照给。
        // 对话里「不读屏」时，先用它把「模型不行」和「路由不行」分开，
        // 不用装 adb、不用抓 logcat。
        var visionTesting by remember { mutableStateOf(false) }
        var visionInferResult by remember { mutableStateOf<String?>(null) }
        // 一键预热 + 端到端自检（0.8.20）：装载 → 截一屏 → 本地描述 → 把模型原话交出来。
        // 以前只有「预热」和「测试视觉模型」两个按钮，前者看不到结果，后者走的是另一套截图逻辑，
        // 所以「测试能出、对话不能出」这问题一直没法自证。现在点一下就把整条链路的每一步摆出来。
        var warming by remember { mutableStateOf(false) }
        var warmResult by remember { mutableStateOf<String?>(null) }
        Spacer(modifier = Modifier.height(8.dp))
        LunaOutlinedButton(
            onClick = {
                if (!warming) {
                    warming = true
                    warmResult = "正在装载模型并看一屏，请稍等…"
                    scope.launch {
                        warmResult = runCatching { VisionHub.diagnose(context) }
                            .getOrElse { "自检没跑起来：${it.javaClass.simpleName} ${it.message}" }
                        warming = false
                    }
                }
            },
            enabled = !warming
        ) { Text(if (warming) "装载 + 看屏中…（首次约 1.3GB）" else "预热并看一屏（端到端自检）") }
        warmResult?.let { msg ->
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                msg,
                style = MaterialTheme.typography.labelSmall,
                color = LunaOnSurfaceVariant,
                lineHeight = 16.sp
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        LunaOutlinedButton(
            onClick = {
                if (!visionTesting) {
                    visionTesting = true
                    scope.launch {
                        // 0.8.6：这段危险操作跑在独立进程（:vision），
                        // 它再崩也带不走主 App —— 之前「点一下就闪回桌面」就是主进程被一起收走了。
                        val outcome = runCatching {
                            VisionWorkerClient.testInfer(context)
                        }.getOrNull()
                        visionTesting = false
                        visionInferResult = when (outcome) {
                            is VisionWorkerClient.Outcome.Done -> outcome.text
                            is VisionWorkerClient.Outcome.ProcessDied -> buildString {
                                appendLine("❌ 视觉子进程被终止")
                                appendLine("原因：${outcome.reason}")
                                appendLine()
                                appendLine("宏月主体这次没有崩——视觉这段是故意关在独立进程里跑的。")
                                append("下一步：设置 → 读屏自检，看「上次 native 调用」写的是什么，")
                                append("或导出模型初始化日志发我。")
                            }
                            null -> "测试没跑起来（看日志 VisionWorker）"
                        }
                    }
                }
            },
            enabled = !visionTesting
        ) { Text(if (visionTesting) "测试中…（首次要装载模型）" else "测试视觉模型（截图并描述）") }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "截一张当前屏幕直接喂给本地视觉模型，把模型原文原样给你看；" +
                "首次会装载 1.3GB 模型，可能等 10~60 秒。",
            style = MaterialTheme.typography.labelSmall,
            color = LunaOnSurfaceVariant,
            lineHeight = 16.sp
        )
        visionInferResult?.let { text ->
            AlertDialog(
                onDismissRequest = { visionInferResult = null },
                title = { Text("测试视觉模型") },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        Text(
                            text,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { visionInferResult = null }) { Text("知道了") }
                }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 副驾驶读屏用的 provider：显式指定过就用它，否则跟随主模型
        val visionModel = LlmClientFactory.getConfig(context, visionProvider).third
        val visionCapable = LlmClientFactory.supportsVision(visionProvider, visionModel)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                LunaOutlinedButton(onClick = { visionMenu = true }) {
                    Text("读屏模型：$visionProvider")
                }
                DropdownMenu(
                    expanded = visionMenu,
                    onDismissRequest = { visionMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("跟随主模型（${LlmClientFactory.getProvider(context)}）") },
                        onClick = {
                            LlmClientFactory.clearVisionProvider(context)
                            visionProvider = LlmClientFactory.getEffectiveVisionProvider(context)
                            visionMenu = false
                        }
                    )
                    listOf("qwen-vl", "qwen", "openai", "deepseek").forEach { p ->
                        DropdownMenuItem(
                            text = { Text(p) },
                            onClick = {
                                LlmClientFactory.saveVisionProvider(context, p)
                                visionProvider = p
                                visionMenu = false
                            }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                if (visionCapable) "● 可读屏" else "○ 纯文本",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            if (visionCapable) {
                "副驾驶问答会带上当前屏幕截图"
            } else {
                "该模型不支持图片，副驾驶会以纯文本回答（不读屏）"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 0.9.7 批次二：独立的云端视觉模型（发图时优先用它提取描述，再喂给纯文本主模型）
        Text("云端视觉模型（可选）", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "主模型是纯文本（如 deepseek-chat）时，你发图它看不来。这里配一个能看图的云端模型" +
                "（如 qwen-vl-max），发图时会先把图片转成文字描述，再交给主模型。不配则回退本地视觉。",
            style = MaterialTheme.typography.bodySmall,
            color = LunaOnSurfaceVariant,
            lineHeight = 17.sp
        )
        // 0.9.10：把「为什么看不了图」直接写在配置入口旁边，别让它只出现在日志里。
        // 只对「主模型本身不能看图、视觉 Key 也没配」这一种情况报警 ——
        // 用 qwen-vl 当主模型的用户看到红字只会困惑。
        run {
            val mainProvider = LlmClientFactory.getProvider(context)
            val mainModel = LlmClientFactory.getConfig(context, mainProvider).third
            val mainCanVision = LlmClientFactory.supportsVision(mainProvider, mainModel)
            if (!mainCanVision && !visionKeyConfigured) {
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(LunaErrBg)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Text(
                        "💡 提示：主模型若为 DeepSeek，必须在此处配置通义千问的 Key 才能看图。" +
                            "（当前主模型：$mainProvider / $mainModel，纯文本）",
                        fontSize = 12.sp,
                        color = LunaErrFg,
                        lineHeight = 17.sp
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LunaOutlinedButton(onClick = { showVisionConfigDialog = true }) {
                Text("配置云端视觉")
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                when {
                    visionKeyConfigured ->
                        "${LlmClientFactory.getVisionModel(context)} · Key 尾号 " +
                            LlmClientFactory.getVisionApiKey(context).takeLast(4)
                    LlmClientFactory.hasUsableVision(context) ->
                        "复用主模型视觉 · ${LlmClientFactory.getProvider(context)}"
                    else -> "未配置（发图回退本地视觉）"
                },
                style = MaterialTheme.typography.labelSmall,
                color = LunaOnSurfaceVariant,
                lineHeight = 16.sp
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 跨端接入（0.9.0 批次四）：给手表 / 鸿蒙 NEXT / PC 用的本地 HTTP 接口。
        // 默认关；打开时自动生成 12 位 Token，界面上看得到、能复制、能重置。
        Text("跨端接入", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "在同一 WiFi 下给手表、鸿蒙 NEXT、PC 用一个极小的 HTTP 接口。" +
                "只有两个接口：POST /api/chat 说话，GET /api/status 看状态。",
            style = MaterialTheme.typography.bodySmall,
            color = LunaOnSurfaceVariant,
            lineHeight = 17.sp
        )
        Spacer(modifier = Modifier.height(10.dp))
        var crossEnabled by remember { mutableStateOf(LocalAgentServer.isEnabled(context)) }
        var crossToken by remember { mutableStateOf(LocalAgentServer.token(context)) }
        var crossResult by remember { mutableStateOf("") }
        SettingSwitchRow(
            title = "允许外部设备连接",
            desc = "打开后开始监听局域网端口，用 Token 才能调；关掉立即停止监听。",
            checked = crossEnabled,
            onCheckedChange = { on ->
                val ok = LocalAgentServer.setEnabled(context, on)
                crossEnabled = LocalAgentServer.isEnabled(context)
                crossToken = LocalAgentServer.token(context)
                crossResult = when {
                    !on -> "已停止监听"
                    ok -> "监听中：http://${LocalAgentServer.localIp()}:${LocalAgentServer.port(context)}"
                    else -> "端口 ${LocalAgentServer.port(context)} 被占用，换个端口再试"
                }
            }
        )
        if (crossEnabled) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "地址：http://${LocalAgentServer.localIp()}:${LocalAgentServer.port(context)}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
            Text(
                "Token：$crossToken",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row {
                LunaTextButton(onClick = {
                    val cm = context.getSystemService(
                        android.content.Context.CLIPBOARD_SERVICE
                    ) as? android.content.ClipboardManager
                    cm?.setPrimaryClip(
                        android.content.ClipData.newPlainText("宏月 Token", crossToken)
                    )
                    Toast.makeText(context, "Token 已复制", Toast.LENGTH_SHORT).show()
                }) { Text("复制 Token") }
                LunaTextButton(onClick = {
                    crossToken = LocalAgentServer.regenerateToken(context)
                    Toast.makeText(context, "已换新 Token，旧设备需重新填", Toast.LENGTH_LONG).show()
                }) { Text("重置 Token") }
            }
            Text(
                "调用示例：curl -H \"X-Auth-Token: $crossToken\" " +
                    "-d '{\"message\":\"在吗\"}' " +
                    "http://${LocalAgentServer.localIp()}:${LocalAgentServer.port(context)}/api/chat",
                style = MaterialTheme.typography.labelSmall,
                color = LunaOnSurfaceVariant,
                lineHeight = 16.sp
            )
        }
        if (crossResult.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                crossResult,
                style = MaterialTheme.typography.labelSmall,
                color = LunaOnSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 语音识别（ASR）配置
        Text("语音识别（ASR）", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LunaOutlinedButton(onClick = { showAsrDialog = true }) { Text("配置 ASR Key") }
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
            LunaTextButton(onClick = { onOpenOverlaySettings(); permissionText = onPermissionStatus() }) { Text("悬浮窗") }
            LunaTextButton(onClick = { onOpenAccessibilitySettings(); permissionText = onPermissionStatus() }) { Text("无障碍") }
        }
        Row {
            LunaTextButton(onClick = { onOpenBatteryOptimization(); permissionText = onPermissionStatus() }) { Text("电池优化") }
            LunaTextButton(onClick = onOpenAutoStart) { Text("自启动") }
            LunaTextButton(onClick = onOpenBackgroundPower) { Text("后台耗电") }
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

        // 自我学习（0.9.11）：默认关闭，开启前必须读完免责声明
        HabitLearningSection(context)

        Spacer(modifier = Modifier.height(24.dp))

        // 外挂记忆（0.9.13）：默认开启，它不额外采集用户信息，但要能关掉
        MemorySection(context)

        Spacer(modifier = Modifier.height(24.dp))

        // 情感（0.9.15）：PAD 双速 / 情感调制召回 / 会话日记，三个独立开关
        EmotionSection(context)

        Spacer(modifier = Modifier.height(24.dp))

        // 预设导入导出（0.9.24）：换手机 / 重装 / 键盘高度调好后带走
        PresetSection(context)

        Spacer(modifier = Modifier.height(24.dp))

        // QQ 自动回复（0.9.25 批次 E）：通知监听 + 静默回复
        QqSection(context)

        Spacer(modifier = Modifier.height(24.dp))

        // 日志导出
        Text("日志", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Row {
            LunaButton(onClick = { LogExporter.export(context) }) {
                Text("一键导出日志")
            }
            Spacer(modifier = Modifier.width(12.dp))
            // 0.7.12：视觉模型单独一份现场报告（模型目录 / 6 个文件 / 4 个 so / 堆栈），
            // logcat 里只有一行 Log.e，真机排障时根本看不出缺什么。
            LunaOutlinedButton(onClick = {
                if (!LogExporter.exportVisionInit(context)) {
                    Toast.makeText(context, "模型初始化日志生成失败", Toast.LENGTH_SHORT).show()
                }
            }) { Text("导出模型初始化日志") }
        }
        // 读屏自检（0.8.0-alpha2）：一口气查无障碍 → 截图 → 视觉模型，
        // 直接给结论。用户反馈「读不了屏」时不用装 adb 抓 logcat。
        var visionCheck by remember { mutableStateOf<String?>(null) }
        var checking by remember { mutableStateOf(false) }
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LunaOutlinedButton(
                onClick = {
                    if (!checking) {
                        checking = true
                        scope.launch {
                            val report = runCatching { VisionSelfCheck.run(context) }.getOrNull()
                            checking = false
                            visionCheck = report?.toText()
                                ?: "自检没跑起来（看日志 VisionSelfCheck）"
                        }
                    }
                },
                enabled = !checking
            ) { Text(if (checking) "自检中…" else "读屏自检") }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                "查无障碍 / 截图 / 视觉模型，一条结论说明为什么读不了屏",
                style = MaterialTheme.typography.labelSmall,
                color = LunaOnSurfaceVariant
            )
        }
        visionCheck?.let { text ->
            AlertDialog(
                onDismissRequest = { visionCheck = null },
                title = { Text("读屏自检") },
                text = {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                },
                confirmButton = {
                    TextButton(onClick = { visionCheck = null }) { Text("知道了") }
                }
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row {
            LunaOutlinedButton(onClick = {
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

        // 崩溃日志：全局 CrashHandler 落盘，闪退后靠这个拿现场
        var crashCount by remember { mutableStateOf(CrashHandler.count(context)) }
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LunaOutlinedButton(onClick = {
                val ok = CrashHandler.share(context)
                if (!ok) Toast.makeText(context, "暂无崩溃记录", Toast.LENGTH_SHORT).show()
            }) { Text("导出崩溃日志（$crashCount）") }
            Spacer(modifier = Modifier.width(12.dp))
            LunaTextButton(onClick = {
                val n = CrashHandler.clear(context)
                crashCount = 0
                Toast.makeText(
                    context,
                    if (n > 0) "已清空 $n 份崩溃记录" else "暂无崩溃记录",
                    Toast.LENGTH_SHORT
                ).show()
            }) { Text("清空") }
        }
        Text(
            if (crashCount > 0) "上次崩溃现场已保存，反馈闪退时请点上面按钮导出" else "暂无崩溃记录",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

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
        Row(verticalAlignment = Alignment.CenterVertically) {
            LunaOutlinedButton(onClick = onCheckUpdate) {
                Text("检查更新")
            }
        }

        // 实验版：以前这里只是一行不可点的「● 实验版 vX」，用户看到却装不了。
        // 现在整行就是按钮，点一下直接走下载安装。
        experimentalUpdate?.let { exp ->
            Spacer(modifier = Modifier.height(6.dp))
            LunaTextButton(onClick = { onInstallUpdate(exp) }) {
                Text(
                    "● 实验版 v${exp.version} · 点这里安装",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFFE53935)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        LunaOutlinedButton(onClick = onOpenTaskArchive) {
            Text("任务记录与审计")
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

    if (showVisionConfigDialog) {
        VisionConfigDialog(
            context = context,
            onDismiss = { showVisionConfigDialog = false },
            onSave = { model, apiKey, baseUrl ->
                if (model.isNotEmpty()) LlmClientFactory.saveVisionModel(context, model)
                LlmClientFactory.saveVisionApiKey(context, apiKey)
                if (baseUrl.isNotEmpty()) LlmClientFactory.saveVisionBaseUrl(context, baseUrl)
                visionKeyConfigured = LlmClientFactory.getVisionApiKey(context).isNotBlank()
                showVisionConfigDialog = false
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
                LunaTextButton(onClick = {
                    safetyEnabled = false
                    SafetyPolicy.setEnabled(context, false)
                    showSafetyConfirm = false
                }) { Text("确认关闭") }
            },
            dismissButton = {
                LunaTextButton(onClick = { showSafetyConfirm = false }) { Text("取消") }
            }
        )
    }
}

/**
 * 版本比较：remote 是否比 current 新。
 *
 * **只做转调**，实现留在 [UpdateChecker.isNewer]。
 *
 * 以前这里和 UpdateChecker 各写了一份：修了这份、漏了那份，
 * 结果「0.8.0-alpha2 比 0.8.0-alpha1 新」在一条链路上成立、在另一条上不成立，
 * 表现为装了 alpha1 死活收不到 alpha2。单实现后这种分叉不可能再发生。
 */
internal fun isNewerVersion(remote: String, current: String): Boolean =
    UpdateChecker.isNewer(remote, current)

/**
 * 模型中心（0.8.13-alpha7）：**选方案 → 产出 → 安装** 三步。
 *
 * 为什么改成三段：以前每个按钮都是「点一下直接往 models/ 里覆盖」，
 * 中途失败就留下半新半旧的混装包 —— 真机上「混装」就是这么来的，
 * 而且崩了完全看不出原因。现在下载只写暂存区，安装才动正式目录，
 * 旧文件先备份、失败自动回滚。
 *
 * 另外 alpha5 有个设计错误：把修复按钮放进 `if (!visionReady)` 里，
 * 结果模型文件齐全的用户（最需要修的那批）根本看不到按钮。这里不再犯。
 */
@Composable
private fun ModelDownloadSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var visionReady by remember { mutableStateOf(ModelManager.isVisionModelReady(context)) }
    var senseVoiceReady by remember { mutableStateOf(ModelManager.isSenseVoiceReady(context)) }
    var bundle by remember { mutableStateOf(ModelManager.detectVisionBundle(context)) }
    // 0.8.13-alpha8：embedding 到底内嵌还是要单独文件 —— 这是「装载成功但一推理就 abort」的真凶
    var embedding by remember { mutableStateOf(ModelManager.embeddingMode(context)) }
    var busy by remember { mutableStateOf(ModelManager.downloading || ModelManager.importing) }
    var progress by remember { mutableStateOf(ModelManager.lastStage) }
    var fraction by remember { mutableStateOf(-1f) }
    var showLinks by remember { mutableStateOf(false) }

    // 默认替用户选好当前最该做的那一步
    var plan by remember {
        mutableStateOf(
            when {
                // 缺 embedding 文件：别的都做了也白搭，一推理就崩
                ModelManager.embeddingMode(context) == ModelManager.EmbeddingMode.SEPARATE_MISSING ->
                    ModelManager.ModelPlan.MODELSCOPE_EMBEDDING
                // 已经有一套完整文件 → 最小改动
                ModelManager.isVisionModelReady(context) -> ModelManager.ModelPlan.VISUAL_ONLY
                // 什么都没有 → 整包
                else -> ModelManager.ModelPlan.MODELSCOPE_FULL
            }
        )
    }
    var staged by remember { mutableStateOf(ModelManager.stagedState(context, plan)) }
    var backup by remember { mutableStateOf(ModelManager.lastBackup(context)) }

    fun refresh() {
        visionReady = ModelManager.isVisionModelReady(context)
        senseVoiceReady = ModelManager.isSenseVoiceReady(context)
        bundle = ModelManager.detectVisionBundle(context)
        embedding = ModelManager.embeddingMode(context)
        staged = ModelManager.stagedState(context, plan)
        backup = ModelManager.lastBackup(context)
    }

    // 切方案要重算暂存状态，否则会显示上一个方案的结果
    LaunchedEffect(plan) { staged = ModelManager.stagedState(context, plan) }

    val importZipLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: android.net.Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            fraction = -1f
            progress = "准备导入…"
            val ok = ModelManager.importZip(context, uri) { p, stage ->
                fraction = p
                progress = stage
            }
            busy = false
            refresh()
            fraction = if (ok) 1f else -1f
            if (ok) progress = "● 导入完成，本地模型已就绪"
            Toast.makeText(context, if (ok) "模型导入完成" else progress, Toast.LENGTH_SHORT).show()
        }
    }

    val importFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: android.net.Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            fraction = -1f
            progress = "准备导入…"
            val ok = ModelManager.importFolder(context, uri) { p, stage ->
                fraction = p
                progress = stage
            }
            busy = false
            refresh()
            fraction = if (ok) 1f else -1f
            if (ok) progress = "● 导入完成，本地模型已就绪"
            Toast.makeText(context, if (ok) "模型导入完成" else progress, Toast.LENGTH_SHORT).show()
        }
    }

    Column {
        // ---------------- 现状 ----------------
        Text(
            "视觉模型：" + when {
                !visionReady -> "○ 未下载"
                bundle == ModelManager.VisionBundle.MODELSCOPE -> "● 已就绪（魔搭官方包）"
                bundle == ModelManager.VisionBundle.LUNAR -> "● 文件齐全（LunaMacro 包，版本不配套）"
                bundle == ModelManager.VisionBundle.MIXED -> "● 文件齐全（两套混装，会崩）"
                else -> "● 文件齐全（来源未知）"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "语音模型：" + (if (senseVoiceReady) "● 已就绪" else "○ 未下载"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            "模型来源：" + bundle.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            bundle.advice,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
        // 0.8.13-alpha8：embedding 状态单独报一行。
        // 「缺 embeddings_bf16.bin」是这个 BUG 里最阴的一个 —— 装载成功、日志干净，
        // 只有真正推理时 fseeko(null) 才 abort，不看这一行根本想不到。
        Text(
            "embedding：" + embedding.label,
            style = MaterialTheme.typography.labelSmall,
            color = when (embedding) {
                ModelManager.EmbeddingMode.SEPARATE_MISSING -> LunaErrFg
                ModelManager.EmbeddingMode.UNKNOWN -> LunaWarnFg
                else -> LunaOkFg
            },
            modifier = Modifier.padding(top = 4.dp)
        )

        // ---------------- 进度 ----------------
        if (busy) {
            Spacer(modifier = Modifier.height(10.dp))
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
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "下载/导入期间请保持应用在前台",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // ---------------- ① 选方案 ----------------
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "① 选择一个方案",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(6.dp))
        ModelManager.ModelPlan.entries.forEach { p ->
            PlanOptionRow(
                plan = p,
                selected = p == plan,
                enabled = !busy,
                onClick = { plan = p }
            )
            Spacer(modifier = Modifier.height(6.dp))
        }

        // ---------------- ② 产出 ----------------
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            "② 产出（下载到暂存区，不动正式目录）",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(4.dp))
        val stagedReady = staged is ModelManager.Staged.Ready
        Text(
            when (val s = staged) {
                is ModelManager.Staged.Ready ->
                    "✓ 已产出 " + s.fileCount + " 个文件 / " + (s.bytes / 1024 / 1024) + "MB，可以安装了"
                is ModelManager.Staged.Empty ->
                    if (s.partialBytes > 0) {
                        "下了一半（" + (s.partialBytes / 1024 / 1024) + "MB），点下面的按钮可续传"
                    } else {
                        "还没下载"
                    }
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (stagedReady) LunaOkFg else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        LunaOutlinedButton(
            onClick = {
                scope.launch {
                    busy = true
                    fraction = -1f
                    val ok = ModelManager.producePlan(context, plan) { p, s ->
                        fraction = p
                        progress = s
                    }
                    busy = false
                    refresh()
                    if (!ok) fraction = -1f
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (stagedReady) "重新下载（" + plan.title + "）"
                else "下载到暂存区（" + plan.title + "）"
            )
        }

        // ---------------- ③ 安装 ----------------
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "③ 安装（覆盖正式目录，旧文件先备份）",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "会替换 " + (plan.webFiles?.size ?: 7) + " 个文件；先备份旧的，装失败自动回滚，" +
                "不会再出现半新半旧的混装包。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        LunaButton(
            onClick = {
                scope.launch {
                    busy = true
                    fraction = -1f
                    val r = ModelManager.installStaged(context, plan) { p, s ->
                        fraction = p
                        progress = s
                    }
                    busy = false
                    refresh()
                    when (r) {
                        is ModelManager.InstallResult.Ok -> {
                            fraction = 1f
                            progress = "● 安装完成（" + r.replaced + " 个文件），去点「测试视觉模型」"
                            Toast.makeText(context, "模型已安装", Toast.LENGTH_SHORT).show()
                        }
                        is ModelManager.InstallResult.Failed -> {
                            fraction = -1f
                            progress = "安装失败：" + r.reason
                        }
                    }
                }
            },
            enabled = !busy && stagedReady,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("安装并生效")
        }
        backup?.let { name ->
            Spacer(modifier = Modifier.height(2.dp))
            LunaTextButton(
                onClick = {
                    scope.launch {
                        busy = true
                        val ok = ModelManager.rollbackLastInstall(context)
                        busy = false
                        refresh()
                        fraction = -1f
                        progress = if (ok) "● 已回滚到备份 " + name else "没有可回滚的备份"
                        Toast.makeText(
                            context,
                            if (ok) "已回滚到 " + name else "没有备份",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                enabled = !busy
            ) {
                Text("回滚到上一次备份（" + name + "）")
            }
        }

        // ---------------- 语音模型 / 手动导入 ----------------
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "语音模型 / 手动导入",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(6.dp))
        if (!busy) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LunaOutlinedButton(
                    onClick = { importZipLauncher.launch(arrayOf("*/*")) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("导入 zip")
                }
                LunaOutlinedButton(
                    onClick = { importFolderLauncher.launch(null) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("导入文件夹")
                }
            }
            if (!senseVoiceReady) {
                Spacer(modifier = Modifier.height(6.dp))
                LunaTextButton(
                    onClick = {
                        scope.launch {
                            busy = true
                            fraction = -1f
                            progress = "连接下载源…"
                            val ok = ModelManager.ensureModels(context) { p, stage ->
                                fraction = p
                                progress = stage
                            }
                            busy = false
                            refresh()
                            fraction = if (ok) 1f else -1f
                            progress = if (ok) "● 语音模型已就绪" else progress
                        }
                    },
                    enabled = !busy
                ) {
                    Text("下载语音模型（SenseVoice，约 229MB）")
                }
            }
        }
        if (progress.isNotEmpty() && !busy) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                progress,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        LunaTextButton(onClick = { showLinks = !showLinks }) {
            Text(if (showLinks) "收起手动下载链接" else "手动下载链接（外部下载器更快）")
        }
        if (showLinks) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "用 IDM / 迅雷 / aria2 / 浏览器下好 zip，回到本页点「导入 zip」即可，不用连 App 的网络。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            ManualLinkGroup(
                title = "视觉模型 models.zip（约 1.3GB）",
                zipName = ModelManager.VISION_ZIP_NAME
            )
            Spacer(modifier = Modifier.height(6.dp))
            ManualLinkGroup(
                title = "语音模型 sensevoice.zip（约 229MB）",
                zipName = ModelManager.SENSEVOICE_ZIP_NAME
            )
        }
    }
}

/**
 * 一个方案选项（单选卡片）。
 *
 * 不用 border：项目基调是无硬边框，选中靠底色 + 左侧实心圆点区分。
 */
@Composable
private fun PlanOptionRow(
    plan: ModelManager.ModelPlan,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    val bg = if (selected) LunaSurfaceAlt else LunaSurface
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(enabled = enabled) { onClick() },
        shape = shape,
        color = bg
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text(
                if (selected) "●" else "○",
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) LunaPrimary else LunaOnSurfaceVariant
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        plan.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        // 用十进制换算：魔搭页面上写的是 376MB，这里跟着显示 377MB，
                        // 按 1024 算会变成 359MB，用户会以为下的不是同一个东西
                        if (plan.bytes >= 1_000_000_000L) {
                            "%.2fGB".format(plan.bytes / 1_000_000_000.0)
                        } else {
                            (plan.bytes / 1_000_000L).toString() + "MB"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = LunaOnSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    plan.summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = LunaOnSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ManualLinkGroup(title: String, zipName: String) {
    val context = LocalContext.current
    Column {
        Text(
            title,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        ModelManager.manualUrls(zipName).forEachIndexed { i, url ->
            val label = if (i == 0) "推荐" else if (i == ModelManager.manualUrls(zipName).lastIndex) "官方" else "备用"
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "[$label] $url",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                LunaTextButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("url", url))
                    Toast.makeText(context, "已复制链接", Toast.LENGTH_SHORT).show()
                }) { Text("复制") }
                LunaTextButton(onClick = {
                    try {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                        )
                    } catch (e: Throwable) {
                        Toast.makeText(context, "没有可用的浏览器", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("打开") }
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
    val providers = listOf("deepseek", "openai", "qwen", "qwen-vl", "ollama")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("配置 LLM") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LunaOutlinedButton(onClick = { providerMenu = true }) {
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
                // 本地 AI 服务的填写指引（0.7.12）：HTTP 已被 network_security_config 放行，
                // 但地址/端口/API Key 三处任何一处填错，用户看到的都只是「连不上」，很难自查。
                Text(
                    text = "若使用本地 Ollama，请填写 http://127.0.0.1:11434/v1，" +
                        "并确保 PC 端已设置 OLLAMA_HOST=0.0.0.0。\n" +
                        "手机与电脑不在同一台机器时，127.0.0.1 要换成电脑的局域网 IP" +
                        "（本机实测 http://192.168.31.46:11434/v1）；API Key 随便填，本地服务不校验。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
            LunaTextButton(onClick = { onSave(provider, apiKey.trim(), baseUrl.trim(), model.trim()) }) {
                Text("保存")
            }
        },
        dismissButton = {
            LunaTextButton(onClick = onDismiss) {
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
            LunaTextButton(onClick = { onSave(apiKey.trim(), model.trim()) }) {
                Text("保存")
            }
        },
        dismissButton = {
            LunaTextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

/**
 * 0.9.7 批次二：独立的云端视觉配置对话框。
 *
 * 三个输入：模型下拉（qwen-vl-max / qwen-vl-plus / gpt-4o）+ 视觉专用 Key + Base URL。
 * Key 留空 = 不配云端视觉，发图时回退本地视觉（或复用主模型视觉）。
 */
@Composable
fun VisionConfigDialog(
    context: Context,
    onDismiss: () -> Unit,
    onSave: (model: String, apiKey: String, baseUrl: String) -> Unit
) {
    var model by remember { mutableStateOf(LlmClientFactory.getVisionModel(context)) }
    var apiKey by remember { mutableStateOf(LlmClientFactory.getVisionApiKey(context)) }
    var baseUrl by remember { mutableStateOf(LlmClientFactory.getVisionBaseUrl(context)) }
    var modelMenu by remember { mutableStateOf(false) }

    val models = listOf("qwen-vl-max", "qwen-vl-plus", "gpt-4o")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("配置云端视觉模型") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box {
                    LunaOutlinedButton(onClick = { modelMenu = true }) {
                        Text("模型：$model")
                    }
                    DropdownMenu(
                        expanded = modelMenu,
                        onDismissRequest = { modelMenu = false }
                    ) {
                        models.forEach { m ->
                            DropdownMenuItem(
                                text = { Text(m) },
                                onClick = {
                                    model = m
                                    // 切换模型时 baseUrl 自动回填对应 provider 的默认地址
                                    baseUrl = when (m) {
                                        "gpt-4o" -> "https://api.openai.com/v1"
                                        else -> "https://dashscope.aliyuncs.com/compatible-mode/v1"
                                    }
                                    modelMenu = false
                                }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { v -> apiKey = v },
                    label = { Text("视觉 API Key（留空则回退本地视觉）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "qwen-vl-max / qwen-vl-plus 用通义千问（DashScope）的 Key；" +
                        "gpt-4o 用 OpenAI 的 Key。留空则发图时只走本地视觉模型。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { v -> baseUrl = v },
                    label = { Text("视觉 Base URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            LunaTextButton(onClick = { onSave(model.trim(), apiKey.trim(), baseUrl.trim()) }) {
                Text("保存")
            }
        },
        dismissButton = {
            LunaTextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

/** 设置页的开关行（0.7.11 从 CopilotTab 抽过来，样式保持一致） */
@Composable
private fun SettingSwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = LunaOnBackground)
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = LunaOnSurfaceVariant,
                lineHeight = 17.sp
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = LunaSurface,
                checkedTrackColor = LunaPrimary,
                uncheckedThumbColor = LunaSurface,
                uncheckedTrackColor = LunaOutline,
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}

// ---- 自我学习（0.9.11）----
//
// 这一块的交互原则：**默认关着，且关着的时候没有任何后台动作**。
// 用户点开开关只是「表达意愿」，真正的开启发生在读完免责声明点同意之后 ——
// 中间任何一步退出，都不算数。

private data class HabitUiSnapshot(
    val today: Int,
    val days: Int,
    val sizeKb: Long,
    val brief: String
)

@Composable
private fun HabitLearningSection(context: Context) {
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(CopilotConfig.isHabitLearningEnabled(context)) }
    var showConsent by remember { mutableStateOf(false) }
    var showTerms by remember { mutableStateOf(false) }
    var showClear by remember { mutableStateOf(false) }
    var showLearned by remember { mutableStateOf(false) }
    var todayCount by remember { mutableStateOf(0) }
    var dayCount by remember { mutableStateOf(0) }
    var sizeKb by remember { mutableStateOf(0L) }
    var brief by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val refresh: () -> Unit = {
        scope.launch {
            val snap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                HabitUiSnapshot(
                    today = HabitStore.todayCount(context),
                    days = HabitStore.dayCount(context),
                    sizeKb = HabitStore.sizeBytes(context) / 1024,
                    brief = HabitStore.loadBrief(context).orEmpty()
                )
            }
            todayCount = snap.today
            dayCount = snap.days
            sizeKb = snap.sizeKb
            brief = snap.brief
        }
    }
    LaunchedEffect(Unit) { refresh() }

    Text("自我学习", style = MaterialTheme.typography.titleMedium)
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        "让她自己留意你怎么用手机（打开过哪些应用、点了哪个按钮、大概几点），" +
            "慢慢把你的习惯记下来；同一条操作她走熟之后，会归纳成「她会做的动作」。" +
            "默认关闭；密码、验证码、输入内容、支付与金融类界面一律不采，学会的动作也要你逐条收下才算数。",
        style = MaterialTheme.typography.bodySmall,
        color = LunaOnSurfaceVariant,
        lineHeight = 17.sp
    )
    Spacer(modifier = Modifier.height(10.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(
            checked = enabled,
            onCheckedChange = { checked ->
                if (checked) {
                    // **每次**从关到开都要停 20 秒重新读一遍，不给「上次同意过」的口子。
                    // 理由写在免责声明第三节：这个开关意味着「她可以动手」，
                    // 一次性的勾选撑不住这个后果。
                    showConsent = true
                } else {
                    CopilotConfig.setHabitLearningEnabled(context, false)
                    enabled = false
                    // 关掉就立刻从提示词里撤掉 —— 不能她还在念叨你已经关掉的东西
                    HabitMemory.clear()
                }
            }
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            if (enabled) "正在学习" else "已关闭",
            style = MaterialTheme.typography.bodySmall,
            color = if (enabled) LunaOkFg else LunaOnSurfaceVariant
        )
    }

    if (enabled) {
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            "已采集 $dayCount 天 · 今天 $todayCount 条 · 占用 ${sizeKb} KB（原始记录最多留 7 天）",
            style = MaterialTheme.typography.labelSmall,
            color = LunaOnSurfaceVariant
        )
    }

    // 0.9.12：她学到的「能做的事」。默认什么都不会做，必须逐条收下。
    if (enabled) {
        HabitSkillSection(context)
    }

    Spacer(modifier = Modifier.height(8.dp))
    Row {
        LunaTextButton(onClick = {
            if (brief.isBlank()) {
                Toast.makeText(context, "她还没学到东西，先让她多看几天，或者点「现在学一下」", Toast.LENGTH_SHORT).show()
            } else {
                showLearned = true
            }
        }) { Text("看她学到了什么") }
        LunaTextButton(onClick = {
            if (!busy) {
                busy = true
                scope.launch {
                    val p = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        HabitCollector.learnNow(context)
                    }
                    CopilotConfig.setHabitLastLearnedAt(context, System.currentTimeMillis())
                    HabitMemory.reload(context)
                    busy = false
                    refresh()
                    Toast.makeText(
                        context,
                        if (p.isEmpty) "记录还太少，先让她多看几天" else "学完了，去跟她聊聊看",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }) { Text(if (busy) "正在学…" else "现在学一下") }
    }
    Row {
        LunaTextButton(onClick = { showTerms = true }) { Text("查看免责声明") }
        LunaTextButton(onClick = {
            if (dayCount == 0 && brief.isBlank()) {
                Toast.makeText(context, "本来就没有学习数据", Toast.LENGTH_SHORT).show()
            } else {
                showClear = true
            }
        }) { Text("清除学习数据") }
    }

    if (showConsent) {
        HabitConsentDialog(
            onDismiss = { showConsent = false },
            onAgree = {
                CopilotConfig.setHabitTermsAccepted(context, true)
                CopilotConfig.setHabitLearningEnabled(context, true)
                HabitMemory.reload(context)
                enabled = true
                showConsent = false
                refresh()
                Toast.makeText(context, "已开启，她会慢慢开始留意", Toast.LENGTH_SHORT).show()
            }
        )
    }
    if (showTerms) {
        HabitTermsDialog(onDismiss = { showTerms = false })
    }
    if (showLearned) {
        AlertDialog(
            onDismissRequest = { showLearned = false },
            title = { Text("她学到的") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(brief, style = MaterialTheme.typography.bodySmall, lineHeight = 20.sp)
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        "这些是纯本地的统计结论，你可以随时清除。",
                        style = MaterialTheme.typography.labelSmall,
                        color = LunaOnSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showLearned = false }) { Text("知道了") }
            }
        )
    }
    if (showClear) {
        AlertDialog(
            onDismissRequest = { showClear = false },
            title = { Text("清除学习数据？") },
            text = {
                Text(
                    "已经采集的操作记录和她总结出来的习惯会一起删掉，无法恢复。" +
                        "开关状态不变，之后会从头开始重新学。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showClear = false
                    scope.launch {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            HabitCollector.clearAll(context)
                        }
                        HabitMemory.clear()
                        refresh()
                        Toast.makeText(context, "已清除，什么都没有留下", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("确认清除", color = LunaErrFg) }
            },
            dismissButton = {
                TextButton(onClick = { showClear = false }) { Text("取消") }
            }
        )
    }
}

/**
 * 「她学会的动作」（0.9.12）。
 *
 * 这一块的交互原则只有一条：**默认什么都不做**。
 * 候选状态只能被用户点「收下」改变；执行前还要再确认一次。
 * 任何「她学了就自动帮你做」的设计都不在这里 —— 那是另一件事，得单独征求同意。
 */
@Composable
private fun HabitSkillSection(context: Context) {
    val scope = rememberCoroutineScope()
    var all by remember { mutableStateOf<List<HabitSkill>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf<HabitSkill?>(null) }
    var runningId by remember { mutableStateOf<String?>(null) }
    var resultText by remember { mutableStateOf<String?>(null) }

    fun reload() {
        scope.launch {
            all = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                HabitSkillStore.load(context)
            }
            loaded = true
        }
    }

    fun setState(skill: HabitSkill, state: String) {
        scope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                HabitSkillStore.setState(context, skill.id, state)
                HabitMemory.reloadSkills(context)
            }
            reload()
        }
    }

    LaunchedEffect(Unit) { reload() }

    val candidates = all.filter { it.isCandidate }
    val actives = all.filter { it.isActive }

    Spacer(modifier = Modifier.height(16.dp))
    Text("她学会的动作", style = MaterialTheme.typography.titleMedium)
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        "她把你反复走的同一条路径记了下来。现在这些还只是候选——她一个都不会做。" +
            "你点「收下」之后她才算会，而且每次动手前都会先问过你。",
        style = MaterialTheme.typography.bodySmall,
        color = LunaOnSurfaceVariant,
        lineHeight = 17.sp
    )
    Spacer(modifier = Modifier.height(8.dp))

    if (loaded && candidates.isEmpty() && actives.isEmpty()) {
        Text(
            "还没学到。同一条操作重复 3 次以上、并且跨 2 天才算数——一次两次只是随手点。",
            style = MaterialTheme.typography.labelSmall,
            color = LunaOnSurfaceVariant,
            lineHeight = 16.sp
        )
    }

    if (candidates.isNotEmpty()) {
        Text(
            "候选（${candidates.size}）",
            style = MaterialTheme.typography.labelMedium,
            color = LunaOnSurfaceVariant
        )
        Spacer(modifier = Modifier.height(6.dp))
        candidates.forEach { sk ->
            SkillRow(
                skill = sk,
                running = runningId == sk.id,
                primaryLabel = "收下",
                onPrimary = { setState(sk, HabitSkill.STATE_ACTIVE) },
                secondaryLabel = "不要",
                onSecondary = { setState(sk, HabitSkill.STATE_REJECTED) }
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    if (actives.isNotEmpty()) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "已收下（${actives.size}）",
            style = MaterialTheme.typography.labelMedium,
            color = LunaOkFg
        )
        Spacer(modifier = Modifier.height(6.dp))
        actives.forEach { sk ->
            SkillRow(
                skill = sk,
                running = runningId == sk.id,
                primaryLabel = if (runningId == sk.id) "正在做…" else "让她做",
                onPrimary = {
                    if (NexusAccessibilityService.getInstance() == null) {
                        Toast.makeText(
                            context,
                            "得先打开无障碍服务，她才点得动",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        confirming = sk
                    }
                },
                secondaryLabel = "忘记",
                onSecondary = { setState(sk, HabitSkill.STATE_REJECTED) }
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    if (loaded && all.any { it.isRejected }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "已忽略 ${all.count { it.isRejected }} 条",
                style = MaterialTheme.typography.labelSmall,
                color = LunaOnSurfaceVariant
            )
            LunaTextButton(onClick = {
                scope.launch {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        HabitSkillStore.save(
                            context,
                            HabitSkillStore.load(context).filterNot { it.isRejected }
                        )
                    }
                    reload()
                }
            }) { Text("清掉这些记录") }
        }
    }

    // ---- 执行确认 ----
    val pending = confirming
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { if (runningId == null) confirming = null },
            title = { Text("让我做这件事？") },
            text = {
                Column {
                    Text(pending.describe(), style = MaterialTheme.typography.bodyMedium, lineHeight = 21.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "我会按文字找按钮，找不到就停下，不会乱点。你现在可以看着屏幕，随时让我停。",
                        style = MaterialTheme.typography.bodySmall,
                        color = LunaOnSurfaceVariant,
                        lineHeight = 18.sp
                    )
                    if (pending.risk == HabitTrace.RISK_CAUTION) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "⚠ 这条里有点下去就会改动内容的步骤，请确认后再说。",
                            style = MaterialTheme.typography.bodySmall,
                            color = LunaErrFg,
                            lineHeight = 18.sp
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = runningId == null,
                    onClick = {
                        val sk = pending
                        confirming = null
                        runningId = sk.id
                        scope.launch {
                            val r = HabitSkillBridge.runIfActive(context, sk)
                            runningId = null
                            resultText = if (r == null) {
                                "她被拦下了：开关已关闭，或者这个动作还没被收下。"
                            } else {
                                HabitSkillBridge.resultText(sk, r)
                            }
                            reload()
                        }
                    }
                ) { Text("去做") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = null }) { Text("算了") }
            }
        )
    }

    val res = resultText
    if (res != null) {
        AlertDialog(
            onDismissRequest = { resultText = null },
            title = { Text("做完了") },
            text = { Text(res, lineHeight = 21.sp) },
            confirmButton = {
                TextButton(onClick = { resultText = null }) { Text("知道了") }
            }
        )
    }
}

@Composable
private fun SkillRow(
    skill: HabitSkill,
    running: Boolean,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String,
    onSecondary: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(LunaSurfaceAlt)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(skill.name, style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            "重复 ${skill.occurrences} 次 · ${skill.days} 天 · ${skill.steps.size} 步",
            style = MaterialTheme.typography.labelSmall,
            color = LunaOnSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            skill.describe(),
            style = MaterialTheme.typography.labelSmall,
            color = LunaOnSurfaceVariant,
            lineHeight = 16.sp
        )
        if (skill.risk == HabitTrace.RISK_CAUTION) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "⚠ 会改动内容（发送 / 删除 / 关注之类），做之前一定问过你",
                style = MaterialTheme.typography.labelSmall,
                color = LunaErrFg
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Row {
            LunaTextButton(onClick = onPrimary, enabled = !running) { Text(primaryLabel) }
            LunaTextButton(onClick = onSecondary, enabled = !running) { Text(secondaryLabel) }
        }
    }
}

/**
 * 「情感」（0.9.15）。
 *
 * 情绪从「三个标签」升级成一条连续曲线之后，最大的风险是**用户看不见它在动**：
 * 她说话变温柔了，用户分不清是心情变了还是换了套话术。所以这一块除了三个开关，
 * 还挂了一行每 2 秒刷新的实时读数。
 *
 * 三个开关彼此独立，任何一个关掉都能精确回到 0.9.14 的对应行为 ——
 * 这是这一版所有新能力的统一退路。
 */
// ---- 情感（0.9.15）----
//
// 三个开关对应三块互相独立的能力，任何一个都能单独关掉回到 0.9.14：
//   1. PAD 双速      → 关掉 = 只按「被夸/被凶/普通」写心情标签，不再有连续坐标
//   2. 情感调制召回  → 关掉 = 记忆还是 `top(10)`，谁的心情都看同一份
//   3. 会话日记      → 关掉 = 夜里不写，开机不念
//
// 这一块最要紧的**不是开关本身，而是那个实时数值**：PAD 看不见摸不着，
// 没有一行「此刻/底色」，用户根本无法判断她是不是真的在变、还是只是换了套话术。
@Composable
private fun EmotionSection(context: Context) {
    var pad by remember { mutableStateOf(CopilotConfig.isPadEnabled(context)) }
    var recall by remember { mutableStateOf(CopilotConfig.isAffectRecallEnabled(context)) }
    var diary by remember { mutableStateOf(CopilotConfig.isDiaryEnabled(context)) }
    var line by remember { mutableStateOf("") }

    fun refreshLine() {
        line = runCatching { com.nexus.agent.persona.PadRuntime.displayLine() }.getOrDefault("")
    }

    // 每 2 秒跟着动一下：设置页开着的时候，用户多半正在聊天窗口和这里来回切，
    // 想看的恰恰是「我刚才那句话有没有让她变」。轮询比事件总线简单得多，代价可以忽略
    // （只在设置页可见时跑，离开就取消）。
    LaunchedEffect(pad) {
        refreshLine()
        if (!pad) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(2000)
            refreshLine()
        }
    }

    Text("情感", style = MaterialTheme.typography.titleMedium)
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        "她的情绪不是「开心/难过」两个标签，而是一个连续的三维坐标（愉悦、唤醒、支配）。" +
            "快变量每轮对话剧烈波动，20 分钟衰减一半；底色是长期基调，只挪一点点。" +
            "情绪稳定的记忆老得更慢，检索时也会偏向和她此刻心情相近的事。",
        style = MaterialTheme.typography.bodySmall,
        color = LunaOnSurfaceVariant,
        lineHeight = 17.sp
    )
    Spacer(modifier = Modifier.height(10.dp))

    SettingSwitchRow(
        title = "连续情感（PAD）",
        desc = if (pad) {
            "开启中：她会有「强颜欢笑」这种表里不一，也会自己慢慢消气"
        } else {
            "已关闭：只按被夸/被凶/普通写心情标签，行为回到 0.9.14"
        },
        checked = pad,
        onCheckedChange = { checked ->
            CopilotConfig.setPadEnabled(context, checked)
            pad = checked
            refreshLine()
        }
    )
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        if (line.isNotBlank()) "${line}（每 2 秒刷新）" else "此刻：—（还没说过话，或已关闭）",
        style = MaterialTheme.typography.labelMedium,
        color = LunaPrimary,
        lineHeight = 18.sp
    )

    Spacer(modifier = Modifier.height(12.dp))
    SettingSwitchRow(
        title = "情绪调制的记忆召回",
        desc = if (recall) {
            "开启中：开心时更容易想起开心的事；只重排，不会凭空翻出不相干的事"
        } else {
            "已关闭：按权重取前 10 条，与心情无关（0.9.14 的老行为）"
        },
        checked = recall,
        onCheckedChange = { checked ->
            CopilotConfig.setAffectRecallEnabled(context, checked)
            recall = checked
        }
    )

    Spacer(modifier = Modifier.height(12.dp))
    SettingSwitchRow(
        title = "会话日记",
        desc = if (diary) {
            "开启中：每天凌晨写一句当天的心情，第二天开机只念一次"
        } else {
            "已关闭：不写也不读，开机照常"
        },
        checked = diary,
        onCheckedChange = { checked ->
            CopilotConfig.setDiaryEnabled(context, checked)
            diary = checked
            // 打开时立刻补一次：不必等到下一个凌晨才看到第一篇
            if (checked) {
                com.nexus.agent.memory.MemoryNightShift.maybeWriteDiaryOnLaunch(context)
            }
        }
    )

    Spacer(modifier = Modifier.height(8.dp))
    Text(
        "排障：adb logcat -s PadDebug，搜「召回」看每次挑了哪几条、" +
            "搜「日记」看她昨晚写了什么。",
        style = MaterialTheme.typography.labelSmall,
        color = LunaOnSurfaceVariant,
        lineHeight = 16.sp
    )
}

/**
 * 「外挂记忆」（0.9.13）。
 *
 * 她跑成功的任务会被压成可回放的动作序列，下次同样的话、同样的界面就直接照着点，
 * 一次模型都不调。失败的任务会被提炼成一条避坑经验。
 *
 * 和上面的「自我学习」不是一回事：
 *  - 自我学习记的是**你**怎么用手机（所以要免责声明、要 20 秒）；
 *  - 外挂记忆记的是**她自己**怎么把事办成的（所以要的是能关掉、能看到数字）。
 *
 * 默认开启。关掉 = 不再采集、不再拦截，已有记录保留 —— 不偷偷删用户数据。
 */
@Composable
private fun MemorySection(context: Context) {
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(CopilotConfig.isMemoryEnabled(context)) }
    var auto by remember { mutableStateOf(CopilotConfig.isAutoConsolidateEnabled(context)) }
    var activeCount by remember { mutableStateOf(0) }
    var totalCount by remember { mutableStateOf(0) }
    var tipCount by remember { mutableStateOf(0) }
    var pendingCount by remember { mutableStateOf(0) }
    var poolCount by remember { mutableStateOf(0) }
    var lastAt by remember { mutableStateOf(CopilotConfig.memoryLastConsolidateAt(context)) }
    var lastReport by remember { mutableStateOf(CopilotConfig.memoryLastReport(context)) }
    var running by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            val counts = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val db = com.nexus.agent.data.db.AppDatabase.get(context)
                    intArrayOf(
                        db.actionMemoryDao().countOf(com.nexus.agent.data.db.ActionMemory.STATE_ACTIVE),
                        db.actionMemoryDao().count(),
                        db.intentTipDao().count(),
                        db.rawTraceDao().pendingCount(),
                        db.rawTraceDao().count()
                    )
                }.getOrNull()
            }
            counts?.let {
                activeCount = it[0]
                totalCount = it[1]
                tipCount = it[2]
                pendingCount = it[3]
                poolCount = it[4]
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Text("外挂记忆", style = MaterialTheme.typography.titleMedium)
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        "白天她只记账：每次任务结束记一行「界面 + 你要的事 + 点了什么 + 成没成」，不分析、不调模型。" +
            "每天凌晨一次性整理：同一件事成功三次以上才压成一套能重放的动作，" +
            "同一处反复失败才写成一条避坑经验。",
        style = MaterialTheme.typography.bodySmall,
        color = LunaOnSurfaceVariant,
        lineHeight = 17.sp
    )
    Spacer(modifier = Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text("启用外挂记忆", style = MaterialTheme.typography.bodyMedium)
            Text(
                if (enabled) "开启中：会记录成功/失败轨迹，并在下次复用它" else "已关闭：不采集、不拦截",
                style = MaterialTheme.typography.labelSmall,
                color = LunaOnSurfaceVariant
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = { checked ->
                CopilotConfig.setMemoryEnabled(context, checked)
                enabled = checked
                // 开关一变就重排夜间任务：关掉就该把周期任务一起取消
                com.nexus.agent.memory.MemoryNightShift.schedule(context)
                if (checked) refresh()
            },
            colors = SwitchDefaults.colors(checkedTrackColor = LunaPrimary)
        )
    }
    if (enabled) {
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("夜间自动整理", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (auto) "每天凌晨 3 点后，电量不低时跑一次（攒够 3 条才跑）" else "已关闭：只在你自己点「现在整理」时跑",
                    style = MaterialTheme.typography.labelSmall,
                    color = LunaOnSurfaceVariant
                )
            }
            Switch(
                checked = auto,
                onCheckedChange = { checked ->
                    CopilotConfig.setAutoConsolidateEnabled(context, checked)
                    auto = checked
                    com.nexus.agent.memory.MemoryNightShift.schedule(context)
                },
                colors = SwitchDefaults.colors(checkedTrackColor = LunaPrimary)
            )
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        "可回放 $activeCount 条 · 学到的动作共 $totalCount 条 · 避坑经验 $tipCount 条\n" +
            "待整理 $pendingCount 条 · 轨迹池共 $poolCount 条",
        style = MaterialTheme.typography.labelSmall,
        color = LunaOnSurfaceVariant,
        lineHeight = 16.sp
    )
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        "上次整理：${memoryAgo(lastAt)}" + if (lastReport.isNotBlank()) "\n$lastReport" else "",
        style = MaterialTheme.typography.labelSmall,
        color = LunaOnSurfaceVariant,
        lineHeight = 16.sp
    )
    Spacer(modifier = Modifier.height(8.dp))
    LunaButton(
        onClick = {
            if (running) return@LunaButton
            scope.launch {
                running = true
                val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { com.nexus.agent.memory.MemoryConsolidator.consolidate(context) }
                        .getOrElse {
                            com.nexus.agent.memory.MemoryConsolidator.Report.skipped(
                                "出错：${it.javaClass.simpleName}"
                            )
                        }
                }
                running = false
                lastAt = CopilotConfig.memoryLastConsolidateAt(context)
                lastReport = r.summary()
                refresh()
            }
        },
        enabled = enabled && !running
    ) {
        Text(if (running) "整理中…" else "现在整理一次")
    }
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        "连续 30 天没被用到会自动过期（不删除），权重也会随天数往下掉；连续 2 次回放失败立刻停用。" +
            "支付、转账、密码、验证码这类界面和动作一律不记。整理过 7 天的原始轨迹会被删掉，只留下学到的东西。",
        style = MaterialTheme.typography.labelSmall,
        color = LunaOnSurfaceVariant,
        lineHeight = 16.sp
    )
}

/** 「多久以前」的人话版本。设置页只需要这个精度，没必要上时间格式化 */
private fun memoryAgo(at: Long): String {
    if (at <= 0L) return "还没跑过"
    val d = (System.currentTimeMillis() - at).coerceAtLeast(0L)
    return when {
        d < 60_000L -> "刚刚"
        d < 3_600_000L -> "${d / 60_000L} 分钟前"
        d < 86_400_000L -> "${d / 3_600_000L} 小时前"
        else -> "${d / 86_400_000L} 天前"
    }
}

/**
 * 首次（以及每次）开启前的强制确认：全文可滚动，**必须停留 20 秒**。
 *
 * 为什么要硬等 20 秒而不是「滚到底就能点」：
 *  - 滚到底只要两秒，手指一滑就到底了，等于没有阅读；
 *  - 这份声明里最要紧的一句是「她可能代你执行操作」，
 *    那是把一个能点支付按钮的手交出去，值得 20 秒。
 *
 * 倒计时跟着这个弹窗的生命周期走：关掉就取消（LaunchedEffect 随组合销毁），
 * 再打开重新从 20 数起 —— 不给人「上次已经等过了」的口子。
 */
@Composable
private fun HabitConsentDialog(onDismiss: () -> Unit, onAgree: () -> Unit) {
    var left by remember { mutableStateOf(HabitTerms.READ_SECONDS) }

    LaunchedEffect(Unit) {
        while (left > 0) {
            delay(1000L)
            left -= 1
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("开启前请先读一遍") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (left > 0) "还剩 ${left} 秒可以点同意" else "读完了，可以决定了",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (left > 0) LunaOnSurfaceVariant else LunaOkFg
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = {
                        ((HabitTerms.READ_SECONDS - left).toFloat() / HabitTerms.READ_SECONDS)
                            .coerceIn(0f, 1f)
                    },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = LunaPrimary,
                    trackColor = LunaOutline
                )
                Spacer(modifier = Modifier.height(10.dp))
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        HabitTerms.TEXT,
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 19.sp
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onAgree, enabled = left <= 0) {
                Text(if (left > 0) "还需阅读 ${left}s" else "我已阅读并同意")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("不同意") }
        }
    )
}

@Composable
private fun HabitTermsDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自我学习 · 免责声明（v${HabitTerms.VERSION}）") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    HabitTerms.TEXT,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 19.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

/**
 * 预设（0.9.24）：把调好的设置导出带走，或者把别人的预设导进来。
 *
 * ## 为什么值得单独一块
 *
 * 键盘高度这种事，同一台机器量一次就够，但换机、重装、刷机之后全丢。
 * 「导出 → 贴给自己 → 新机导入」是这个功能唯一的用法，也是它存在的全部理由。
 *
 * ## 为什么导入要弹文本框
 *
 * 剪贴板里可能是一段别的东西（用户刚复制过链接）。直接应用会莫名其妙地失败，
 * 所以先让用户看见将要导入的内容 —— 也能顺便改一两项再导。
 *
 * 安全：导出物里**没有任何 Key**（见 [PresetIO] 的注释），所以贴到哪儿都不担心。
 */
@Composable
private fun PresetSection(context: android.content.Context) {
    var showImport by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var resultHint by remember { mutableStateOf<String?>(null) }
    val calib = remember { mutableStateOf(com.nexus.agent.copilot.CopilotConfig.keyboardCalibrationPx(context)) }

    Column {
        Text("预设", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "把名字、几个开关和键盘高度打包成一段文字带走。换手机、重装之后导回来，不用一项项重设。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "当前键盘校准：${if (calib.value > 0) "${calib.value} px" else "自动（还没量过）"}",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row {
            LunaButton(onClick = {
                resultHint = PresetIO.exportToClipboard(context)
            }) { Text("导出预设") }
            Spacer(modifier = Modifier.width(12.dp))
            LunaOutlinedButton(onClick = {
                importText = PresetIO.readClipboard(context)
                if (importText.isBlank()) {
                    resultHint = "剪贴板是空的。先把预设文字复制进来。"
                } else {
                    showImport = true
                }
            }) { Text("导入预设") }
        }
        resultHint?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        }
        Text(
            "导出内容不含任何 API Key，也不含聊天记录和好感度。",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (showImport) {
        AlertDialog(
            onDismissRequest = { showImport = false },
            title = { Text("导入预设", fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "下面是从剪贴板读到的内容，确认后应用。只会改动名字、开关和键盘高度。",
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = importText,
                        onValueChange = { importText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 260.dp),
                        textStyle = MaterialTheme.typography.bodySmall,
                        maxLines = 12
                    )
                }
            },
            confirmButton = {
                LunaTextButton(onClick = {
                    resultHint = PresetIO.import(context, importText)
                    calib.value = com.nexus.agent.copilot.CopilotConfig.keyboardCalibrationPx(context)
                    showImport = false
                }) { Text("应用") }
            },
            dismissButton = {
                LunaTextButton(onClick = { showImport = false }) { Text("取消") }
            }
        )
    }
}
