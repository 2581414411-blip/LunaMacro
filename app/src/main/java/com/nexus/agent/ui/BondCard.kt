package com.nexus.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexus.agent.persona.BondState
import com.nexus.agent.ui.components.LunaCard
import com.nexus.agent.ui.components.LunaTextButton
import com.nexus.agent.ui.theme.LunaOnBackground
import com.nexus.agent.ui.theme.LunaOnSurfaceVariant
import com.nexus.agent.ui.theme.LunaOutline
import com.nexus.agent.ui.theme.LunaPrimary

/**
 * 羁绊卡片（0.9.2 批次四）。
 *
 * 取代原来顶部那行「宏月 / 已记住最近 N 轮」：
 * 那行字只说明「它在运行」，不说明「你俩什么关系」。
 * 这一版要有三样东西 —— **等级、心情、进度**，一眼看出这段关系走到哪了。
 *
 * 底下那行「她会主动找你」是心跳开关：让用户能关掉这条主动打扰，
 * 也能点「现在就试」当场验证一次（不然要等一天才知道有没有生效）。
 */
@Composable
fun BondCard(
    name: String,
    affinity: Int,
    mood: String,
    memoryTurns: Int,
    memoryFacts: Int,
    heartbeatEnabled: Boolean,
    onHeartbeatChange: (Boolean) -> Unit,
    onTryHeartbeat: () -> Unit
) {
    LunaCard(modifier = Modifier.fillMaxWidth(), contentPadding = 14.dp) {
        // ---- 第一行：名字 + 等级 ……… 心情 ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        name,
                        style = MaterialTheme.typography.titleMedium,
                        color = LunaOnBackground,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        BondState.labelOf(affinity),
                        fontSize = 11.sp,
                        color = LunaOnSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    BondState.hintOf(affinity),
                    fontSize = 11.sp,
                    color = LunaOnSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            // 心情：大 Emoji + 文字，Emoji 单独放大，扫一眼就知道她现在什么状态
            Column(horizontalAlignment = Alignment.End) {
                Text(BondState.emojiOf(mood), fontSize = 22.sp)
                Text(mood.ifBlank { "平静" }, fontSize = 10.sp, color = LunaOnSurfaceVariant)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // ---- 好感度进度条 ----
        BondBar(progress = BondState.progressOf(affinity))

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "已记住最近 $memoryTurns 轮" +
                    if (memoryFacts > 0) " · 长期记忆 $memoryFacts 条" else "",
                fontSize = 10.sp,
                color = LunaOnSurfaceVariant
            )
            Text(
                "$affinity / 100",
                fontSize = 10.sp,
                color = LunaOnSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(LunaOutline.copy(alpha = 0.35f))
        )
        Spacer(modifier = Modifier.height(6.dp))

        // ---- 心跳开关 ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("她会主动找你", fontSize = 12.sp, color = LunaOnBackground)
                Text(
                    if (heartbeatEnabled) {
                        // 0.9.3：频率从「一天一条」放到 15 分钟一个机会，文案必须跟着改，
                        // 否则用户按旧描述以为一天只会响一次，实际一天几十条会当成 bug
                        "你安静 15 分钟，她就可能开口；夜里不吵"
                    } else {
                        "关掉之后只在你想说话时出现"
                    },
                    fontSize = 10.sp,
                    color = LunaOnSurfaceVariant
                )
            }
            if (heartbeatEnabled) {
                LunaTextButton(onClick = onTryHeartbeat) {
                    Text("现在试", fontSize = 11.sp, color = LunaOnSurfaceVariant)
                }
            }
            Switch(
                checked = heartbeatEnabled,
                onCheckedChange = onHeartbeatChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = LunaPrimary,
                    checkedTrackColor = LunaPrimary.copy(alpha = 0.35f)
                )
            )
        }
    }
}

/**
 * 一截自己画的进度条。
 *
 * 不用 `LinearProgressIndicator`：Material3 那个有固定最小高度和两端留白，
 * 塞进卡片里会偏上偏下，圆角也对不齐。这里就两个 Box，简单可控。
 */
@Composable
private fun BondBar(progress: Float) {
    val p = progress.coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(LunaOutline.copy(alpha = 0.35f))
    ) {
        if (p > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(p)
                    .height(6.dp)
                    .padding(end = 0.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(LunaPrimary)
            )
        }
    }
}
