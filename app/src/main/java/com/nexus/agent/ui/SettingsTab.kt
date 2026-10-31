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
import com.nexus.agent.agent.LlmClient
import com.nexus.agent.agent.LlmClientFactory
import com.nexus.agent.audit.AuditLogger
import com.nexus.agent.input.ShizukuManager
import com.nexus.agent.overlay.RunningIndicator
import com.nexus.agent.safety.SafetyPolicy
import com.nexus.agent.system.CrashHandler
import com.nexus.agent.system.LogExporter
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
import com.nexus.agent.game.VisionSelfCheck
import com.nexus.agent.game.VisionWorkerClient
import com.nexus.agent.util.UpdateChecker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
    onTestLlm: (scope: kotlinx.coroutines.CoroutineScope, onResult: (String) -> Unit) -> Unit,
    onCheckUpdate: () -> Unit,
    onOpenTaskArchive: () -> Unit,
    /** 实验版更新（非空 → 「检查更新」下方显示红字，点它直接进更新流程） */
    experimentalUpdate: UpdateChecker.UpdateInfo? = null,
    onInstallUpdate: (UpdateChecker.UpdateInfo) -> Unit = {}
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

    // 副驾驶视觉模型：默认跟随主 provider（之前写死 qwen-vl，导致配了 Key 也用不上）
    var visionProvider by remember { mutableStateOf(LlmClientFactory.getEffectiveVisionProvider(context)) }
    var visionMenu by remember { mutableStateOf(false) }

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
        Row {
            LunaOutlinedButton(onClick = { showLlmDialog = true }) { Text("配置 LLM") }
            Spacer(modifier = Modifier.width(12.dp))
            LunaButton(onClick = { llmResult = ""; onTestLlm(scope) { r -> llmResult = r } }) { Text("测试 LLM") }
        }
        if (llmResult.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(llmResult, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }

        // 视觉模型实拍测试（0.8.2）：真的截当前屏幕 → 真的跑本地视觉模型 → 原文照给。
        // 对话里「不读屏」时，先用它把「模型不行」和「路由不行」分开，
        // 不用装 adb、不用抓 logcat。
        var visionTesting by remember { mutableStateOf(false) }
        var visionInferResult by remember { mutableStateOf<String?>(null) }
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
 * 本地模型下载区（瘦身版 APK 用）。
 *
 * 三条路：① 应用内下载（走镜像，慢）；② 外部下载器下好 zip 后「导入 zip」；
 * ③ 已解压的文件夹「导入文件夹」。②③ 不走网络，国内实测最快。
 */
@Composable
private fun ModelDownloadSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var visionReady by remember { mutableStateOf(ModelManager.isVisionModelReady(context)) }
    var senseVoiceReady by remember { mutableStateOf(ModelManager.isSenseVoiceReady(context)) }
    var busy by remember { mutableStateOf(ModelManager.downloading || ModelManager.importing) }
    var progress by remember { mutableStateOf(ModelManager.lastStage) }
    var fraction by remember { mutableStateOf(-1f) }
    var showLinks by remember { mutableStateOf(false) }

    fun refresh() {
        visionReady = ModelManager.isVisionModelReady(context)
        senseVoiceReady = ModelManager.isSenseVoiceReady(context)
    }

    /** 导入结束统一收尾：刷新状态 + 提示 */
    fun afterImport(ok: Boolean) {
        busy = false
        refresh()
        fraction = if (ok) 1f else -1f
        if (ok) {
            progress = "● 导入完成，本地模型已就绪"
            Toast.makeText(context, "模型导入完成", Toast.LENGTH_SHORT).show()
        } else {
            if (!progress.startsWith("导入")) {
                progress = if (progress.isBlank()) "导入失败" else progress
            }
            Toast.makeText(context, progress, Toast.LENGTH_LONG).show()
        }
    }

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
            afterImport(ok)
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
            afterImport(ok)
        }
    }

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

        if (busy) {
            Spacer(modifier = Modifier.height(8.dp))
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
                "下载/导入期间请保持应用在前台",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LunaOutlinedButton(onClick = {
                    importZipLauncher.launch(arrayOf("*/*"))
                }, modifier = Modifier.weight(1f)) {
                    Text("导入 zip")
                }
                LunaOutlinedButton(onClick = {
                    importFolderLauncher.launch(null)
                }, modifier = Modifier.weight(1f)) {
                    Text("导入文件夹")
                }
            }
            if (!visionReady || !senseVoiceReady) {
                Spacer(modifier = Modifier.height(8.dp))
                LunaButton(onClick = {
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
                        progress = if (ok) "● 模型下载完成，可以开始使用了"
                        else progress.ifEmpty { "下载失败，请检查网络后重试" }
                    }
                }) {
                    Text(if (progress.startsWith("下载失败") || progress.contains("不足")) "重试下载" else "应用内下载（约 1.5GB，较慢）")
                }
            }
            if (progress.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    progress,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (visionReady && senseVoiceReady)
                        MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
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
                    title = "语音模型 sensevoice.zip（约 230MB）",
                    zipName = ModelManager.SENSEVOICE_ZIP_NAME
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "也可以手动把解压后的文件放到：${ModelManager.visionDir(context).absolutePath}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 一组手动下载链接：镜像优先，官方源兜底，每条可复制/用浏览器打开 */
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
