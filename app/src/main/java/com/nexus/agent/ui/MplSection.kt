package com.nexus.agent.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexus.agent.accessibility.NexusAccessibilityService
import com.nexus.agent.bridge.MplLog
import com.nexus.agent.bridge.MplProtocol
import com.nexus.agent.bridge.MplServer
import com.nexus.agent.system.StabilityHelper
import com.nexus.agent.userservice.NexusForegroundService

/**
 * 设置页「MPL 桥」区块（0.9.31，0.9.32 补可靠性）。
 *
 * 独立文件，不动 [SettingsTab] 结构 —— 那一坨已经 2700 行了，
 * 再往里塞新功能等于给未来的自己挖坑。
 *
 * 这里只做这些事：开关、端口、粘贴配对码、看最近调用、看最近一次连接、电池提醒。
 * **不显示完整 token**：设置页可能被截屏、被旁人看到，
 * 而 token 一旦泄露，等于把"手"交给了别人。
 */
@Composable
internal fun MplSection(context: Context) {
    var enabled by remember { mutableStateOf(MplServer.isEnabled(context)) }
    var running by remember { mutableStateOf(MplServer.isRunning()) }
    var portText by remember { mutableStateOf(MplServer.port(context).toString()) }
    var tokenInput by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf("") }
    var recent by remember { mutableStateOf(MplLog.recent(context)) }
    var lastConn by remember { mutableStateOf(MplLog.lastConn(context)) }
    var batteryOk by remember { mutableStateOf(StabilityHelper.isIgnoringBatteryOptimizations(context)) }
    var bound by remember { mutableStateOf(MplServer.boundAddresses()) }
    var accessibilityOk by remember { mutableStateOf(NexusAccessibilityService.isBound()) }
    var residentOk by remember { mutableStateOf(NexusForegroundService.isRunning()) }

    // 页面开着时每 2 秒刷一次：用户多半刚在 MPL 里点完"试一下"，回来看有没有到
    LaunchedEffect(enabled) {
        while (true) {
            running = MplServer.isRunning()
            bound = MplServer.boundAddresses()
            recent = MplLog.recent(context)
            lastConn = MplLog.lastConn(context)
            batteryOk = StabilityHelper.isIgnoringBatteryOptimizations(context)
            accessibilityOk = NexusAccessibilityService.isBound()
            residentOk = NexusForegroundService.isRunning()
            kotlinx.coroutines.delay(2000)
        }
    }

    Text("MPL 桥", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        "让同一台手机上的 MPL 把「手」交给宏月：她读屏、开应用，都由宏月执行。" +
            "只监听 127.0.0.1，局域网上的设备连不上；配对码只认请求头，不走网址。" +
            "这一版只开两个只读动作（读屏、打开应用），会点的动作还没放开。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        lineHeight = 17.sp
    )
    Spacer(Modifier.height(8.dp))

    // 0.9.33：把「能不能动手」的两个前提摆在最上面。
    // 联调时最耗时的一步就是猜「端口通了、为什么 injection=none」——
    // 无障碍掉了 / 前台服务没起，这两行能一眼看出来，不用回首页看弹窗。
    Text(
        buildString {
            append(if (accessibilityOk) "✅" else "❌").append(" 无障碍")
            append(if (accessibilityOk) "（能读屏）" else "（掉了：读屏会失败，去首页按提示重开）")
            append("\n")
            append(if (residentOk) "✅" else "❌").append(" 前台服务")
            append(if (residentOk) "（后台不易被冻）" else "（没在跑：后台可能被冻住，端口在也不回包）")
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (accessibilityOk && residentOk) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.error,
        lineHeight = 17.sp
    )
    Spacer(Modifier.height(10.dp))

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("开启 MPL 桥", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(3.dp))
            Text(
                if (enabled) {
                    if (running) "监听中：${bound.joinToString(", ")} 端口 ${MplServer.currentPort()}"
                    else "已开启但没监听成功（端口被占用？换一个）"
                } else {
                    "已关闭：端口 18080 上没有服务"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = { on ->
                val ok = MplServer.setEnabled(context, on)
                enabled = MplServer.isEnabled(context)
                running = MplServer.isRunning()
                bound = MplServer.boundAddresses()
                batteryOk = StabilityHelper.isIgnoringBatteryOptimizations(context)
                hint = if (on && !ok) "启动失败：端口 $portText 被占用，换一个再试" else ""
            }
        )
    }

    // 0.9.32 W3：打开了桥但不是「电池不限制」时，后台会被系统冻结 ——
    // 端口还在、但一个包都不回。这正是上次联调里最难查的那个“超时”。
    if (enabled && !batteryOk) {
        Spacer(Modifier.height(8.dp))
        Text(
            "⚠️ 系统还允许「优化」本应用的电池。那样宏月一进后台就可能被冻住：端口还在、但不回包，" +
                "MPL 会一直等到超时。点下面的按钮选「不限制 / 允许后台运行」。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            lineHeight = 17.sp
        )
        Spacer(Modifier.height(6.dp))
        Button(
            onClick = {
                runCatching { StabilityHelper.requestIgnoreBatteryOptimizations(context) }
                    .onFailure { hint = "打不开系统设置，去手机设置里手动允许后台运行" }
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            )
        ) { Text("去设置电池不限制") }
    }

    Spacer(Modifier.height(14.dp))

    Text("监听端口", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = portText,
            onValueChange = { s -> portText = s.filter { it.isDigit() }.take(5) },
            label = { Text("端口（默认 ${MplProtocol.PORT}）") },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = {
                val p = portText.toIntOrNull()
                hint = when {
                    p == null || p !in 1024..65535 -> "端口要在 1024–65535 之间"
                    MplServer.setPort(context, p) -> {
                        running = MplServer.isRunning()
                        bound = MplServer.boundAddresses()
                        "已切到 $p"
                    }
                    else -> "切换失败：$p 被占用"
                }
            },
            colors = ButtonDefaults.buttonColors()
        ) { Text("应用") }
    }

    Spacer(Modifier.height(14.dp))

    Text("配对码", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(3.dp))
    Text(
        "MPL 生成一串 64 位十六进制，复制过来粘在这里。当前：${MplServer.maskedToken(context)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        lineHeight = 17.sp
    )
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
        value = tokenInput,
        onValueChange = { tokenInput = it },
        label = { Text("粘贴配对码") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
    Row {
        Button(
            onClick = {
                hint = if (MplServer.setToken(context, tokenInput)) {
                    tokenInput = ""
                    "配对码已保存（重启后仍在）"
                } else {
                    "格式不对：应该是 32–128 位十六进制"
                }
            }
        ) { Text("保存") }
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = {
                MplServer.clearToken(context)
                tokenInput = ""
                hint = "配对码已清除，MPL 那边需要重新配"
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) { Text("清除") }
    }

    if (hint.isNotBlank()) {
        Spacer(Modifier.height(6.dp))
        Text(
            hint,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            lineHeight = 18.sp
        )
    }

    Spacer(Modifier.height(14.dp))

    Text("最近一次连接", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(3.dp))
    Text(
        "所有请求都算，包括 ping / health / 配对码不对。用来回答“到底有没有人来敲过门”——" +
            "「最近调用」只记动作，两者不是一回事。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        lineHeight = 17.sp
    )
    Spacer(Modifier.height(4.dp))
    val conn = lastConn
    if (conn == null) {
        Text(
            "还没有任何连接。如果 MPL 那边点过按钮，这里仍然空着 —— 那就是请求根本没发出来。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        Text(
            conn.line(),
            style = MaterialTheme.typography.bodySmall,
            color = when {
                conn.status == 401 -> MaterialTheme.colorScheme.error
                conn.status == 0 -> MaterialTheme.colorScheme.onSurfaceVariant
                conn.status == 200 -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            }
        )
    }

    Spacer(Modifier.height(14.dp))

    Text("最近调用", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    if (recent.isEmpty()) {
        Text(
            "还没有调用。MPL 那边点「试一下」之后，这里会出现 id 前 8 位、动作、耗时。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        recent.take(8).forEach { e ->
            Text(
                e.line(),
                style = MaterialTheme.typography.bodySmall,
                color = if (e.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 1.dp)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "共 ${recent.size} 条（最多保留 ${MplLog.MAX} 条）。id 前 8 位能和 MPL 的动作历史对上。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
