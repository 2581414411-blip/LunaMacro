package com.nexus.agent.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.nexus.agent.accessibility.NexusAccessibilityService
import com.nexus.agent.input.ShizukuManager
import com.nexus.agent.system.StellarInstaller
import com.nexus.agent.ui.theme.NexusAgentTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

/**
 * 首次启动部署向导。
 *
 * 面向「零基础」测试者：逐项检测环境缺失，一键跳转引导，实时打勾。
 * 全程只需测试者跟着点「安装 / 开启 / 允许」，无需任何技术背景。
 *
 * 步骤：欢迎 → 安装 Stellar → 激活服务(配对) → 授权 → 无障碍 → 悬浮窗 → 录音 → 完成。
 *
 * 唯一无法自动化的一步是「无线配对输 6 位配对码」（Shizuku 机制的硬限制，
 * 除非 root），向导会给出傻瓜式引导。
 */
class OnboardingActivity : ComponentActivity() {

    companion object {
        const val PREFS = "nexus_prefs"
        const val KEY_ONBOARDING = "onboarding_completed"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ShizukuManager.init(applicationContext)

        setContent {
            NexusAgentTheme {
                OnboardingScreen(
                    onFinish = {
                        getSharedPreferences(PREFS, MODE_PRIVATE)
                            .edit().putBoolean(KEY_ONBOARDING, true).apply()
                        startActivity(Intent(this, MainActivity::class.java))
                        finish()
                    }
                )
            }
        }
    }
}

