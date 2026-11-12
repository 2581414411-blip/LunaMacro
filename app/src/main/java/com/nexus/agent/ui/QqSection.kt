package com.nexus.agent.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row as RowLayout
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import com.nexus.agent.qq.QqConfig
import com.nexus.agent.ui.components.LunaButton
import com.nexus.agent.ui.components.LunaOutlinedButton
import com.nexus.agent.ui.components.LunaTextButton
import com.nexus.agent.qq.QqGuard
import com.nexus.agent.qq.QqStateManager
import com.nexus.agent.qq.QqTestProbe

/**
 * 「QQ 自动回复」（0.9.25 批次 E）。
 *
 * ## 这一块为什么长这样
 *
 * 通知使用权是本项目里**最敏感**的一个权限（能读所有 App 的通知），
 * 所以页面的第一件事不是配置项，而是：**现在到底有没有授权**。
 * 没授权时把按钮做成「去开启」，而不是让用户点了一堆配置之后发现根本不工作。
 *
 * ## 抓取测试为什么必须存在
 *
 * 「QQ 通知里到底有哪些字段」只能真机上看：不同版本、主号/分身、群聊/单聊，
 * `EXTRA_TEXT` 的含义都不一样，甚至按钮上有没有 RemoteInput 都不确定。
 * 没有这个入口，排障就只能靠用户口述「点了没反应」。
 */
