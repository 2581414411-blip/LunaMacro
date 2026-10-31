package com.nexus.agent.ui

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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
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
import com.nexus.agent.util.UpdateChecker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import rikka.shizuku.Shizuku

/**
 * 主界面 —— Scaffold + NavigationBar 四 Tab（首页 / 设置 / 对话 / 插件）。
 *
 * 0.7.11：设置页拆到 SettingsTab.kt，副驾驶开关面板换成人格聊天流 PersonaTab.kt。
 */
class MainActivity : ComponentActivity() {

    /**
     * 每日公告：持有当前要弹的公告（null = 不弹）。
     *
     * 放在 Activity 字段而非 composition 局部状态，是为了在 [onResume] 拉取——
     * App 常驻后台时进程不重建，只在第一次启动判断会漏掉「第二天再打开」的场景。
     */
    private val dailyAnnouncement = mutableStateOf<AnnouncementChecker.Announcement?>(null)

    /** 手动查看公告（首页「公告」小按钮）的结果 */
    private val manualAnnouncement = mutableStateOf<AnnouncementChecker.Announcement?>(null)
    private val manualAnnouncementLoading = mutableStateOf(false)

    /** 待确认的更新（非空 → 弹「发现新版本」询问框） */
    private val updatePrompt = mutableStateOf<UpdateChecker.UpdateInfo?>(null)

    /** 更新下载/安装界面状态（非空 → 弹进度框） */
    private val updateUi = mutableStateOf<UpdateUiState?>(null)

    private var updateJob: Job? = null

    /** 当前正在处理的更新（错误重试时复用） */
    private var updatingInfo: UpdateChecker.UpdateInfo? = null

    /** 强制更新（非空 → 全屏遮罩锁死，不更新不给用） */
    private val mandatoryUpdate = mutableStateOf<UpdateChecker.UpdateInfo?>(null)

    /**
     * 实验版更新（非空 → 设置页红点）。
     *
     * 存完整 [UpdateChecker.UpdateInfo] 而不是只存版本号字符串：以前只存 String，
     * 红点旁边没有任何可点的东西，用户看到「● 实验版 v0.8.0-alpha2」却装不了 ——
     * 点「检查更新」只会再弹一次「可在设置页检查更新处安装」，原地打转。
     * 现在拿得到 apkUrl，红点本身就是一键安装入口。
     */
    private val experimentalUpdate = mutableStateOf<UpdateChecker.UpdateInfo?>(null)

    /** 本次进程生命周期内是否已自动检查过更新（避免每次回前台都请求一遍） */
    private var autoUpdateChecked = false

    override fun onResume() {
        super.onResume()
        // 未完成引导时 onCreate 已 finish()，这里不要再发网络请求
        if (isFinishing) return
        // 每天第一次进前台拉一次公告（内部已做「今天查过就跳过」，开销仅一次 prefs 读）
        lifecycleScope.launch {
            val ann = AnnouncementChecker.fetchIfNeeded(this@MainActivity)
            if (ann != null) dailyAnnouncement.value = ann
        }
        // 强制更新：重启后第一件事就是锁上，不等网络返回
        if (mandatoryUpdate.value == null) {
            UpdateChecker.pendingMandatory(this)?.let {
                Log.i("MainActivity", "存在未完成的强制更新 v${it.version}，继续锁定")
                mandatoryUpdate.value = it
            }
        }
        // 每次启动自动检查一次更新（按档位决定：红点 / 弹窗 / 锁机）
        if (!autoUpdateChecked) {
            autoUpdateChecked = true
            lifecycleScope.launch {
                handleUpdateInfo(UpdateChecker.checkLatest(this@MainActivity), manual = false)
            }
        }
        // 灵动岛开关是开的，就把窗口补起来（进程被回收/重启后自动恢复）
        if (CopilotConfig.isIslandEnabled(this) && !CopilotIslandOverlay.isShowing()) {
            CopilotIslandOverlay.show(this)
        }
        // 灵动岛输入框接给副驾驶大脑（幂等；即使陪玩没开也要接，否则岛上问话没人应）
        CopilotEngine.bindIsland(this)
        // 情绪陪玩开关是开的，就把感知循环接上（幂等，已在跑则直接返回）
        if (CopilotConfig.isCompanionEnabled(this)) {
            CopilotEngine.start(this)
        }
    }

