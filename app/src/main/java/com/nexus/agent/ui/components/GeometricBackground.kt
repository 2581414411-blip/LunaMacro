package com.nexus.agent.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.nexus.agent.ui.theme.LunaBackground
import com.nexus.agent.ui.theme.LunaOutline
import com.nexus.agent.ui.theme.LunaSurfaceAlt

/**
 * 几何切割背景 —— 低饱和抽象画风格。
 *
 * 全屏底图：浅灰底 + 斜切线 + 半透明圆形色块/月相 + 十字星简笔。
 * 作为所有页面的底图（放在 Scaffold 之后、内容之下）。
 */
@Composable
fun GeometricBackground(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        // 1. 页面底色
        drawRect(LunaBackground)

        // 2. 主斜线：左上角 → 右下角（1px 浅灰）
        drawLine(
            color = LunaOutline,
            start = Offset(0f, 0f),
            end = Offset(w, h),
            strokeWidth = 1.dp.toPx(),
            cap = StrokeCap.Round
        )

        // 3. 次要平行斜线（细、更淡，制造切割层次）
        drawLine(
            color = LunaOutline.copy(alpha = 0.55f),
            start = Offset(0f, h * 0.32f),
            end = Offset(w * 0.42f, h),
            strokeWidth = 1.dp.toPx()
        )
        drawLine(
            color = LunaOutline.copy(alpha = 0.35f),
            start = Offset(w * 0.18f, 0f),
            end = Offset(w, h * 0.78f),
            strokeWidth = 1.dp.toPx()
        )

        // 4. 半透明圆形色块（浅蓝 30%）
        drawCircle(
            color = LunaSurfaceAlt.copy(alpha = 0.30f),
            radius = w * 0.28f,
            center = Offset(w * 0.88f, h * 0.12f)
        )
        drawCircle(
            color = LunaSurfaceAlt.copy(alpha = 0.22f),
            radius = w * 0.14f,
            center = Offset(w * 0.08f, h * 0.85f)
        )

        // 5. 月相（月牙）：半透明浅蓝圆，叠加一个底色圆偏移，切出月牙缺口
        val moonCenter = Offset(w * 0.16f, h * 0.16f)
        val moonRadius = w * 0.11f
        drawCircle(
            color = LunaSurfaceAlt.copy(alpha = 0.30f),
            radius = moonRadius,
            center = moonCenter
        )
        drawCircle(
            color = LunaBackground,
            radius = moonRadius * 0.92f,
            center = Offset(moonCenter.x + moonRadius * 0.45f, moonCenter.y - moonRadius * 0.18f)
        )

        // 6. 十字星简笔（右下角）
        drawCrossStar(
            center = Offset(w * 0.90f, h * 0.90f),
            size = w * 0.055f,
            color = LunaOutline
        )
        // 左上角小十字星
        drawCrossStar(
            center = Offset(w * 0.06f, h * 0.06f),
            size = w * 0.032f,
            color = LunaOutline.copy(alpha = 0.6f)
        )
    }
}

/** 十字星简笔：中心 + 四条延伸线（上/下/左/右）+ 两条短斜线，像星星的几何简写 */
private fun DrawScope.drawCrossStar(center: Offset, size: Float, color: Color) {
    val stroke = 1.dp.toPx()
    drawLine(color, center + Offset(0f, -size), center + Offset(0f, size), stroke, StrokeCap.Round)
    drawLine(color, center + Offset(-size, 0f), center + Offset(size, 0f), stroke, StrokeCap.Round)
    val diag = size * 0.5f
    drawLine(color, center + Offset(-diag, -diag), center + Offset(diag, diag), stroke, StrokeCap.Round)
    drawLine(color, center + Offset(-diag, diag), center + Offset(diag, -diag), stroke, StrokeCap.Round)
}
