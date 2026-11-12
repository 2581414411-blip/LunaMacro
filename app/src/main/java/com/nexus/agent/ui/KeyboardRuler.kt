package com.nexus.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexus.agent.ui.theme.LunaOnBackground
import com.nexus.agent.ui.theme.LunaOnSurfaceVariant
import com.nexus.agent.ui.theme.LunaPrimary
import com.nexus.agent.ui.theme.LunaSurface
import kotlin.math.roundToInt

/**
 * 像素尺子（0.9.24）—— 让用户自己量键盘高度。
 *
 * ## 为什么不再靠代码猜
 *
 * 「输入框离键盘老远」的成因在不同 ROM 上完全不一样：有的系统 resize 过窗口、
 * `ime` insets 又报一遍（双重让位）；有的输入法把工具栏/安全区算进 insets。
 * 这两件事的比例每台机器都不同，用 if 永远覆盖不全 —— 前两版就是这么翻车的。
 *
 * 而用户只要把一条红线拖到**键盘上沿**，就能给出一个在这台机器上绝对正确的数字。
 *
 * ## 为什么不做成 Dialog
 *
 * 这个浮层必须**不抢焦点**：它只在键盘已经弹起时才有意义，一旦抢焦点键盘就收了，
 * 等于让用户在黑屏上量空气。所以它是聊天页里的一个覆盖层，不是 Dialog。
 *
 * ## 坐标系
 *
 * 覆盖层与内容区同高（键盘弹起时内容区已被系统缩过，这正是需要的坐标系）。
 * 红线用「距屏幕底部多少像素」表达，与 `CopilotConfig.keyboardCalibrationPx`
 * 的语义完全一致 —— 存进去布局就能直接用。
 *
 * @param screenHeightPx 物理屏幕高度（px）
 * @param initialPx      已保存的校准值；0 表示还没量过
 * @param autoGuessPx    系统上报的键盘高度，只用来决定红线的初始位置
 */
@Composable
fun KeyboardRulerOverlay(
    screenHeightPx: Int,
    initialPx: Int,
    autoGuessPx: Int,
    onCancel: () -> Unit,
    onSave: (Int) -> Unit
) {
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.42f))
    ) {
        val contentHeightPx = with(density) { maxHeight.toPx() }.roundToInt()
        val safeScreen = if (screenHeightPx > 0) screenHeightPx else contentHeightPx
        val startPx = (if (initialPx > 0) initialPx else autoGuessPx)
            .coerceIn(0, safeScreen)
        // fraction = 红线在内容区里的位置（0 顶部，1 底部）
        var fraction by remember {
            mutableFloatStateOf(
                if (contentHeightPx > 0) {
                    ((safeScreen - startPx).toFloat() / contentHeightPx).coerceIn(0f, 1f)
                } else 0.6f
            )
        }

        val lineY = contentHeightPx * fraction
        // 真正要保存的值：红线到屏幕底部的距离
        val calibPx = (safeScreen - lineY).roundToInt().coerceAtLeast(0)
        val pct = calibPx * 100f / safeScreen

        // ---- 刻度：每 5% 一根，左侧标百分比 ----
        Column(modifier = Modifier.fillMaxSize()) {
            for (i in 0..20) {
                val f = i / 20f
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(with(density) { (contentHeightPx * 0.05f).toDp() }),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(if (i % 4 == 0) 0.22f else 0.10f)
                            .height(1.dp)
                            .background(
                                Color.White.copy(alpha = if (i % 4 == 0) 0.55f else 0.22f)
                            )
                    )
                    if (i % 2 == 0) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "${100 - (f * 100).roundToInt()}%",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }

        // ---- 红线 ----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(0, lineY.roundToInt()) }
                .height(3.dp)
                .background(Color(0xFFFF5252))
        )

        // ---- 拖拽区（整块都能拖，比只拖那条 3dp 的线友善）----
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(contentHeightPx) {
                    detectVerticalDragGestures { change, dragAmount ->
                        change.consume()
                        if (contentHeightPx > 0) {
                            fraction = (fraction + dragAmount / contentHeightPx)
                                .coerceIn(0f, 1f)
                        }
                    }
                }
        )

        // ---- 顶部说明（在拖拽区之上）----
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 18.dp)
        ) {
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(LunaSurface.copy(alpha = 0.96f))
                    .padding(14.dp)
            ) {
                Text(
                    "把红线拖到键盘的上沿",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = LunaOnBackground
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "按住屏幕任意位置上下拖。红线贴住键盘顶部时松手，再点保存。",
                    fontSize = 12.sp,
                    color = LunaOnSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    "键盘顶距屏幕底部：$calibPx px（${"%.1f".format(pct)}%）",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = LunaPrimary
                )
                Text(
                    "屏幕高 ${safeScreen}px · 内容区 ${contentHeightPx}px",
                    fontSize = 11.sp,
                    color = LunaOnSurfaceVariant
                )
            }
        }

        // ---- 底部按钮 ----
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RulerButton("−1") {
                fraction = (fraction - 1f / contentHeightPx.coerceAtLeast(1)).coerceIn(0f, 1f)
            }
            RulerButton("+1") {
                fraction = (fraction + 1f / contentHeightPx.coerceAtLeast(1)).coerceIn(0f, 1f)
            }
            Spacer(modifier = Modifier.weight(1f))
            RulerButton("取消", ghost = true) { onCancel() }
            RulerButton("保存") { onSave(calibPx) }
        }
    }
}

@Composable
private fun RulerButton(text: String, ghost: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (ghost) Color.White.copy(alpha = 0.20f) else Color(0xFFFF5252))
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
