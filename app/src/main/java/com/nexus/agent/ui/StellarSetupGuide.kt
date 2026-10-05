package com.nexus.agent.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexus.agent.input.ShizukuManager

private const val STELLAR_PKG = "roro.stellar.manager"

/**
 * Stellar 配置引导页：分 5 步引导用户完成 vivo OriginOS 的白名单配置。
 */
@Composable
fun StellarSetupGuide(onClose: () -> Unit) {
    val context = LocalContext.current
    var step by remember { mutableStateOf(0) }
    var verifyResult by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Stellar 配置引导", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("关闭") }
        }

        Text("Step ${step + 1}/5", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(progress = { (step + 1) / 5f }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))

        when (step) {
            0 -> GuideCard(
                title = "① 电池白名单",
                desc = "vivo 需要在电池设置中允许 Stellar「后台高耗电」，否则系统会反复杀掉它。",
                button = "打开电池设置"
            ) {
                try {
                    context.startActivity(
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (e: Throwable) {
                    Toast.makeText(context, "请手动到 设置→电池→后台耗电管理 设置", Toast.LENGTH_LONG).show()
                }
            }
            1 -> GuideCard(
                title = "② 自启动白名单",
                desc = "允许 Stellar 自启动，否则重启/杀进程后无法自动拉起。",
                button = "打开自启动管理"
            ) {
                var opened = false
                try {
                    val intent = Intent("com.vivo.permissionmanager.action.APP_AUTO_START")
                    intent.setPackage("com.vivo.permissionmanager")
                    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    opened = true
                } catch (e: Throwable) {
                    opened = false
                }
                if (!opened) {
                    // 兜底：跳应用详情页
                    try {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$STELLAR_PKG"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (_: Throwable) {
                        Toast.makeText(context, "请手动到 设置→应用管理→自启动 设置", Toast.LENGTH_LONG).show()
                    }
                }
            }
            2 -> GuideCard(
                title = "③ 睡眠模式例外",
                desc = "把 Stellar 加入睡眠模式白名单，避免息屏后被强制休眠。",
                button = "打开电池更多设置"
            ) {
                try {
                    context.startActivity(
                        Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (e: Throwable) {
                    Toast.makeText(context, "请手动到 设置→电池→更多电池设置", Toast.LENGTH_LONG).show()
                }
            }
            3 -> GuideCard(
                title = "④ Stellar 内开关",
                desc = "打开 Stellar，确保 Watchdog、Start on Boot、Follow Startup 三个开关都是开着的。",
                button = "打开 Stellar"
            ) {
                val launch = context.packageManager.getLaunchIntentForPackage(STELLAR_PKG)
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launch)
                } else {
                    Toast.makeText(context, "未找到 Stellar，请先安装", Toast.LENGTH_SHORT).show()
                }
            }
            4 -> GuideCard(
                title = "⑤ 验证",
                desc = "配置完成，点击验证检查 Stellar 是否真正可用。",
                button = "验证"
            ) {
                verifyResult = verifyStellar(context)
            }
        }

        // 第 5 步的验证结果显示
        verifyResult?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = if (it.contains("成功")) Color(0xFF69F0AE) else Color(0xFFFF8A80))
            if (it.contains("成功")) {
                Spacer(Modifier.height(8.dp))
                Button(onClick = onClose) { Text("完成") }
            }
        }

        Spacer(Modifier.height(20.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (step > 0) {
                OutlinedButton(onClick = { step-- }, modifier = Modifier.weight(1f)) { Text("上一步") }
            }
            if (step < 4) {
                Button(onClick = { step++ }, modifier = Modifier.weight(1f)) { Text("下一步") }
            }
        }
    }
}

/**
 * 单步引导卡片。
 */
@Composable
private fun GuideCard(title: String, desc: String, button: String, onAction: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(desc, style = MaterialTheme.typography.bodySmall, color = Color.Gray, lineHeight = 20.sp)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onAction) { Text(button) }
        }
    }
}

/**
 * 验证 Stellar 是否真正可用（执行 echo ok）。
 */
private fun verifyStellar(context: Context): String {
    return try {
        if (!com.nexus.agent.input.ShizukuManager.isShizukuAvailable()) {
            return "Stellar 未运行，请重启手机后再试"
        }
        val svc = ShizukuManager.getService()
        if (svc == null) {
            "仍未生效：服务未绑定，请重启手机后再试"
        } else {
            val out = svc.execCommand("echo ok")
            if (out.contains("ok", ignoreCase = true)) {
                "验证成功：Stellar 工作正常"
            } else {
                "仍未生效：命令执行异常，请重启手机后再试"
            }
        }
    } catch (e: Throwable) {
        "验证失败：${e.message}"
    }
}

/**
 * 检查 Stellar 是否已加入电池优化白名单。
 */
fun isStellarIgnoringBatteryOptimizations(context: Context): Boolean {
    return try {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(STELLAR_PKG)
    } catch (e: Throwable) {
        false
    }
}