@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { 8 })
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // ===== 实时状态检测 =====
    var stellarInstalled by remember { mutableStateOf(StellarInstaller.isInstalled(context)) }
    var shizukuRunning by remember { mutableStateOf(Shizuku.pingBinder()) }
    var shizukuGranted by remember { mutableStateOf(ShizukuManager.hasShizukuPermission()) }
    var accessibilityOk by remember { mutableStateOf(NexusAccessibilityService.isServiceRunning(context)) }
    var overlayOk by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var recordOk by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
        )
    }

    LaunchedEffect(Unit) {
        while (true) {
            stellarInstalled = StellarInstaller.isInstalled(context)
            shizukuRunning = Shizuku.pingBinder()
            shizukuGranted = shizukuRunning && ShizukuManager.hasShizukuPermission()
            accessibilityOk = NexusAccessibilityService.isServiceRunning(context)
            overlayOk = Settings.canDrawOverlays(context)
            recordOk = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            delay(1000)
        }
    }

    // 录音权限请求
    val recordLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    // 每页「是否已就绪」
    fun stepOk(page: Int): Boolean = when (page) {
        0 -> true                       // 欢迎
        1 -> stellarInstalled           // Stellar 已装
        2 -> shizukuRunning             // 服务已激活
        3 -> shizukuGranted             // 已授权
        4 -> accessibilityOk            // 无障碍
        5 -> overlayOk                  // 悬浮窗
        6 -> recordOk                   // 录音
        7 -> true                       // 完成
        else -> true
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f)
            ) { page ->
                when (page) {
                    0 -> WelcomePage()
                    1 -> StellarInstallPage(stellarInstalled) {
                        if (StellarInstaller.canInstall(context)) {
                            StellarInstaller.install(context)
                        } else {
                            StellarInstaller.openInstallPermissionSettings(context)
                        }
                    }
                    2 -> ActivatePage(
                        ok = shizukuRunning,
                        onOpenStellar = { openApp(context, StellarInstaller.STELLAR_PACKAGE) },
                        onOpenWirelessDebug = { openWirelessDebug(context) }
                    )
                    3 -> GrantPage(shizukuGranted) {
                        ShizukuManager.requestShizukuPermission()
                    }
                    4 -> AccessibilityPage(accessibilityOk) {
                        try { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Throwable) {}
                    }
                    5 -> OverlayPage(overlayOk) {
                        try {
                            context.startActivity(
                                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                            )
                        } catch (_: Throwable) {}
                    }
                    6 -> RecordPage(recordOk) {
                        recordLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                    7 -> DonePage(
                        onFinish = onFinish,
                        onRegisterDeepSeek = { openUrl(context, "https://platform.deepseek.com/") },
                        onRegisterDashScope = { openUrl(context, "https://bailian.console.aliyun.com/") }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 进度点
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(8) { i ->
                    Text(
                        text = if (i == pagerState.currentPage) "●" else if (stepOk(i)) "✓" else "○",
                        color = when {
                            i == pagerState.currentPage -> MaterialTheme.colorScheme.primary
                            stepOk(i) -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontSize = 14.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 底部按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (pagerState.currentPage > 0) {
                    OutlinedButton(onClick = {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                    }) { Text("上一步") }
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }

                if (pagerState.currentPage < 7) {
                    Button(
                        onClick = {
                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                        }
                    ) { Text("下一步") }
                } else {
                    Button(onClick = onFinish) { Text("开始使用") }
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

/** 打开某包名的主界面 */
private fun openApp(context: android.content.Context, pkg: String) {
    try {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    } catch (_: Throwable) {}
}

/** 打开 URL（浏览器，用于 API Key 注册跳转） */
private fun openUrl(context: android.content.Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Throwable) {
        android.widget.Toast.makeText(context, "无法打开浏览器，请手动访问：$url", android.widget.Toast.LENGTH_LONG).show()
    }
}

/** 打开系统开发者选项（「无线调试」开关在里面） */
private fun openWirelessDebug(context: android.content.Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
    } catch (_: Throwable) {}
}

// ===== 各步骤页面 =====

@Composable
private fun WelcomePage() {
    StepLayout(
        title = "欢迎",
        icon = "👋",
        desc = "跟着引导点几下，Nexus Agent 就能用了。全程无需技术背景。",
        action = null,
        onAction = {}
    )
}

@Composable
private fun StellarInstallPage(ok: Boolean, onInstall: () -> Unit) {
    StepLayout(
        title = "第一步：安装 Stellar",
        icon = if (ok) "✅" else "📦",
        desc = if (ok) "Stellar 已安装" else "Stellar 是获取系统权限的核心组件，已内置于本 App。\n点「一键安装」后，在弹出的系统界面点「安装」即可。",
        action = if (!ok) "一键安装 Stellar" else null,
        onAction = onInstall
    )
}

@Composable
private fun ActivatePage(ok: Boolean, onOpenStellar: () -> Unit, onOpenWirelessDebug: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        Text(if (ok) "✅" else "🔑", fontSize = 48.sp)
        Spacer(modifier = Modifier.height(16.dp))
        Text("第二步：激活服务", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            if (ok) "服务已激活" else "需要输一次 6 位配对码（安卓安全机制，无法自动）。\n\n① 点「开启无线调试」，进去打开「无线调试」开关\n② 点「打开 Stellar」\n③ 在 Stellar 里点「无线调试 → 启动」\n④ 下拉通知栏，在 Stellar 通知里输入屏幕上的 6 位码",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!ok) {
            Spacer(modifier = Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onOpenWirelessDebug) { Text("开启无线调试") }
                Button(onClick = onOpenStellar) { Text("打开 Stellar") }
            }
        }
    }
}

@Composable
private fun GrantPage(ok: Boolean, onGrant: () -> Unit) {
    StepLayout(
        title = "第三步：授权",
        icon = if (ok) "✅" else "🔓",
        desc = if (ok) "已授权 Nexus Agent" else "让 Nexus Agent 获得操作权限。\n点「授权」，在弹出的界面点「允许」。",
        action = if (!ok) "去授权" else null,
        onAction = onGrant
    )
}

@Composable
private fun AccessibilityPage(ok: Boolean, onOpen: () -> Unit) {
    StepLayout(
        title = "第四步：开启无障碍",
        icon = if (ok) "✅" else "👁",
        desc = if (ok) "无障碍服务已开启" else "让 Agent 能「看到」屏幕。\n点「去开启」，在列表找到「Nexus Agent」并打开开关。",
        action = if (!ok) "去开启" else null,
        onAction = onOpen
    )
}

@Composable
private fun OverlayPage(ok: Boolean, onOpen: () -> Unit) {
    StepLayout(
        title = "第五步：开启悬浮窗",
        icon = if (ok) "✅" else "🪟",
        desc = if (ok) "悬浮窗已开启" else "让 Agent 能显示思考过程和运行灯。\n点「去开启」，允许「显示在其他应用上层」。",
        action = if (!ok) "去开启" else null,
        onAction = onOpen
    )
}

@Composable
private fun RecordPage(ok: Boolean, onRequest: () -> Unit) {
    StepLayout(
        title = "第六步：开启麦克风",
        icon = if (ok) "✅" else "🎤",
        desc = if (ok) "录音权限已开启" else "让 Agent 能听「你好高总」唤醒。\n点「去开启」，在弹窗点「允许」。",
        action = if (!ok) "去开启" else null,
        onAction = onRequest
    )
}

@Composable
private fun DonePage(onFinish: () -> Unit, onRegisterDeepSeek: () -> Unit, onRegisterDashScope: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        Text("🎉", fontSize = 48.sp)
        Spacer(modifier = Modifier.height(16.dp))
        Text("部署完成", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "环境已就绪！\n\n最后还需一个 LLM API Key（让 Agent 思考和看图）：\n· DeepSeek（推荐）\n· 通义千问 DashScope\n\n没账号的话点下面去注册，注册后把 Key 填进 App 的「设置」页即可。",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onRegisterDeepSeek) { Text("注册 DeepSeek") }
            OutlinedButton(onClick = onRegisterDashScope) { Text("注册通义千问") }
        }
    }
}

/** 通用步骤布局 */
@Composable
private fun StepLayout(
    title: String,
    icon: String,
    desc: String,
    action: String?,
    onAction: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        Text(icon, fontSize = 48.sp)
        Spacer(modifier = Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = desc,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            lineHeight = 24.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (action != null) {
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onAction) { Text(action) }
        }
    }
}
