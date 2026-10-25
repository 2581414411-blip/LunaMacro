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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nexus.agent.ui.components.LunaButton
import com.nexus.agent.ui.theme.LunaBackground
import com.nexus.agent.ui.theme.LunaErrBg
import com.nexus.agent.ui.theme.LunaErrFg
import com.nexus.agent.ui.theme.LunaOnBackground
import com.nexus.agent.ui.theme.LunaOnSurfaceVariant
import com.nexus.agent.ui.theme.LunaOutline
import com.nexus.agent.ui.theme.LunaPrimary
import com.nexus.agent.ui.theme.LunaSurface
import com.nexus.agent.ui.theme.LunaWarnBg
import com.nexus.agent.ui.theme.LunaWarnFg
import kotlinx.coroutines.delay

/** 免责声明强制阅读时长（秒） */
private const val DISCLAIMER_COUNTDOWN = 35

/**
 * 副驾驶免责声明（全屏、不可取消）。
 *
 * 首次进入「副驾驶」Tab 时强制展示：必须在倒计时结束后才能点「同意并继续」，
 * 返回键与点击外部都不关闭，避免用户「一秒划过」把风险条款点掉。
 *
 * @param onAccept 用户点击同意后回调（调用方负责写 SharedPreferences 并关闭）
 */
@Composable
fun CopilotDisclaimerDialog(onAccept: () -> Unit) {
    var remaining by remember { mutableIntStateOf(DISCLAIMER_COUNTDOWN) }

    LaunchedEffect(Unit) {
        while (remaining > 0) {
            delay(1000)
            remaining -= 1
        }
    }

    Dialog(
        onDismissRequest = { /* 不可取消：只能点「同意并继续」 */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = LunaBackground
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 顶部标题区
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 24.dp, top = 40.dp, bottom = 16.dp)
                ) {
                    Text(
                        "副驾驶 · 使用前必读",
                        style = MaterialTheme.typography.headlineMedium,
                        color = LunaOnBackground
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .width(30.dp)
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(LunaPrimary)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .width(14.dp)
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(LunaOutline)
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        "这个功能会「看你的屏幕」，也会「替你点屏幕」，请先花 35 秒读完下面三条。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LunaOnSurfaceVariant
                    )
                }

                // 正文（可滚动）
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    DisclaimerItem(
                        index = "01",
                        title = "实时读屏 = 隐私风险",
                        body = "开启情绪陪玩后，App 会以固定频率截取当前屏幕画面用于本地分析。\n" +
                            "• 截图仅在本机内存中处理，不上传云端（本地模型推理）；\n" +
                            "• 屏幕上的任何内容都可能被截到，请勿在开启期间展示身份证、银行卡、密码、验证码等敏感信息；\n" +
                            "• 检测到银行、支付、密码类应用时，会自动暂停截屏。",
                        bg = LunaWarnBg,
                        fg = LunaWarnFg
                    )
                    DisclaimerItem(
                        index = "02",
                        title = "AI 生成内容免责",
                        body = "弹幕与建议由本地小模型（Qwen2-VL-2B）实时生成，可能识别错误、措辞不当或与事实不符。" +
                            "内容仅供娱乐参考，不构成任何操作建议；据此操作的风险由使用者自行承担。",
                        bg = LunaSurface,
                        fg = LunaOnBackground
                    )
                    DisclaimerItem(
                        index = "03",
                        title = "严禁用于游戏作弊与支付",
                        body = "副驾驶不仅能聊天，还会按你的指令真的操作手机（打开应用、点击、滑动、跑多步任务）：\n" +
                            "• 严禁用于任何破坏游戏公平性的自动化作弊、脚本宏、多开挂机；\n" +
                            "• 严禁用于支付、转账、下单、验证码识别等任何资金或账号安全相关操作（检测到支付宝/银行等界面会直接拒绝代为操作）；\n" +
                            "• AI 可能看错屏幕、点错按钮，重要操作请自己确认，别把控制权完全交出去；\n" +
                            "• 请在遵守游戏/应用服务条款与所在地法律法规的前提下使用。\n" +
                            "违规使用产生的一切后果由使用者自行承担。",
                        bg = LunaErrBg,
                        fg = LunaErrFg
                    )
                    Text(
                        "点击下方按钮即表示你已阅读并同意以上全部内容。可随时在「副驾驶」页关闭灵动岛与情绪陪玩。",
                        style = MaterialTheme.typography.bodySmall,
                        color = LunaOnSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                // 底部按钮：倒计时结束前不可点
                val unlocked = remaining <= 0
                LunaButton(
                    onClick = { if (unlocked) onAccept() },
                    enabled = unlocked,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    Text(
                        if (unlocked) "同意并继续" else "请仔细阅读并同意（${remaining}s）",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (unlocked) MaterialTheme.colorScheme.onPrimary else LunaOnSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun DisclaimerItem(
    index: String,
    title: String,
    body: String,
    bg: androidx.compose.ui.graphics.Color,
    fg: androidx.compose.ui.graphics.Color
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = bg
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    index,
                    style = MaterialTheme.typography.titleMedium,
                    color = fg.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = fg
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = fg.copy(alpha = 0.85f),
                lineHeight = 21.sp
            )
        }
    }
}
