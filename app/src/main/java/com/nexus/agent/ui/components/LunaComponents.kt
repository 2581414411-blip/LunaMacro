package com.nexus.agent.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import com.nexus.agent.ui.theme.LunaPressed
import com.nexus.agent.ui.theme.LunaPrimary
import com.nexus.agent.ui.theme.LunaShadow
import com.nexus.agent.ui.theme.LunaSurface

/**
 * 微圆角 + 弥散阴影的组件库（0.7.0 视觉基调）。
 *
 * 统一：12~16dp 圆角、无 1px 硬线框、极淡投影分层、按下弹簧缩放到 0.96。
 */

/** 按下缩放比例（全局统一手感） */
const val PRESS_SCALE = 0.96f

/**
 * 弹簧按压缩放修饰符。
 *
 * 用法：把组件内部的 `MutableInteractionSource` 传进来，即可获得 0.96 缩放的弹性反馈。
 * 例：`OutlinedButton(onClick = ..., interactionSource = src, modifier = Modifier.springPressScale(src))`
 *
 * 说明：Material3 原生按钮不支持全局 hook，需要逐处传入 interactionSource。
 */
fun Modifier.springPressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = PRESS_SCALE
): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "springPressScale"
    )
    this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * 主行动按钮：12dp 圆角 + 弥散投影 + 弹簧按压缩放（无水波纹）。
 *
 * @param danger 危险操作变体（浅红底 + 红字）
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
    val shape = RoundedCornerShape(12.dp)

    val bg = when {
        !enabled -> Color(0xFFE8E8E8)
        danger -> LunaErrBg
        pressed -> LunaPressed
        else -> LunaPrimary
    }
    val fg = when {
        !enabled -> LunaOnSurfaceVariant
        danger -> LunaErrFg
        else -> LunaOnPrimary
    }

    Surface(
        modifier = modifier
            .springPressScale(interactionSource)
            .shadow(
                elevation = if (pressed) 1.dp else 5.dp,
                shape = shape,
                ambientColor = LunaShadow,
                spotColor = LunaShadow
            ),
        shape = shape,
        color = bg,
        contentColor = fg
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
                .padding(horizontal = 18.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}

/**
 * 卡片：16dp 圆角 + 极淡弥散投影，无描边，内容留白疏朗。
 */
@Composable
fun LunaCard(
    modifier: Modifier = Modifier,
    container: Color = LunaSurface,
    contentPadding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    Surface(
        modifier = modifier.shadow(
            elevation = 8.dp,
            shape = shape,
            ambientColor = LunaShadow,
            spotColor = LunaShadow
        ),
        shape = shape,
        color = container
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            content = content
        )
    }
}

/**
 * 可点击卡片：在 [LunaCard] 基础上加弹簧按压缩放（整卡作为入口时用）。
 */@Composable
fun LunaClickableCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = LunaSurface,
    contentPadding: Dp = 16.dp,
    enabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(16.dp)
    Surface(
        modifier = modifier
            .springPressScale(interactionSource, pressedScale = 0.975f)
            .shadow(
                elevation = 8.dp,
                shape = shape,
                ambientColor = LunaShadow,
                spotColor = LunaShadow
            ),
        shape = shape,
        color = container
    ) {
        Column(
            modifier = Modifier
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick
                )
                .padding(contentPadding),
            content = content
        )
    }
}

/**
 * 标题：超大粗体 + 下方两行极细圆角线条装饰（轻盈版，替代原来的直角切割线）。
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
                    .clip(RoundedCornerShape(2.dp))
                    .background(LunaPrimary)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .width(14.dp)
                    .height(2.dp)
                    .clip(RoundedCornerShape(2.dp))
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
 * 选中项：微光黄圆角胶囊底 + 深空黑文字/图标，按下弹簧缩放；未选中：透明底 + 浅灰。
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
                .background(LunaOutline.copy(alpha = 0.6f))
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(LunaSurface)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEachIndexed { i, (icon, label) ->
                val selected = selectedIndex == i
                val interactionSource = remember { MutableInteractionSource() }
                val pressed by interactionSource.collectIsPressedAsState()
                val scale by animateFloatAsState(
                    targetValue = if (pressed) 0.92f else 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    ),
                    label = "navScale"
                )
                val fg = if (selected) LunaPrimary else LunaOnSurfaceVariant
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        }
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (selected) LunaAccent else Color.Transparent)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            role = Role.Tab,
                            onClick = { onSelect(i) }
                        )
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        icon,
                        fontSize = 19.sp,
                        color = fg
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        label,
                        fontSize = 10.sp,
                        color = fg,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }
    }
}

/**
 * 文字按钮：Material3 TextButton + 弹簧按压缩放（推荐用于对话框操作）。
 */
@Composable
fun LunaTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    val src = remember { MutableInteractionSource() }
    TextButton(
        onClick = onClick,
        modifier = modifier.springPressScale(src),
        enabled = enabled,
        interactionSource = src,
        content = content
    )
}

/**
 * 描边按钮：Material3 OutlinedButton + 12dp 圆角 + 弹簧按压缩放。
 */
@Composable
fun LunaOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    val src = remember { MutableInteractionSource() }
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.springPressScale(src),
        enabled = enabled,
        interactionSource = src,
        shape = RoundedCornerShape(12.dp),
        content = content
    )
}
