package com.nexus.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import com.nexus.agent.accessibility.NexusAccessibilityService
import com.nexus.agent.copilot.CopilotConfig
import com.nexus.agent.copilot.CopilotEngine
import com.nexus.agent.overlay.CopilotIslandOverlay
import com.nexus.agent.ui.components.GeometricTitle
import com.nexus.agent.ui.components.LunaButton
import com.nexus.agent.ui.components.LunaCard
import com.nexus.agent.ui.components.LunaTextButton
import com.nexus.agent.ui.theme.LunaAccent
import com.nexus.agent.ui.theme.LunaOnBackground
import com.nexus.agent.ui.theme.LunaOnSurfaceVariant
import com.nexus.agent.ui.theme.LunaOutline
import com.nexus.agent.ui.theme.LunaPrimary
import com.nexus.agent.ui.theme.LunaSurface
import com.nexus.agent.ui.theme.LunaSurfaceAlt
import com.nexus.agent.ui.theme.LunaWarnBg

/**
 * 副驾驶 Tab —— 灵动岛 AI 陪玩的总控页。
 *
 * 首次进入会强制弹出免责声明（35 秒倒计时），同意后不再弹。
 * 页面上放：两个开关（灵动岛 / 情绪陪玩）、运行原理说明、最近一条弹幕、隐私熔断说明。
 */
@Composable
fun CopilotTab() {
    val context = LocalContext.current

    var showDisclaimer by remember { mutableStateOf(false) }
    var islandEnabled by remember { mutableStateOf(CopilotConfig.isIslandEnabled(context)) }
    var companionEnabled by remember { mutableStateOf(CopilotConfig.isCompanionEnabled(context)) }
    var lastBubble by remember { mutableStateOf(CopilotConfig.lastBubble(context)) }

    // 首次进入且未同意 → 强制阅读
    LaunchedEffect(Unit) {
        if (!CopilotConfig.isDisclaimerAccepted(context)) {
            showDisclaimer = true
        }
        // 开关是「开」但窗口没起来（比如被系统回收），自动补回来
        if (CopilotConfig.isIslandEnabled(context) && !CopilotIslandOverlay.isShowing()) {
            CopilotIslandOverlay.show(context)
        }
        // 岛的输入框接到副驾驶大脑（看得见屏幕 + 能干活）
        CopilotEngine.bindIsland(context)
    }

    if (showDisclaimer) {
        CopilotDisclaimerDialog(onAccept = {
            CopilotConfig.setDisclaimerAccepted(context, true)
            showDisclaimer = false
        })
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        GeometricTitle(
            title = "副驾驶",
            subtitle = "一个大脑：看得见屏幕、答得了话、也动得了手"
        )

        Spacer(modifier = Modifier.height(20.dp))

        // ---- 两个开关 ----
        LunaCard(modifier = Modifier.fillMaxWidth()) {
            SwitchRow(
                title = "灵动岛",
                desc = "屏幕顶部黑色胶囊，AI 吐槽与打字互动",
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
            Spacer(modifier = Modifier.height(14.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(LunaOutline.copy(alpha = 0.5f))
            )
            Spacer(modifier = Modifier.height(14.dp))
            SwitchRow(
                title = "情绪陪玩",
                desc = "画面突变（死亡 / 爆炸 / 秀操作）时自动发弹幕",
                checked = companionEnabled,
                onCheckedChange = { on ->
                    companionEnabled = on
                    CopilotConfig.setCompanionEnabled(context, on)
                    if (on) {
                        // 岛的承载窗口也得在，否则吐槽没地方显示
                        if (!CopilotIslandOverlay.isShowing()) {
                            CopilotIslandOverlay.show(context)
                        }
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
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ---- 最近弹幕 ----
        LunaCard(modifier = Modifier.fillMaxWidth(), container = LunaAccent.copy(alpha = 0.35f)) {
            Text(
                "最近一条弹幕",
                style = MaterialTheme.typography.labelMedium,
                color = LunaOnSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                lastBubble.ifBlank { "还没吐过槽，去开一局试试" },
                style = MaterialTheme.typography.titleMedium,
                color = LunaOnBackground
            )
            Spacer(modifier = Modifier.height(14.dp))
            LunaButton(
                onClick = {
                    if (!islandEnabled) {
                        Toast.makeText(context, "先把上面的「灵动岛」打开", Toast.LENGTH_SHORT).show()
                    } else {
                        val demo = "666，这波走位无敌"
                        CopilotIslandOverlay.speak(demo)
                        lastBubble = demo
                        CopilotConfig.setLastBubble(context, demo)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("让它说一句", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ---- 原理说明 ----
        LunaCard(modifier = Modifier.fillMaxWidth(), container = LunaSurfaceAlt) {
            Text(
                "它怎么判断「该吐槽了」",
                style = MaterialTheme.typography.titleMedium,
                color = LunaOnBackground
            )
            Spacer(modifier = Modifier.height(10.dp))
            BulletLine("每 1.2 秒抓一帧 720p 画面，压成 32×32 灰度图")
            BulletLine("和上一帧算平均绝对差（MAD），低于 15 视为没变化，直接跳过")
            BulletLine("超过 15（爆炸 / 死亡 / 大招）才唤醒本地 Qwen2-VL 生成吐槽")
            BulletLine("每次触发后强制冷却 5 秒，避免连续刷屏、也省电")
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ---- 隐私熔断 ----
        LunaCard(modifier = Modifier.fillMaxWidth(), container = LunaWarnBg) {
            Text(
                "隐私熔断",
                style = MaterialTheme.typography.titleMedium,
                color = LunaOnBackground
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "检测到前台应用是银行 / 支付 / 证券 / 密码管理类时，立即暂停截屏；" +
                    "离开后自动恢复。截图只在内存中分析，不上传云端。",
                style = MaterialTheme.typography.bodySmall,
                color = LunaOnBackground.copy(alpha = 0.8f),
                lineHeight = 19.sp
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        LunaTextButton(
            onClick = { showDisclaimer = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "重新阅读免责声明",
                style = MaterialTheme.typography.labelLarge,
                color = LunaOnSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun SwitchRow(
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
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = LunaOnBackground
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = LunaOnSurfaceVariant,
                lineHeight = 18.sp
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

@Composable
private fun BulletLine(text: String) {
    Row(modifier = Modifier.padding(bottom = 8.dp)) {
        Box(
            modifier = Modifier
                .padding(top = 7.dp)
                .width(5.dp)
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(LunaPrimary)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = LunaOnBackground.copy(alpha = 0.85f),
            lineHeight = 19.sp
        )
    }
}
