package com.nexus.agent.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexus.agent.ui.theme.LunaAccent
import com.nexus.agent.ui.theme.LunaErrBg
import com.nexus.agent.ui.theme.LunaErrFg
import com.nexus.agent.ui.theme.LunaOnBackground
import com.nexus.agent.ui.theme.LunaOnPrimary
import com.nexus.agent.ui.theme.LunaOnSurfaceVariant
import com.nexus.agent.ui.theme.LunaOutline
import com.nexus.agent.ui.theme.LunaPrimary
import com.nexus.agent.ui.theme.LunaPressed
import com.nexus.agent.ui.theme.LunaSurface
import com.nexus.agent.ui.theme.LunaSurfaceAlt

/**
 * 极简几何风核心组件。
 *
 * 统一：无阴影、1px 线框、直角、按下变深（去水波纹）。
 */

/**
 * 主行动按钮：纯色底 + 1px 边框 + 直角，按下直接变深色（无水波纹）。
 *
 * @param danger 危险操作变体（浅红底 + 红字红框）
 */
@Composable
fun LunaButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
    content: @Composable RowScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    val bg = when {
        !enabled -> Color(0xFFE5E5E5)
        danger -> LunaErrBg
        pressed -> LunaPressed
        else -> LunaPrimary
    }
    val fg = when {
        !enabled -> LunaOnSurfaceVariant
        danger -> LunaErrFg
        else -> LunaOnPrimary
    }
    val borderColor = when {
        !enabled -> LunaOutline
        danger -> LunaErrFg
        else -> LunaPrimary
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(0.dp),
        color = bg,
        contentColor = fg,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick
                )
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}

/**
 * 卡片：纯白/浅色底 + 1px 浅灰边框 + 直角 + 无阴影，内部留白疏朗。
 */
@Composable
fun LunaCard(
    modifier: Modifier = Modifier,
    container: Color = LunaSurface,
    contentPadding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(0.dp),
        color = container,
        border = BorderStroke(1.dp, LunaOutline)
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            content = content
        )
    }
}

/**
 * 标题：超大粗体 + 下方两行极细几何线条装饰（极简海报排版）。
 */
@Composable
fun GeometricTitle(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null
) {
    Column(modifier = modifier) {
        Text(
            title,
            style = MaterialTheme.typography.headlineLarge,
            color = LunaOnBackground
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(30.dp)
                    .height(2.dp)
                    .background(LunaPrimary)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .width(14.dp)
                    .height(2.dp)
                    .background(LunaOutline)
            )
        }
        if (subtitle != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = LunaOnSurfaceVariant
            )
        }
    }
}

/**
 * 十字星装饰（简笔 +），用于卡片右上角点缀。
 */
@Composable
fun CrossStarDecoration(modifier: Modifier = Modifier, size: Dp = 16.dp, color: Color = LunaOutline) {
    Canvas(modifier = modifier.size(size)) {
        val c = Offset(size.toPx() / 2f, size.toPx() / 2f)
        val r = size.toPx() / 2f
        val stroke = 1.dp.toPx()
        drawLine(color, c + Offset(0f, -r), c + Offset(0f, r), stroke)
        drawLine(color, c + Offset(-r, 0f), c + Offset(r, 0f), stroke)
    }
}

/**
 * 底部导航栏：纯白底 + 顶部 1px 细线分割。
 *
 * 选中项：微光黄底 + 深空黑文字/图标；未选中：透明底 + 浅灰；
 * 按下：浅蓝纯色块（无水波纹）。
 *
 * @param tabs 图标与文字对（图标用 emoji 或文字均可）
 */
@Composable
fun LunaNavigationBar(
    selectedIndex: Int,
    tabs: List<Pair<String, String>>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        // 顶部 1px 细灰分割线
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(LunaOutline)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(LunaSurface)
        ) {
            tabs.forEachIndexed { i, (icon, label) ->
                val selected = selectedIndex == i
                val interactionSource = remember { MutableInteractionSource() }
                val pressed by interactionSource.collectIsPressedAsState()
                val bg = when {
                    selected -> LunaAccent
                    pressed -> LunaSurfaceAlt
                    else -> Color.Transparent
                }
                val fg = if (selected) LunaPrimary else LunaOnSurfaceVariant
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            role = Role.Tab,
                            onClick = { onSelect(i) }
                        )
                        .background(bg)
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        icon,
                        fontSize = 20.sp,
                        color = fg
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        label,
                        fontSize = 11.sp,
                        color = fg,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }
    }
}
