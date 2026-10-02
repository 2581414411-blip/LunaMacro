package com.nexus.agent.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexus.agent.accessibility.NexusAccessibilityService
import com.nexus.agent.input.ShizukuManager
import com.nexus.agent.ui.theme.NexusAgentTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

/**
 * 首次启动引导页（三步）。
 *
 * 1. 欢迎页 —— 介绍 Nexus Agent
 * 2. Shizuku 激活引导
 * 3. 无障碍服务开启引导
 *
 * 完成后写入 SharedPreferences "onboarding_completed" 并跳转 MainActivity。
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
                    },
                    onOpenShizuku = {
                        try {
                            val launch = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                            if (launch != null) startActivity(launch)
                        } catch (e: Throwable) {
                            // Shizuku 未安装，忽略
                        }
                    },
                    onOpenAccessibility = {
                        try {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        } catch (e: Throwable) {
                            // 忽略
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun OnboardingScreen(
    onFinish: () -> Unit,
    onOpenShizuku: () -> Unit,
    onOpenAccessibility: () -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    // 状态检测（Shizuku / 无障碍）
    var shizukuOk by remember { mutableStateOf(false) }
    var accessibilityOk by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            shizukuOk = Shizuku.pingBinder() && ShizukuManager.hasShizukuPermission()
            accessibilityOk = NexusAccessibilityService.isServiceRunning(context.applicationContext)
            delay(1000)
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(32.dp))

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f)
            ) { page ->
                when (page) {
                    0 -> WelcomePage()
                    1 -> ShizukuPage(shizukuOk, onOpenShizuku)
                    2 -> AccessibilityPage(accessibilityOk, onOpenAccessibility)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 底部按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (pagerState.currentPage > 0) {
                    OutlinedButton(onClick = {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                    }) {
                        Text("上一步")
                    }
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }

                if (pagerState.currentPage < 2) {
                    Button(onClick = {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    }) {
                        Text("下一步")
                    }
                } else {
                    Button(onClick = onFinish) {
                        Text("开始使用")
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun WelcomePage() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        Text(
            text = "Nexus Agent",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "你的手机系统级 AI 助手",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "能听懂「你好高总」唤醒词，\n通过读屏 + 语义工具帮你自动操作手机：\n打开应用、点击、滑动、长按、拖拽、多指手势…",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            lineHeight = 24.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ShizukuPage(ok: Boolean, onOpenShizuku: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        Text(
            text = "第一步：激活 Shizuku",
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = if (ok) "✅ Shizuku 已连接且已授权" else "❌ Shizuku 未激活",
            style = MaterialTheme.typography.bodyLarge,
            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Nexus Agent 依赖 Shizuku 获取系统权限，\n用于注入点击、滑动、截图等操作。",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 22.sp
        )
        Spacer(modifier = Modifier.height(24.dp))
        if (!ok) {
            Button(onClick = onOpenShizuku) {
                Text("打开 Shizuku")
            }
        }
    }
}

@Composable
private fun AccessibilityPage(ok: Boolean, onOpenAccessibility: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        Text(
            text = "第二步：开启无障碍服务",
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = if (ok) "✅ 无障碍服务已开启" else "❌ 无障碍服务未开启",
            style = MaterialTheme.typography.bodyLarge,
            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "无障碍服务让 Agent 能「看到」屏幕内容，\n没有它 Agent 就是瞎的，只能乱点。",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 22.sp
        )
        Spacer(modifier = Modifier.height(24.dp))
        if (!ok) {
            Button(onClick = onOpenAccessibility) {
                Text("去开启")
            }
        }
    }
}