    private fun currentVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "0.0.0"
    } catch (e: Throwable) {
        "0.0.0"
    }

    /** 设置页「检查更新」：手动检查，结果无论有没有都明确提示 */
    private fun checkUpdateManually() {
        lifecycleScope.launch {
            handleUpdateInfo(UpdateChecker.checkLatest(this@MainActivity), manual = true)
        }
    }

    /**
     * 按三档策略处理更新结果。
     *
     * - experimental：只点亮设置页红点
     * - recommended：普通弹窗（可稍后）
     * - mandatory：全屏遮罩锁死，并持久化（重启继续锁）
     *
     * @param manual true = 用户主动点「检查更新」，需要 Toast 明确反馈
     */
    private fun handleUpdateInfo(info: UpdateChecker.UpdateInfo?, manual: Boolean) {
        val current = currentVersionName()
        if (info == null) {
            if (manual) {
                Toast.makeText(this, "检查更新失败，请检查网络", Toast.LENGTH_SHORT).show()
            }
            return
        }
        val newer = info.force || isNewerVersion(info.version, current)
        if (!newer) {
            // 已经追平（用户装完新版了）：把锁和历史标记一起清掉
            UpdateChecker.clearMandatory(this)
            mandatoryUpdate.value = null
            experimentalUpdate.value = null
            Log.i("MainActivity", "检查更新：已是最新（当前 $current）")
            if (manual) {
                Toast.makeText(this, "已是最新版本 v$current", Toast.LENGTH_SHORT).show()
            }
            return
        }

        when (info.level) {
            UpdateChecker.UpdateLevel.MANDATORY -> {
                Log.i("MainActivity", "命中强制更新 v${info.version}，锁机")
                UpdateChecker.rememberMandatory(this, info)
                updatePrompt.value = null
                mandatoryUpdate.value = info
            }

            UpdateChecker.UpdateLevel.EXPERIMENTAL -> {
                Log.i("MainActivity", "发现实验版 v${info.version}（当前 $current）")
                experimentalUpdate.value = info
                when {
                    // 用户主动点的「检查更新」：直接把弹窗摆出来，点一下就能装。
                    // 不这么做的话，这条分支以前只会弹一句「可在设置页安装」，
                    // 而设置页那儿根本没有安装入口 —— 死循环。
                    manual -> updatePrompt.value = info
                    // 自动检查：只亮红点，不打扰
                    else -> Unit
                }
            }

            UpdateChecker.UpdateLevel.RECOMMENDED -> {
                Log.i("MainActivity", "发现推荐更新 v${info.version}（当前 $current）")
                if (info.apkUrl == null) {
                    if (manual) {
                        Toast.makeText(this, "最新版本缺少安装包，请稍后再试", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    updatePrompt.value = info
                }
            }
        }
    }

    /** 无 APK 资产时，退到浏览器打开 Releases 页 */
    private fun openReleasesPage() {
        try {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    android.net.Uri.parse(UpdateChecker.releasesPageUrl())
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Throwable) {
            Toast.makeText(this, "打不开浏览器：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** 首页「公告」按钮：立即拉取，失败/无公告都给明确反馈 */
    private fun checkAnnouncementManually() {
        if (manualAnnouncementLoading.value) return
        manualAnnouncementLoading.value = true
        lifecycleScope.launch {
            when (val r = AnnouncementChecker.checkNow()) {
                is AnnouncementChecker.FetchResult.Has -> manualAnnouncement.value = r.announcement
                AnnouncementChecker.FetchResult.Empty ->
                    Toast.makeText(this@MainActivity, "当前没有新公告", Toast.LENGTH_SHORT).show()
                AnnouncementChecker.FetchResult.Failed ->
                    Toast.makeText(this@MainActivity, "公告拉取失败，请检查网络后重试", Toast.LENGTH_SHORT).show()
            }
            manualAnnouncementLoading.value = false
        }
    }

    /** 用户确认更新 → 应用内下载 → 拉起系统安装器 */
    private fun startUpdate(info: UpdateChecker.UpdateInfo) {
        val apkUrl = info.apkUrl ?: run {
            Toast.makeText(this, "该版本缺少安装包，请稍后再试", Toast.LENGTH_SHORT).show()
            return
        }
        updatePrompt.value = null
        updateJob?.cancel()
        updatingInfo = info
        updateUi.value = UpdateUiState(
            version = info.version,
            phase = UpdatePhase.DOWNLOADING,
            percent = 0f,
            message = "准备下载…"
        )
        updateJob = lifecycleScope.launch {
            try {
                val apk = AppUpdater.downloadApk(
                    context = this@MainActivity,
                    version = info.version,
                    apkUrl = apkUrl,
                    expectedSize = info.apkSize
                ) { p, done, total ->
                    val st = updateUi.value ?: return@downloadApk
                    updateUi.value = st.copy(
                        percent = if (p < 0f) 0f else p,
                        downloaded = done,
                        total = total,
                        message = if (total > 0) {
                            "正在下载 ${fmtMb(done)} / ${fmtMb(total)}"
                        } else {
                            "正在下载 ${fmtMb(done)}"
                        }
                    )
                }
                if (apk == null) {
                    updateUi.value = updateUi.value?.copy(
                        phase = UpdatePhase.ERROR,
                        message = "下载失败：所有下载源都不通，请稍后重试（已下载部分会续传）"
                    )
                    return@launch
                }
                updateUi.value = updateUi.value?.copy(
                    phase = UpdatePhase.READY,
                    percent = 1f,
                    message = "已下载 ${fmtMb(apk.length())}，点「立即安装」完成更新"
                )
                // 顺手清掉旧版本的安装包，别让外部私有目录越攒越大
                AppUpdater.cleanOldApks(this@MainActivity, keepVersion = info.version)
                // 已具备安装权限就直接拉起安装器，省一次点击
                if (AppUpdater.canInstall(this@MainActivity)) {
                    installDownloaded(apk)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e("MainActivity", "更新下载异常", e)
                updateUi.value = updateUi.value?.copy(
                    phase = UpdatePhase.ERROR,
                    message = "下载异常：${e.message ?: e.javaClass.simpleName}"
                )
            }
        }
    }

    private fun installDownloaded(apk: java.io.File) {
        val err = AppUpdater.install(this, apk)
        if (err == null) {
            updateUi.value = updateUi.value?.copy(
                phase = UpdatePhase.INSTALLING,
                message = "已拉起系统安装器，按提示完成安装即可"
            )
        } else {
            updateUi.value = updateUi.value?.copy(
                phase = UpdatePhase.READY,
                message = err
            )
        }
    }

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

        // 0.8.0：那块带「停止」按钮的思考浮窗（大黑块）已废弃，状态提示全部走灵动岛，
        // 所以这里不再挂 onStopRequested。语音流程仍可由首页的语音开关
        // （VoiceAssistant.stop）或灵动岛点击中断来停止。

        // 语音助手接线：识别出指令后交给副驾驶大脑（聊天/干活同一个入口），
        // 结束时用最后一句回复做 TTS 播报。
        VoiceAssistant.onCommand = { command ->
            runOnUiThread {
                var lastReply: String? = null
                CopilotEngine.ask(
                    this@MainActivity,
                    command,
                    onReply = { reply -> lastReply = reply },
                    onDone = {
                        VoiceAssistant.reportExecuteResult(lastReply ?: "任务结束")
                    }
                )
            }
        }

        setContent {
            NexusAgentTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    GeometricBackground()
                    NexusHome(
                        onAskCopilot = { text, onReply ->
                            CopilotEngine.ask(this@MainActivity, text, onReply)
                        },
                        onVoiceInput = { onStatus, onText ->
                            CopilotVoiceInput.listenOnce(this@MainActivity, onStatus, onText)
                        },
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
                        onTestLlm = ::testLlm,
                        onCheckUpdate = ::checkUpdateManually,
                        onShowAnnouncement = ::checkAnnouncementManually,
                        announcementLoading = manualAnnouncementLoading.value,
                        experimentalUpdate = experimentalUpdate.value,
                        onInstallUpdate = ::startUpdate
                    )
                }

                // 每日公告弹窗（内容在 onResume 拉取）
                dailyAnnouncement.value?.let { ann ->
                    AnnouncementDialog(ann) { dailyAnnouncement.value = null }
                }

                // 手动查看公告（首页「公告」按钮）
                manualAnnouncement.value?.let { ann ->
                    AnnouncementDialog(ann) { manualAnnouncement.value = null }
                }

                // 发现新版本 → 询问是否更新
                updatePrompt.value?.let { info ->
                    UpdatePromptDialog(
                        info = info,
                        currentVersion = currentVersionName(),
                        isExperimental = info.level == UpdateChecker.UpdateLevel.EXPERIMENTAL,
                        onConfirm = { startUpdate(info) },
                        onDismiss = { updatePrompt.value = null }
                    )
                }

                // 更新下载 / 安装进度
                updateUi.value?.let { state ->
                    UpdateProgressDialog(
                        state = state,
                        apk = AppUpdater.apkFile(this@MainActivity, state.version),
                        onCancel = {
                            updateJob?.cancel()
                            updateJob = null
                            updateUi.value = null
                        },
                        onInstall = { installDownloaded(AppUpdater.apkFile(this@MainActivity, state.version)) },
                        onRetry = {
                            val info = updatingInfo
                            if (info != null) startUpdate(info) else updateUi.value = null
                        }
                    )
                }

                // 强制更新：最后渲染 → 盖住全部界面；返回键在这里被吞掉
                mandatoryUpdate.value?.let { info ->
                    BackHandler(enabled = true) {
                        // 故意什么都不做：锁机期间返回键无效，也不弹「再按一次退出」
                    }
                    MandatoryUpdateOverlay(
                        info = info,
                        currentVersion = currentVersionName(),
                        progress = updateUi.value,
                        onDownload = { startUpdate(info) },
                        onRetry = {
                            lifecycleScope.launch {
                                handleUpdateInfo(
                                    UpdateChecker.checkLatest(this@MainActivity),
                                    manual = true
                                )
                            }
                        },
                        onOpenReleases = { openReleasesPage() }
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

    private fun toggleWakeWord(enable: Boolean, onResult: (Boolean, String) -> Unit) {
        if (enable) {
            if (!recordPermissionStatus()) {
                onResult(false, "❌ 未授予录音权限\n请先申请录音权限")
                return
            }
            val word = WakeWordConfig.getWakeWord(this)
            if (!com.nexus.agent.speech.LocalAsr.isModelAvailable(this)) {
                // 唤醒模型在 assets 里，总能起来；但识别模型缺失时必须提前讲清楚
                onResult(
                    true,
                    "⚠️ 唤醒已开启（说「$word」）\n但语音识别模型未安装，现在只能唤醒不能识别"
                )
                VoiceAssistant.start(this)
                return
            }
            // 真实启动结果回调，不再无脑报「已开启」
            VoiceAssistant.start(this) { ok ->
                runOnUiThread {
                    if (ok) {
                        onResult(true, "✅ 语音助手已开启\n说「$word」即可唤醒")
                    } else {
                        onResult(
                            false,
                            "❌ 唤醒启动失败\n检查录音权限是否被系统静音 / 前台服务是否被限制"
                        )
                    }
                }
            }
        } else {
            VoiceAssistant.stop()
            onResult(false, "已停止语音助手")
        }
    }
}

/** 全局 UI 状态（供唤醒回调等非 Compose 代码向 Compose 界面传递信号） */
object UiState {
    /** 待切换的 Tab（0=无 1=设置 2=副驾驶 3=游戏） */
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

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun NexusHome(
    onAskCopilot: (text: String, onReply: (String) -> Unit) -> Unit,
    onVoiceInput: (onStatus: (String) -> Unit, onText: (String) -> Unit) -> Unit,
    onPermissionStatus: () -> String,
    onOpenOverlaySettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenBatteryOptimization: () -> Unit,
    onOpenAutoStart: () -> Unit,
    onOpenBackgroundPower: () -> Unit,
    onRecordPermissionStatus: () -> Boolean,
    onToggleWakeWord: (enable: Boolean, onResult: (Boolean, String) -> Unit) -> Unit,
    onSaveLlm: (provider: String, apiKey: String, baseUrl: String, model: String) -> Unit,
    onLoadLlm: () -> LlmConfigData,
    onTestLlm: (scope: kotlinx.coroutines.CoroutineScope, onResult: (String) -> Unit) -> Unit,
    onCheckUpdate: () -> Unit,
    onShowAnnouncement: () -> Unit,
    announcementLoading: Boolean,
    /** 实验版更新（非空 → 设置页红点；点红点直接进更新流程） */
    experimentalUpdate: UpdateChecker.UpdateInfo? = null,
    onInstallUpdate: (UpdateChecker.UpdateInfo) -> Unit = {}
) {
    var selectedTab by remember { mutableStateOf(0) }

    /** 任务记录/审计全屏页（原「任务」Tab 的内容，收进设置里按需打开） */
    var archiveOpen by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            // 键盘弹起时收起底部导航栏。
            //
            // 不收起的话：Scaffold 会把导航栏高度（约 60dp）算进 content 的底部内边距，
            // 聊天页再叠一次 imePadding（键盘高度），输入框就被顶到「键盘上方 60dp」
            // 的位置——就是用户说的「输入框飞得太高」。收起后 content 底部只剩键盘，
            // 输入框正好贴在键盘上沿。
            if (!WindowInsets.isImeVisible) {
                LunaNavigationBar(
                    selectedIndex = selectedTab,
                    tabs = listOf(
                        "🏠" to "首页",
                        "⚙️" to "设置",
                        "🛸" to "对话",
                        "🧩" to "插件"
                    ),
                    onSelect = { idx ->
                        archiveOpen = false
                        selectedTab = idx
                    }
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (archiveOpen) {
                TaskArchiveScreen(onBack = { archiveOpen = false })
                return@Column
            }
            when (selectedTab) {
                0 -> HomeTab(
                    onPermissionStatus = onPermissionStatus,
                    onToggleWakeWord = onToggleWakeWord,
                    onRecordPermissionStatus = onRecordPermissionStatus,
                    onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                    onOpenOverlaySettings = onOpenOverlaySettings,
                    onOpenBatteryOptimization = onOpenBatteryOptimization,
                    onShowAnnouncement = onShowAnnouncement,
                    announcementLoading = announcementLoading,
                    onAskCopilot = onAskCopilot,
                    onVoiceInput = onVoiceInput,
                    onOpenArchive = { archiveOpen = true }
                )
                1 -> SettingsTab(
                    onPermissionStatus = onPermissionStatus,
                    onOpenOverlaySettings = onOpenOverlaySettings,
                    onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                    onOpenBatteryOptimization = onOpenBatteryOptimization,
                    onOpenAutoStart = onOpenAutoStart,
                    onOpenBackgroundPower = onOpenBackgroundPower,
                    onSaveLlm = onSaveLlm,
                    onLoadLlm = onLoadLlm,
                    onTestLlm = onTestLlm,
                    onCheckUpdate = onCheckUpdate,
                    onOpenTaskArchive = { archiveOpen = true },
                    experimentalUpdate = experimentalUpdate,
                    onInstallUpdate = onInstallUpdate
                )
                2 -> PersonaTab()
                3 -> PluginTab()
            }
        }
    }
}

@Composable
private fun HomeTab(
    onPermissionStatus: () -> String,
    onToggleWakeWord: (enable: Boolean, onResult: (Boolean, String) -> Unit) -> Unit,
    onRecordPermissionStatus: () -> Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onOpenBatteryOptimization: () -> Unit,
    onShowAnnouncement: () -> Unit,
    announcementLoading: Boolean,
    onAskCopilot: (text: String, onReply: (String) -> Unit) -> Unit,
    onVoiceInput: (onStatus: (String) -> Unit, onText: (String) -> Unit) -> Unit,
    onOpenArchive: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var permissionText by remember { mutableStateOf("") }
    // 开关状态必须回读持久化值：之前写死 false，重启后明明在跑却显示「开启唤醒」，
    // 用户点一下被 start() 的单飞逻辑静默忽略，看着就像「点了没反应」。
    var wakeEnabled by remember { mutableStateOf(VoiceAssistant.isEnabled(context)) }
    var asrModelReady by remember { mutableStateOf(com.nexus.agent.speech.LocalAsr.isModelAvailable(context)) }
    var wakeWord by remember { mutableStateOf(WakeWordConfig.getWakeWord(context)) }
    var wakeThreshold by remember { mutableStateOf(WakeWordConfig.getThreshold(context)) }
    // 自定义唤醒词输入（0.7.9）：初始值取当前自定义词，预置状态下留空
    var customWakeInput by remember {
        mutableStateOf(if (WakeWordConfig.isCustom(context)) WakeWordConfig.getWakeWord(context) else "")
    }
    var customWakeMsg by remember { mutableStateOf("") }
    var dailyCollecting by remember { mutableStateOf(DailyCollector.isCollecting) }
    var dailyCount by remember { mutableStateOf(DailyCollector.getCount) }
    var wakeStatusText by remember { mutableStateOf("") }

    // ---- 主控台状态 ----
    var cmdText by remember { mutableStateOf("") }
    var lastReply by remember { mutableStateOf("") }
    var voiceStatus by remember { mutableStateOf("") }
    var overlayOk by remember { mutableStateOf(android.provider.Settings.canDrawOverlays(context)) }
    var batteryOk by remember {
        mutableStateOf(StabilityHelper.isIgnoringBatteryOptimizations(context))
    }

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
            overlayOk = android.provider.Settings.canDrawOverlays(context)
            batteryOk = StabilityHelper.isIgnoringBatteryOptimizations(context)
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
                LunaTextButton(onClick = {
                    showAccessibilityGuide = false
                    onOpenAccessibilitySettings()
                }) { Text("去开启") }
            },
            dismissButton = {
                LunaTextButton(onClick = { showAccessibilityGuide = false }) { Text("稍后") }
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
                LunaTextButton(onClick = {
                    showShizukuGuide = false
                    StabilityHelper.openShizukuWebsite(context)
                }) { Text("去官网") }
            },
            dismissButton = {
                LunaTextButton(onClick = { showShizukuGuide = false }) { Text("知道了") }
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
        // 标题 + 公告入口（小按钮，随时可手动查看公告，不等每日自动那一次）
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            GeometricTitle("宏月", modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.width(8.dp))
            LunaTextButton(
                onClick = onShowAnnouncement,
                enabled = !announcementLoading,
                modifier = Modifier.padding(top = 2.dp)
            ) {
                Text(
                    if (announcementLoading) "获取中…" else "📢 公告",
                    style = MaterialTheme.typography.labelMedium,
                    color = LunaPrimary
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // ---- 顶部：权限状态速览（点一下就跳去修） ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PermissionPill("悬浮窗", overlayOk) { onOpenOverlaySettings() }
            PermissionPill("无障碍", accessibilityEnabled) { onOpenAccessibilitySettings() }
            PermissionPill("Shizuku", shizukuOk) { onOpenOverlaySettings() }
            PermissionPill("省电", batteryOk) { onOpenBatteryOptimization() }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // ---- 主控台：一个输入框，聊天和干活都走这里 ----
        LunaCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                "指挥台",
                style = MaterialTheme.typography.titleMedium,
                color = LunaOnBackground
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "问它屏幕里是什么、让它打开应用或执行任务，都是这一句",
                style = MaterialTheme.typography.bodySmall,
                color = LunaOnSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = cmdText,
                onValueChange = { cmdText = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        "比如：我现在在什么软件里 / 帮我打开微信 / 这关怎么打",
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                minLines = 2,
                maxLines = 4
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                LunaOutlinedButton(
                    onClick = {
                        voiceStatus = ""
                        onVoiceInput(
                            { s -> voiceStatus = s },
                            { text ->
                                voiceStatus = ""
                                cmdText = text
                                lastReply = "…"
                                TaskHistory.add(context, text)
                                onAskCopilot(text) { r -> lastReply = r }
                            }
                        )
                    }
                ) {
                    Text("🎤 说", fontSize = 13.sp)
                }
                Spacer(modifier = Modifier.weight(1f))
                LunaButton(
                    onClick = {
                        val text = cmdText.trim()
                        if (text.isEmpty()) return@LunaButton
                        cmdText = ""
                        lastReply = "…"
                        TaskHistory.add(context, text)
                        onAskCopilot(text) { r -> lastReply = r }
                    },
                    enabled = cmdText.isNotBlank()
                ) {
                    Text("发送", fontSize = 14.sp)
                }
            }

            if (voiceStatus.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    voiceStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = LunaOnSurfaceVariant
                )
            }

            if (lastReply.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = LunaSurfaceAlt,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(
                        text = lastReply,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = LunaOnBackground
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            LunaTextButton(onClick = onOpenArchive, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "任务记录与审计 →",
                    style = MaterialTheme.typography.labelMedium,
                    color = LunaOnSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ---- 定时任务（0.7.10）：到点自动跑一条 Agent 任务 ----
        ScheduleSection(modifier = Modifier.fillMaxWidth())

        Spacer(modifier = Modifier.height(20.dp))

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
                    LunaTextButton(onClick = { showStellarGuide = true }) { Text("去配置") }
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
                        LunaTextButton(onClick = { showShizukuGuide = true }) { Text("了解手动激活") }
                        LunaTextButton(onClick = { StabilityHelper.openShizukuWebsite(context) }) { Text("切换到原版 Shizuku") }
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
                    text = if (wakeEnabled) "✅ 已开启 · 说「$wakeWord」唤醒"
                    else "说「$wakeWord」唤醒",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!asrModelReady) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "⚠️ 语音识别模型未安装：现在只能唤醒、不能识别。到下面「本地模型」导入 sensevoice.zip。",
                        style = MaterialTheme.typography.labelSmall,
                        color = LunaErrFg
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                LunaButton(
                    onClick = {
                        val target = !wakeEnabled
                        if (target && !onRecordPermissionStatus()) {
                            recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            return@LunaButton
                        }
                        wakeEnabled = target
                        onToggleWakeWord(target) { ok, msg ->
                            wakeStatusText = msg
                            // 启动失败就把开关拨回去，不再骗用户「已开启」
                            if (!ok) wakeEnabled = false
                        }
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
                                customWakeInput = ""
                                customWakeMsg = ""
                                WakeWordConfig.setWakeWord(context, word)
                            },
                            label = { Text(word, fontSize = 12.sp) }
                        )
                    }
                }

                // 自定义唤醒词：输入中文 → 自动转成 KWS 要的音素串（0.7.9）
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = customWakeInput,
                        onValueChange = { input ->
                            customWakeInput = input.filterNot { it.isWhitespace() }
                                .take(PinyinConverter.MAX_LEN)
                            customWakeMsg = ""
                        },
                        singleLine = true,
                        label = { Text("自定义唤醒词", fontSize = 12.sp) },
                        placeholder = { Text("如：你好宏月", fontSize = 12.sp) },
                        modifier = Modifier.weight(1f)
                    )
                    LunaButton(onClick = {
                        when (val r = PinyinConverter.toKeywords(context, customWakeInput)) {
                            is PinyinConverter.Result.Ok -> {
                                val w = customWakeInput.trim()
                                WakeWordConfig.setCustomWakeWord(context, w, r.keywords)
                                wakeWord = w
                                customWakeMsg =
                                    "✅ 已设为「$w」：${r.syllables.joinToString(" ")}\n重新开启唤醒后生效"
                            }

                            is PinyinConverter.Result.Err -> customWakeMsg = "⚠️ ${r.message}"
                        }
                    }) { Text("用这个", fontSize = 13.sp) }
                }
                if (customWakeMsg.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = customWakeMsg,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (customWakeMsg.startsWith("✅"))
                            MaterialTheme.colorScheme.onSurfaceVariant else LunaWarnFg
                    )
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
    }
}

/**
 * 权限状态小胶囊：绿/红点 + 名字，点一下跳到对应设置页。
 */
@Composable
private fun PermissionPill(label: String, ok: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (ok) LunaOkBg else LunaErrBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (ok) LunaPrimary else LunaErrFg)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            label,
            fontSize = 11.sp,
            color = if (ok) LunaOkFg else LunaErrFg
        )
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

/**
 * 任务记录与审计（原「任务」Tab 的内容）。
 *
 * 任务入口已经统一到首页指挥台 / 灵动岛，这里只保留「回看历史 + 审计详情」。
 */
@Composable
private fun TaskArchiveScreen(onBack: () -> Unit) {
    val context = LocalContext.current
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "任务记录",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            LunaTextButton(onClick = onBack) { Text("← 返回") }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "执行入口已统一到首页「指挥台」与灵动岛，这里只做回看",
            style = MaterialTheme.typography.bodySmall,
            color = LunaOnSurfaceVariant
        )

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
                        LunaTextButton(onClick = { exportAudit(context, id) }) { Text("导出") }
                        LunaTextButton(onClick = { selectedAuditId = null }) { Text("关闭") }
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
                if (announcement.content.isNotBlank()) {
                    Text(
                        announcement.content,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                // 分版本说明：只保留最近几个版本，隔版更新也能看到中间改了什么
                if (announcement.versions.isNotEmpty()) {
                    announcement.versions.forEachIndexed { index, note ->
                        if (index > 0 || announcement.content.isNotBlank()) {
                            Spacer(modifier = Modifier.height(14.dp))
                        }
                        VersionNoteBlock(note)
                    }
                }
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
            LunaTextButton(onClick = onConfirm) {
                Text("确定")
            }
        }
    )
}

/**
 * 公告里的单个版本区块：版本号徽标 + 标题 + 要点列表。
 *
 * 公告只保留最近几个版本（[AnnouncementChecker.MAX_VERSIONS]），
 * 用户隔了几个版本才更新时，也能一眼看到中间都改了什么。
 */
@Composable
private fun VersionNoteBlock(note: AnnouncementChecker.VersionNote) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (note.version.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .background(LunaPrimary.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        "v${note.version}",
                        style = MaterialTheme.typography.labelSmall,
                        color = LunaPrimary
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                note.title.ifBlank { "更新说明" },
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
        }
        if (note.highlights.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            note.highlights.forEach { line ->
                Row(modifier = Modifier.padding(bottom = 3.dp)) {
                    Text("·", style = MaterialTheme.typography.bodySmall, color = LunaPrimary)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (note.date.isNotBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                note.date,
                style = MaterialTheme.typography.labelSmall,
                color = LunaOnSurfaceVariant
            )
        }
    }
}