@Composable
fun QqSection(context: Context) {
    // ---- 授权状态：每次进设置页都重新查 ----
    var granted by remember { mutableStateOf(hasNotificationAccess(context)) }
    var enabled by remember { mutableStateOf(QqConfig.isEnabled(context)) }
    var autoSend by remember { mutableStateOf(QqConfig.isAutoSend(context)) }
    var pkg by remember { mutableStateOf(QqConfig.packageName(context)) }
    var userId by remember { mutableStateOf(QqConfig.userId(context).toString()) }
    var selfNick by remember { mutableStateOf(QqConfig.selfNickname(context)) }
    var confirmAutoSend by remember { mutableStateOf(false) }
    var showProbe by remember { mutableStateOf(false) }

    val probe by QqTestProbe.result.collectAsState()
    val lastSummary by QqStateManager.lastSummary.collectAsState()

    // 抓到一条就弹窗（探针自己会下膛，所以只会来一次）
    LaunchedEffect(probe) {
        if (probe != null) showProbe = true
    }

    Column {
        Text("QQ 自动回复", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "她替你盯着 QQ：收到消息后想一想怎么回，直接在通知里静默发出，不打开 QQ。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(10.dp))

        // ---- 授权状态 ----
        Text(
            if (granted) "通知使用权：已开启" else "通知使用权：未开启（不开就收不到任何消息）",
            fontSize = 12.sp,
            fontWeight = if (granted) FontWeight.Normal else FontWeight.SemiBold,
            color = if (granted) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            }
        )
        Spacer(modifier = Modifier.height(6.dp))
        RowLayout(verticalAlignment = Alignment.CenterVertically) {
            LunaOutlinedButton(onClick = {
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }.onFailure {
                    Toast.makeText(context, "打不开系统设置，请手动去「通知使用权」开启", Toast.LENGTH_LONG).show()
                }
            }) { Text(if (granted) "重新检查" else "去开启") }
            Spacer(modifier = Modifier.width(10.dp))
            LunaTextButton(onClick = { granted = hasNotificationAccess(context) }) {
                Text("刷新状态", fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ---- 总开关 ----
        SwitchRow(
            title = "启用自动回复",
            subtitle = "关掉之后只监听不动作（抓取测试仍然可用）",
            checked = enabled,
            onChange = {
                enabled = it
                QqConfig.setEnabled(context, it)
            }
        )

        SwitchRow(
            title = "静默直接发送",
            subtitle = if (autoSend) {
                "会用她的语气直接发出去，不再问你"
            } else {
                "关闭时只发一条本机通知「建议这样回」，你确认后自己发"
            },
            checked = autoSend,
            onChange = { on ->
                // 打开「直接替人发消息」这种事，必须有一次明确的确认
                if (on) confirmAutoSend = true else {
                    autoSend = false
                    QqConfig.setAutoSend(context, false)
                }
            }
        )

        Spacer(modifier = Modifier.height(10.dp))

        // ---- 包名 ----
        OutlinedTextField(
            value = pkg,
            onValueChange = {
                pkg = it.take(60)
                QqConfig.setPackageName(context, pkg)
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("监听的包名", fontSize = 12.sp) },
            supportingText = {
                Text("默认 com.tencent.mobileqq；TIM 是 com.tencent.tim", fontSize = 11.sp)
            },
            singleLine = true
        )

        Spacer(modifier = Modifier.height(8.dp))

        // ---- 用户 id（分身）----
        OutlinedTextField(
            value = userId,
            onValueChange = {
                val digits = it.filter { c -> c.isDigit() }.take(5)
                userId = digits
                QqConfig.setUserId(context, digits.toIntOrNull() ?: 0)
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("用户 id（应用分身）", fontSize = 12.sp) },
            supportingText = {
                Text(
                    "主号填 0。QQ 分身一般不是 0 —— 先点下面「抓取测试」，" +
                        "在分身里发一条消息，弹窗里会显示它真实的 id。",
                    fontSize = 11.sp
                )
            },
            singleLine = true
        )

        Spacer(modifier = Modifier.height(8.dp))

        // ---- 自己的昵称 ----
        OutlinedTextField(
            value = selfNick,
            onValueChange = {
                selfNick = it.take(20)
                QqConfig.setSelfNickname(context, selfNick)
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("你自己的 QQ 昵称（可选，但强烈建议填）", fontSize = 12.sp) },
            supportingText = {
                Text(
                    "填了才能准确认出「这句是我自己发的」——不填就只能靠内容指纹兜底，" +
                        "偶尔会自己跟自己聊两句。",
                    fontSize = 11.sp
                )
            },
            singleLine = true
        )

        Spacer(modifier = Modifier.height(12.dp))

        // ---- 抓取测试 ----
        RowLayout(verticalAlignment = Alignment.CenterVertically) {
            LunaButton(onClick = {
                QqTestProbe.arm()
                Toast.makeText(
                    context,
                    "已开始抓取。现在去 QQ${if (userId != "0") " 分身" else ""}里给自己发一条消息。",
                    Toast.LENGTH_LONG
                ).show()
            }) { Text("抓取测试") }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                "抓下一条通知的原始字段",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "本小时已回 ${QqGuard.hourlyCount()} / 10 条" +
                (lastSummary?.let { " · 最近：$it" } ?: ""),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    // ---- 静默发送的二次确认 ----
    if (confirmAutoSend) {
        AlertDialog(
            onDismissRequest = { confirmAutoSend = false },
            title = { Text("确认开启静默发送？", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "开启后，她会在你不知情的情况下**直接以你的名义**回复 QQ 消息。\n\n" +
                        "已经有四道闸兜着（不回声自己、60 秒内不重发同样内容、" +
                        "同一聊天 15 秒内只回一次、每小时最多 10 条），" +
                        "但对象是谁、说了什么，她不会先问你。\n\n" +
                        "建议先用「关闭」状态跑几天，看看她的语气你能不能接受。",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                LunaTextButton(onClick = {
                    autoSend = true
                    QqConfig.setAutoSend(context, true)
                    confirmAutoSend = false
                }) { Text("我知道了，开启") }
            },
            dismissButton = {
                LunaTextButton(onClick = { confirmAutoSend = false }) { Text("先算了") }
            }
        )
    }

    // ---- 抓取结果 ----
    if (showProbe) {
        val snap = probe
        AlertDialog(
            onDismissRequest = {
                showProbe = false
                QqTestProbe.disarm()
            },
            title = { Text("抓到的通知", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        snap?.toText() ?: "（没有内容）",
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        "提示：把上面的「用户 id」填进配置项，就能只监听这一个 QQ（主号或分身）。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                LunaTextButton(onClick = {
                    showProbe = false
                    QqTestProbe.disarm()
                }) { Text("知道了") }
            }
        )
    }
}

/** 权限是否已授予：系统里所有被授权的监听器包名里有本应用 */
private fun hasNotificationAccess(context: Context): Boolean = runCatching {
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
}.getOrDefault(false)

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    RowLayout(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
