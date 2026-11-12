package com.nexus.agent.ui.room

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.nexus.agent.R
import com.nexus.agent.copilot.CopilotConfig
import com.nexus.agent.copilot.PersonaEngine
import com.nexus.agent.data.db.RoomItem
import com.nexus.agent.data.db.RoomTheme
import com.nexus.agent.persona.PadRuntime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime
import kotlin.math.min

/**
 * 赛博房间 —— 全屏沉浸页（0.9.17 批次 B）。
 *
 * ## 她只有一个灵魂，两个视窗
 *
 * 对话框和房间看的是**同一份**状态：`pad_state` 里的 PAD、`persona_profile` 里的
 * 好感度。房间不解锁任何新数据、不另存一份心情 —— 它只是把「情绪」翻成
 * 「光」和「身体」。
 *
 * 所以这里读的是 [PadRuntime.current]，不是新开一个房间专用状态：
 * 对话框里被凶了一句，点门进来就是关灯、躺平；在屋里坐二十分钟不说话，
 * 快变量按 20 分钟半衰期自己淡回去，房间会慢慢亮起来 —— 那是「她自己缓过来了」。
 *
 * ## 三层结构（对应验收要求）
 *
 * ```
 * Dialog（全屏、无状态栏、返回键退出）
 *  └─ Box 黑底（兜住窗口背景，避免圆角漏白）
 *      ├─ Layer 0  Canvas  静态层：墙 / 地板 / 窗 / 家具  ← 一个 Canvas 画完
 *      ├─ Layer 1  Box     光影层：黑遮罩(亮度) + 环境色(色温)
 *      ├─ Layer 2  Box     角色层：三视图立绘 + 影子（独立变换，自己吃一份光）
 *      └─ Layer 3  Row     抬头显示（名字 / 状态 / 关门）
 * ```
 *
 * ## 为什么静态层只用一个 Canvas
 *
 * 七件家具如果各是一个 Composable，光组合就要跑七遍、每帧七次布局。
 * 画在同一个 `DrawScope` 里，`onDraw` 一次解决；而且家具之间本来就要
 * 互相遮挡（沙发压住地毯一角），分图层反而要手写 zIndex。
 *
 * 角色单独一个 Box 的理由不是性能，是**变换**：她要呼吸、要浮、要躺平，
 * 这些都要 `graphicsLayer` 独立作用在她身上，不能带着墙一起抖。
 *
 * ## 0.9.18：占位鱼换成正稿
 *
 * 批次 B 里她是一条用 Canvas 现画的胖鱼 —— 那不是偷懒，是当时没有立绘，
 * 与其拿几何色块拼一个「人」，不如画一个能表达情绪的形状。现在有了三视图
 * （正面 / 侧面 / 背面），朝向本身就成了表情：**转过身去 > 画一张苦脸**。
 * 三张图放在 `res/drawable-nodpi/`，共 335 KB。
 */
@Composable
fun RoomScreen(onExit: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current

    Dialog(
        onDismissRequest = onExit,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        // 沉浸：藏掉状态栏与导航栏，返回键退出。
        // 恢复放在 onDispose —— 用户按 home 键、系统回收，也必须把系统栏还回去，
        // 否则退出房间后整个 App 都变全屏，用户会以为界面坏了。
        DisposableEffect(Unit) {
            val window = view.context.findActivity()?.window
            val controller = window?.let { WindowCompat.getInsetsController(it, view) }
            controller?.apply {
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
            onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
        }
        BackHandler(enabled = true) { onExit() }

        var theme by remember { mutableStateOf(RoomTheme()) }
        var items by remember { mutableStateOf<List<RoomItem>>(emptyList()) }
        var pad by remember { mutableStateOf(PadRuntime.current()) }
        var affinity by remember { mutableStateOf(PersonaEngine.profileSnapshot().affinity) }

        // 进屋铺一次默认家具（幂等；失败最多是空屋子）
        LaunchedEffect(Unit) { RoomStore.ensureSeeded(context) }

        // 实时数据：家具/主题一变，画面当场变（下一批的自然语言装修就靠这条）
        val themeFlow = remember { RoomStore.theme(context) }
        val itemsFlow = remember { RoomStore.items(context) }
        val themeRow by themeFlow.collectAsState(initial = null)
        val itemRows by itemsFlow.collectAsState(initial = emptyList())
        LaunchedEffect(themeRow) { themeRow?.let { theme = it } }
        LaunchedEffect(itemRows) { items = itemRows }

        // 情绪每 2 秒取一次。**必须轮询**而不是只在打开时算一次：
        // current() 把「距上次推进过去了多久」的衰减算进去，坐在屋里发呆时
        // 光线要跟着一点点变回来，那才是活的。
        LaunchedEffect(Unit) {
            while (true) {
                delay(2_000)
                pad = PadRuntime.current()
                affinity = PersonaEngine.profileSnapshot().affinity
            }
        }

        // 房间看的是**此刻**（快变量），不是底色（慢变量）：
        // 底色决定她说话的语气，此刻决定她现在躺在哪儿。
        val mood = remember(pad, affinity) { RoomMoodMapper.of(pad.quick, affinity) }

        // ---- 真实时间（0.9.20 批次 D）：她的作息跟着现实时间走 ----
        //
        // 轮询而不是算一次：有人把房间开在桌上从中午看到傍晚，`23:00` 那一刻
        // 她该去睡觉、屋子该暗下来。只在「分钟变了」时更新 —— 每秒一个新
        // LocalTime 会让下面所有 remember 每帧失效。
        var now by remember { mutableStateOf(LocalTime.now()) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(1_000)
                val t = LocalTime.now()
                if (t.minute != now.minute || t.hour != now.hour) now = t
            }
        }
        val routine = remember(now) { TimeRoutineManager.routineOf(now) }

        // 行为状态机 + 走动 + 点她说话
        val life = rememberRoomLife(context, items, routine, now)

        // ---- 手势：双指缩放 / 单指平移（0.9.20）----
        //
        // 只是**视角**，不写库：房间的坐标仍然是 0~1 的舞台比例，
        // 缩放发生在渲染层的 graphicsLayer 上。所以退出去再进来，
        // 房间不会记住「上次放大到 2.3 倍卡在墙角」—— 那不是设置，那是手滑。
        var zoom by remember { mutableStateOf(1f) }
        var pan by remember { mutableStateOf(Offset.Zero) }

        // ---- 装修（0.9.19）：屋里直接说，不用回聊天框 ----
        var draftText by remember { mutableStateOf("") }
        var decorBusy by remember { mutableStateOf(false) }
        var hint by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()

        // 屋里发生的事（摆放 / 换墙色 / 撤走）在这里冒一句短提示。
        // 3 秒后自己消失 —— 提示是用来确认「她听见了」，不是用来读的。
        LaunchedEffect(Unit) {
            RoomStore.events.collect { msg ->
                hint = msg
            }
        }
        LaunchedEffect(hint) {
            if (hint != null) {
                delay(3_000)
                hint = null
            }
        }

        fun submitDecor() {
            val t = draftText.trim()
            if (t.isEmpty() || decorBusy) return
            draftText = ""
            decorBusy = true
            scope.launch {
                try {
                    RoomStore.decorate(context, t)
                } finally {
                    decorBusy = false
                }
            }
        }

        // ---- 所有过渡都走动画，禁止硬切 ----
        //
        // 亮度取「情绪亮度」和「作息亮度上限」的**较小者**：
        // 凌晨两点她就算 PAD 拉满，屋子也不该亮着 —— 那是作息，不是心情。
        // 反过来中午 .lightCap 是 1.0，情绪想多暗都行（午睡）。
        val brightness = min(mood.brightness, routine.lightCap)
        val scrim by animateFloatAsState(
            // 上限 0.72：深夜 0.34 亮度要 0.66 的遮罩，比旧上限 0.60 更黑一点点，
            // 但她还在（有 Zzz、有气泡）。再黑用户会以为屏幕坏了。
            targetValue = (1f - brightness).coerceIn(0f, MAX_SCRIM),
            animationSpec = tween(durationMillis = 1_400),
            label = "roomScrim"
        )
        val ambient by animateColorAsState(
            targetValue = Color(mood.ambientArgb).copy(alpha = mood.tintAlpha),
            animationSpec = tween(durationMillis = 1_400),
            label = "roomAmbient"
        )
        val distance by animateFloatAsState(
            targetValue = mood.distance,
            animationSpec = tween(durationMillis = 1_200),
            label = "roomDistance"
        )

        // 躺下有两个来源：她困了（PAD）／ 该睡了（作息）。
        // 后者是硬约束 —— 深夜就是躺平，不管 PAD 说她多清醒。
        val wantLie = when {
            life.behavior == RoomBehavior.SLEEPING -> 1f
            mood.pose == RoomPose.SLEEPY -> 1f
            else -> 0f
        }
        // 姿态用「进度」而不是枚举硬切：躺平 0→1、悬浮 0→1，
        // 中间那 0.37 就是她慢慢倒下去的那一瞬间。
        val lie by animateFloatAsState(
            targetValue = wantLie,
            animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            label = "roomLie"
        )
        val hover by animateFloatAsState(
            // 睡着的人不会飘起来
            targetValue = if (mood.pose == RoomPose.EXCITED && life.behavior != RoomBehavior.SLEEPING) 1f else 0f,
            animationSpec = spring(dampingRatio = 0.55f, stiffness = 120f),
            label = "roomHover"
        )

        // 坐着没有专门的立绘，只能「压低一点、缩一点」近似 —— 比让她站着说
        // 「我在午休」诚实。
        val sit by animateFloatAsState(
            targetValue = if (life.behavior == RoomBehavior.SITTING) 1f else 0f,
            animationSpec = tween(durationMillis = 600),
            label = "roomSit"
        )

        val infinite = rememberInfiniteTransition(label = "room")
        val breath by infinite.animateFloat(
            initialValue = 0.995f,
            targetValue = 1.022f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2_600, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "roomBreath"
        )
        val floatPhase by infinite.animateFloat(
            initialValue = -1f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 3_200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "roomFloat"
        )

        val herName = remember { CopilotConfig.assistantName(context) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF14100C))
        ) {
            // ---- 场景层（0.9.20）：墙 / 家具 / 她，一起缩放平移 ----
            //
            // 缩放只发生在这一层，抬头显示和输入条不动 —— 放大看她的脸时，
            // 关门的 ✕ 不该跟着飘到屏幕外。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, panChange, zoomChange, _ ->
                            val next = (zoom * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
                            zoom = next
                            // 只有放大之后才有「画外」可拖：1 倍时边界是 0，
                            // 硬拖会拖出一片空地板。
                            val limitX = size.width * (next - 1f) / 2f
                            val limitY = size.height * (next - 1f) / 2f
                            pan = Offset(
                                (pan.x + panChange.x).coerceIn(-limitX, limitX),
                                (pan.y + panChange.y).coerceIn(-limitY, limitY)
                            )
                        }
                    }
                    .pointerInput(Unit) {
                        // 双击复位。缩进去又拖到墙角之后，用户没有别的路回来 ——
                        // 界面里没有「还原」按钮，而这条手势不需要说明书。
                        detectTapGestures(
                            onDoubleTap = {
                                zoom = 1f
                                pan = Offset.Zero
                            }
                        )
                    }
                    .graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                        translationX = pan.x
                        translationY = pan.y
                    }
            ) {
                // ---- Layer 0：静态层（墙 + 地板 + 窗 + 家具），一个 Canvas 画完 ----
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawRoomShell(theme)
                    // 舞台：横向坐标的基准。宽屏（折叠屏）两侧多出来的是墙，不是黑边
                    val stage = RoomGeometry.stageOf(size.width, size.height)
                    if (theme.window) drawWindow(theme, stage)
                    RoomGeometry.ordered(items).forEach { drawFurniture(it, stage) }
                }

                // ---- Layer 1：光影层 ----
                // 先压黑（亮度是乘性的，alpha = 1 − brightness），再叠色温。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = scrim))
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(ambient)
                )

                // ---- Layer 2：角色层 ----
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val density = LocalDensity.current
                    val wPx = with(density) { maxWidth.toPx() }
                    val hPx = with(density) { maxHeight.toPx() }
                    val stage = remember(wPx, hPx) { RoomGeometry.stageOf(wPx, hPx) }

                    // 画框比例固定（三张图共用一个框），朝向切换时屋子不会跟着跳。
                    val frameH = stage.height * RoomAvatar.FRAME_HEIGHT_FRACTION
                    val frameW = frameH * RoomAvatar.FRAME_ASPECT
                    // 脚底跟家具共用同一套地板换算（唯一出处：RoomGeometry.HORIZON），
                    // 但 x、y 现在由行为引擎给 —— 她会走动，不再钉在正中。
                    val footY = stage.height * (
                        RoomGeometry.HORIZON + (1f - RoomGeometry.HORIZON) * life.spot.y
                        )
                    val cx = stage.px(life.spot.x)

                    // 走动用侧面立绘：正面平移看着像贴纸在滑，侧身才像迈步子。
                    // 朝向靠 X 轴镜像翻转，不需要第四张图。
                    val view = when {
                        life.behavior == RoomBehavior.SLEEPING -> RoomView.SIDE
                        life.behavior == RoomBehavior.WALKING -> RoomView.SIDE
                        else -> RoomAvatar.viewOf(mood.pose)
                    }
                    val tilt = RoomAvatar.tiltOf(lie)
                    val lieScale = RoomAvatar.lieScaleOf(lie)
                    val desat = RoomAvatar.desaturateOf(mood.pose)

                    // 立绘在光影层之上（不然会被墙面挡住），所以要自己吃一份光：
                    // 房间暗到 40% 时她降到 67%，色温同频一点点，消沉时去饱和。
                    val figureBrightness = RoomAvatar.figureBrightnessOf(scrim)
                    val figureFilter = remember(figureBrightness, mood.warmth, desat) {
                        ColorFilter.colorMatrix(
                            ColorMatrix(
                                RoomAvatar.colorMatrixOf(figureBrightness, mood.warmth, desat)
                            )
                        )
                    }

                    // 影子不跟着旋转，永远摊在地板上。悬浮时缩小变淡，躺平后拉长。
                    val shadow = RoomAvatar.shadowOf(hover, lie)
                    Canvas(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .size(
                                width = with(density) { (frameW * shadow.widthScale).toDp() },
                                height = 22.dp
                            )
                            .graphicsLayer {
                                translationX = cx - size.width / 2f
                                translationY = footY - 16.dp.toPx()
                            }
                    ) {
                        drawOval(
                            color = Color.Black.copy(alpha = shadow.alpha),
                            topLeft = Offset(0f, size.height * 0.30f),
                            size = Size(size.width, size.height * 0.70f)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .size(
                                width = with(density) { frameW.toDp() },
                                height = with(density) { frameH.toDp() }
                            )
                            .pointerInput(Unit) {
                                detectTapGestures { life.onTap() }
                            }
                            .graphicsLayer {
                                // 呼吸只在「站着」时算：躺着喘气和飘着喘气都不对劲
                                val breathe = if (mood.pose == RoomPose.IDLE && life.behavior != RoomBehavior.WALKING) breath else 1f
                                // 支点放在脚底：她是绕脚倒下去的，不是原地转圈
                                transformOrigin = TransformOrigin(0.5f, 1f)
                                // 走路时小幅上下颠，坐姿压低一点（没有坐姿立绘，只能近似）
                                val walkBob = if (life.behavior == RoomBehavior.WALKING) {
                                    floatPhase * 2.5.dp.toPx()
                                } else {
                                    0f
                                }
                                val sitShrink = 1f - SIT_SHRINK * sit
                                // 朝左走时沿 X 翻转。立绘本身不分左右，翻转是最便宜的"转身"
                                val mirror = if (life.facing < 0) -1f else 1f
                                // 躺着的时候身体是横的：床在 x≈0.80，半个身长会伸到屏幕外。
                                // 按「她离最近的边有多远」收一点，宁可她在远处小一号，
                                // 也不要被裁掉半个头。
                                val halfExtent = (frameH * RoomAvatar.LIE_HALF_EXTENT * lie) / stage.width
                                val edgeFit = RoomAvatar.edgeFitOf(life.spot.x, halfExtent)
                                scaleX = distance * breathe * lieScale * sitShrink * mirror * edgeFit
                                scaleY = distance * breathe * lieScale * sitShrink * edgeFit
                                rotationZ = tilt
                                // 雀跃：飘到房间中间（往上抬）；躺平：推回中央 + 抬半个体厚
                                translationX = (cx - frameW / 2f) +
                                    frameH * RoomAvatar.LIE_SLIDE * lie
                                translationY = (footY - frameH) + walkBob +
                                    sit * frameH * SIT_DROP -
                                    frameH * 0.28f * hover +
                                    floatPhase * 9.dp.toPx() * hover -
                                    frameW * RoomAvatar.LIE_LIFT * lieScale * lie
                            }
                    ) {
                        Crossfade(
                            targetState = view,
                            animationSpec = tween(durationMillis = 520),
                            label = "roomView"
                        ) { v ->
                            Image(
                                painter = painterResource(v.drawableRes()),
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                colorFilter = figureFilter,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }

                    // 困了 / 该睡了才冒 Zzz。**不能放进上面那个框**：那个框绕脚底转了 88°，
                    // 右上角会被甩到屏幕外。所以单独定位，站姿在头顶右上方，
                    // 躺下后移到画面左侧（她的头在那儿）。
                    if (mood.showZzz || life.behavior == RoomBehavior.SLEEPING) {
                        val zzz = RoomAvatar.zzzAnchorOf(lie)
                        Text(
                            text = "Zzz",
                            color = Color(0xFFEDE6DA),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .graphicsLayer {
                                    translationX = (cx - frameW / 2f) + frameW * zzz.xRatio
                                    translationY = (footY - frameH) + frameH * zzz.yRatio +
                                        floatPhase * 4.dp.toPx()
                                }
                                .alpha(lie)
                        )
                    }

                    // ---- 头顶气泡（0.9.20）：点她之后她说的那一句 ----
                    //
                    // 位置跟人走：她走到窗边，气泡也在窗边。挂在屏幕固定位置的话，
                    // 屋里就不存在「她在跟你说话」这件事了。
                    val say = when {
                        life.thinking -> "…"
                        else -> life.bubble
                    }
                    if (say != null) {
                        Text(
                            text = say,
                            color = Color(0xFF2B2420),
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .widthIn(max = 220.dp)
                                .graphicsLayer {
                                    translationX = cx - size.width / 2f
                                    translationY = (footY - frameH) - size.height - 10.dp.toPx()
                                }
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xF2EDE6DA))
                                .padding(horizontal = 11.dp, vertical = 7.dp)
                        )
                    }
                }
            }

            // ---- Layer 3：抬头显示 ----
            RoomHud(
                name = herName,
                moodLabel = mood.label,
                poseLabel = TimeRoutineManager.sceneZh(routine, life.behavior),
                onExit = onExit
            )

            // ---- Layer 4：装修输入（贴底，跟着键盘走）----
            RoomDecorBar(
                itemCount = items.size,
                hint = hint,
                busy = decorBusy,
                draft = draftText,
                onDraftChange = { draftText = it.take(40) },
                onSubmit = { submitDecor() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .imePadding()
            )
        }
    }
}

/**
 * 名字 / 状态 / 关门。
 *
 * 为什么状态要写出来：光影变化用户是能感觉到，但**说不清**。
 * 摆一行「此刻：犯困」把感觉和原因对上，用户才知道「哦她是真困了」，
 * 而不是「这房间怎么突然黑了」。
 */
@Composable
private fun RoomHud(
    name: String,
    moodLabel: String,
    poseLabel: String,
    onExit: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0x6612100C))
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = name,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "$moodLabel · $poseLabel",
                    color = Color(0xCCFFFFFF),
                    fontSize = 12.sp
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Color(0x6612100C))
                    .clickable { onExit() },
                contentAlignment = Alignment.Center
            ) {
                Text("✕", color = Color.White, fontSize = 16.sp)
            }
        }
    }
}

/**
 * 屋里的装修输入条（0.9.19 批次 C）。
 *
 * ## 为什么房间里要再来一个输入框
 *
 * 对话页当然也能说「加个红沙发」，但那是**离开现场**指挥装修：说一句、退出去、
 * 再进屋看一眼、不满意再回去说。房间里有个输入框，改完抬眼就能看见结果 ——
 * 「说」和「看」在同一块屏幕上，这才是装修。
 *
 * ## 为什么是 BasicTextField 而不是 Material 的 TextField
 *
 * 房间是深色沉浸画面，Material 的输入框自带一层 surface 底色和下划线，
 * 摆在屋里像嵌了一块系统控件。这里只要一条半透明胶囊，跟抬头显示同一个调子。
 */
@Composable
private fun RoomDecorBar(
    itemCount: Int,
    hint: String?,
    busy: Boolean,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    fun send() {
        if (draft.isBlank() || busy) return
        onSubmit()
        keyboard?.hide()
    }

    Column(
        modifier = modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 她正在摆放 / 已经摆好 —— 一句短提示，3 秒自己走
        Crossfade(targetState = hint, animationSpec = tween(220), label = "roomHint") { msg ->
            if (msg != null) {
                Text(
                    text = if (busy) "她正在摆放…" else msg,
                    color = Color(0xFFEDE6DA),
                    fontSize = 12.sp,
                    modifier = Modifier
                        .padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xB314100C))
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                )
            } else {
                Spacer(modifier = Modifier.height(0.dp))
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0x9914100C))
                .clickable { focusRequester.requestFocus() }
                .padding(start = 14.dp, end = 5.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.weight(1f)) {
                if (draft.isEmpty()) {
                    Text(
                        text = "跟她说怎么改：加个红沙发 / 墙换深蓝 / 床挪到窗边",
                        color = Color(0x66FFFFFF),
                        fontSize = 12.sp,
                        maxLines = 1
                    )
                }
                BasicTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
                    cursorBrush = SolidColor(Color(0xFFEDE6DA)),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            // 件数塞在这一行里，不单独占一行 —— 底部每多 20dp，她的脚背就多被压一截
            Text(
                text = "$itemCount/${RoomDecorator.MAX_ITEMS}",
                color = Color(0x59FFFFFF),
                fontSize = 10.sp
            )
            Spacer(modifier = Modifier.width(6.dp))
            // 右侧小圆钮：她正在动手时显示「…」，避免连点写出两批家具
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(if (busy) Color(0x33FFFFFF) else Color(0xCCEDE6DA))
                    .clickable(enabled = !busy) { send() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (busy) "…" else "→",
                    color = if (busy) Color(0x99FFFFFF) else Color(0xFF1A1A1A),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

// ==================================================================== 静态层

/** 室内空壳：墙 + 地板 + 踢脚线 + 地板透视纹 */
private fun DrawScope.drawRoomShell(theme: RoomTheme) {
    val horizon = size.height * RoomGeometry.HORIZON
    val wall = Color(theme.wall)
    val floor = Color(theme.floor)

    // 墙：上亮下暗（越靠近地板越暗，暗示光从上方来）
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(wall.lighten(0.06f), wall, wall.darken(0.10f)),
            startY = 0f,
            endY = horizon
        ),
        size = Size(size.width, horizon)
    )

    // 地板：贴墙处暗、靠近屏幕处亮（透视 + 光衰减）
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(floor.darken(0.16f), floor, floor.lighten(0.04f)),
            startY = horizon,
            endY = size.height
        ),
        topLeft = Offset(0f, horizon),
        size = Size(size.width, size.height - horizon)
    )

    // 地板透视线：三条等距横线，够了。真做网格会像 CAD 图，不是她的屋子。
    val band = size.height - horizon
    for (i in 1..3) {
        val y = horizon + band * (i * 0.26f)
        drawLine(
            color = Color.Black.copy(alpha = 0.05f),
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 1.dp.toPx()
        )
    }

    // 踢脚线
    drawRect(
        color = Color.Black.copy(alpha = 0.12f),
        topLeft = Offset(0f, horizon - 6.dp.toPx()),
        size = Size(size.width, 6.dp.toPx())
    )
}

/** 墙上的窗：只做视觉锚点，不参与光照计算（第一版） */
private fun DrawScope.drawWindow(theme: RoomTheme, stage: RoomGeometry.Stage) {
    // 窗跟着舞台走：宽屏上不跟着走的话，窗户会飘到屏幕边角，跟墙不是一个空间
    val w = stage.width * 0.26f
    val h = stage.height * 0.20f
    val left = stage.left + stage.width * 0.62f
    val top = stage.height * 0.10f

    // 窗外天光：夜里（deep wall）就暗一点，白天就亮
    val night = Color(theme.wall).luminanceApprox() < 0.35f
    val sky = if (night) Color(0xFF2B3A55) else Color(0xFFC6DCF0)

    drawRoundRect(
        color = Color.White.copy(alpha = 0.55f),
        topLeft = Offset(left - 4.dp.toPx(), top - 4.dp.toPx()),
        size = Size(w + 8.dp.toPx(), h + 8.dp.toPx()),
        cornerRadius = CornerRadius(4.dp.toPx())
    )
    drawRect(color = sky, topLeft = Offset(left, top), size = Size(w, h))
    // 十字窗框
    drawLine(
        Color.White.copy(alpha = 0.75f),
        Offset(left + w / 2, top),
        Offset(left + w / 2, top + h),
        strokeWidth = 2.dp.toPx()
    )
    drawLine(
        Color.White.copy(alpha = 0.75f),
        Offset(left, top + h / 2),
        Offset(left + w, top + h / 2),
        strokeWidth = 2.dp.toPx()
    )
}

// ==================================================================== 家具

/**
 * 一件家具。
 *
 * 坐标全部由 [RoomGeometry.anchorOf] 换算，尺寸按「单位」u 缩放 ——
 * u 只跟屏幕短边有关，所以在手机上地毯不会比床还大。
 *
 * 未知 kind 落到 [drawUnknown]：一个带备注的方盒子。**不省略**，
 * 因为省略会让人以为家具丢了（数据还在，只是还不会画）。
 */
private fun DrawScope.drawFurniture(item: RoomItem, stage: RoomGeometry.Stage) {
    // 位置由 RoomLayout 统一算（语义槽位 → 0.9.19 起，家具也可以来自装修）
    val p = RoomLayout.place(item.kind, item.slot)
    val floorTop = stage.height * RoomGeometry.HORIZON
    val cx = stage.px(p.x)
    val baseY = if (p.mount == RoomLayout.Mount.WALL) {
        stage.height * p.y
    } else {
        floorTop + (stage.height - floorTop) * p.y
    }
    // finalScaleOf 而不是 p.scale：家具自己记着的 scale（地毯 1.15 倍）也要算进来。
    // u 跟**舞台宽度**走（0.9.20）：跟整屏宽度走的话，折叠屏上整间屋子横向散架
    val u = stage.unit * RoomLayout.finalScaleOf(item)
    val tint = Color(item.tint)
    // 同一件家具左右镜像：只影响「靠哪边」这类细节，第一版只用于沙发扶手
    val dir = if (item.flip) -1f else 1f

    // 接触阴影：没有它，家具会像贴纸一样浮在地板上。
    // 墙上挂着的东西不投影到地板 —— 那会变成一块悬空的影子。
    if (item.kind != RoomItem.KIND_RUG && p.mount == RoomLayout.Mount.FLOOR) {
        drawOval(
            color = Color.Black.copy(alpha = 0.10f),
            topLeft = Offset(cx - u * 1.2f, baseY - u * 0.16f),
            size = Size(u * 2.4f, u * 0.34f)
        )
    }

    when (item.kind) {
        RoomItem.KIND_SOFA -> drawSofa(cx, baseY, u, tint, dir)
        RoomItem.KIND_BED -> drawBed(cx, baseY, u, tint)
        RoomItem.KIND_DESK -> drawDesk(cx, baseY, u, tint)
        RoomItem.KIND_LAMP -> drawLamp(cx, baseY, u, tint)
        RoomItem.KIND_SHELF -> drawShelf(cx, baseY, u, tint)
        RoomItem.KIND_PLANT -> drawPlant(cx, baseY, u, tint)
        RoomItem.KIND_RUG -> drawRug(cx, baseY, u, tint)
        RoomItem.KIND_POSTER -> drawPoster(cx, baseY, u, tint)
        else -> drawUnknown(cx, baseY, u, tint)
    }
}

private fun DrawScope.drawSofa(cx: Float, baseY: Float, u: Float, tint: Color, dir: Float) {
    // 靠背
    drawRoundRect(
        color = tint.darken(0.10f),
        topLeft = Offset(cx - u * 1.1f, baseY - u * 1.15f),
        size = Size(u * 2.2f, u * 0.85f),
        cornerRadius = CornerRadius(u * 0.18f)
    )
    // 坐垫
    drawRoundRect(
        color = tint,
        topLeft = Offset(cx - u * 1.05f, baseY - u * 0.62f),
        size = Size(u * 2.1f, u * 0.42f),
        cornerRadius = CornerRadius(u * 0.12f)
    )
    // 两侧扶手
    drawRoundRect(
        color = tint.lighten(0.06f),
        topLeft = Offset(cx - u * 1.22f, baseY - u * 0.78f),
        size = Size(u * 0.28f, u * 0.62f),
        cornerRadius = CornerRadius(u * 0.10f)
    )
    drawRoundRect(
        color = tint.lighten(0.06f),
        topLeft = Offset(cx + u * (0.94f * dir), baseY - u * 0.78f),
        size = Size(u * 0.28f, u * 0.62f),
        cornerRadius = CornerRadius(u * 0.10f)
    )
    // 一条抱枕斜纹，避免整块色块太死
    drawRoundRect(
        color = Color.White.copy(alpha = 0.35f),
        topLeft = Offset(cx - u * 0.72f, baseY - u * 1.02f),
        size = Size(u * 0.42f, u * 0.30f),
        cornerRadius = CornerRadius(u * 0.08f)
    )
}

private fun DrawScope.drawBed(cx: Float, baseY: Float, u: Float, tint: Color) {
    // 床架
    drawRoundRect(
        color = tint.darken(0.14f),
        topLeft = Offset(cx - u * 1.25f, baseY - u * 0.62f),
        size = Size(u * 2.5f, u * 0.55f),
        cornerRadius = CornerRadius(u * 0.10f)
    )
    // 被面
    drawRoundRect(
        color = tint,
        topLeft = Offset(cx - u * 1.15f, baseY - u * 0.78f),
        size = Size(u * 2.1f, u * 0.32f),
        cornerRadius = CornerRadius(u * 0.10f)
    )
    // 枕头
    drawRoundRect(
        color = Color.White.copy(alpha = 0.82f),
        topLeft = Offset(cx - u * 1.02f, baseY - u * 0.95f),
        size = Size(u * 0.70f, u * 0.26f),
        cornerRadius = CornerRadius(u * 0.12f)
    )
    // 床头板
    drawRoundRect(
        color = tint.darken(0.24f),
        topLeft = Offset(cx - u * 1.30f, baseY - u * 1.30f),
        size = Size(u * 2.6f, u * 0.60f),
        cornerRadius = CornerRadius(u * 0.12f)
    )
}

private fun DrawScope.drawDesk(cx: Float, baseY: Float, u: Float, tint: Color) {
    // 两条腿
    drawRect(
        color = tint.darken(0.22f),
        topLeft = Offset(cx - u * 0.90f, baseY - u * 0.85f),
        size = Size(u * 0.14f, u * 0.85f)
    )
    drawRect(
        color = tint.darken(0.22f),
        topLeft = Offset(cx + u * 0.76f, baseY - u * 0.85f),
        size = Size(u * 0.14f, u * 0.85f)
    )
    // 台面
    drawRoundRect(
        color = tint,
        topLeft = Offset(cx - u * 1.05f, baseY - u * 1.02f),
        size = Size(u * 2.1f, u * 0.20f),
        cornerRadius = CornerRadius(u * 0.06f)
    )
    // 桌上摊着的一本书
    drawRoundRect(
        color = Color(0xFFEDE6DA),
        topLeft = Offset(cx - u * 0.10f, baseY - u * 1.12f),
        size = Size(u * 0.52f, u * 0.12f),
        cornerRadius = CornerRadius(u * 0.03f)
    )
}

private fun DrawScope.drawLamp(cx: Float, baseY: Float, u: Float, tint: Color) {
    // 灯杆
    drawRect(
        color = Color(0xFF6E6154),
        topLeft = Offset(cx - u * 0.045f, baseY - u * 1.95f),
        size = Size(u * 0.09f, u * 1.85f)
    )
    // 灯座
    drawOval(
        color = Color(0xFF6E6154),
        topLeft = Offset(cx - u * 0.34f, baseY - u * 0.16f),
        size = Size(u * 0.68f, u * 0.20f)
    )
    // 灯罩
    val shade = Path().apply {
        moveTo(cx - u * 0.52f, baseY - u * 1.78f)
        lineTo(cx + u * 0.52f, baseY - u * 1.78f)
        lineTo(cx + u * 0.34f, baseY - u * 2.18f)
        lineTo(cx - u * 0.34f, baseY - u * 2.18f)
        close()
    }
    drawPath(shade, tint)
    // 灯下的光晕：这是房间里唯一真的「发光」的东西
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFFFFE0A8).copy(alpha = 0.30f), Color.Transparent),
            center = Offset(cx, baseY - u * 1.86f),
            radius = u * 1.6f
        ),
        radius = u * 1.6f,
        center = Offset(cx, baseY - u * 1.86f)
    )
}

private fun DrawScope.drawShelf(cx: Float, baseY: Float, u: Float, tint: Color) {
    // 立框
    drawRoundRect(
        color = tint,
        topLeft = Offset(cx - u * 0.85f, baseY - u * 2.10f),
        size = Size(u * 1.7f, u * 2.10f),
        cornerRadius = CornerRadius(u * 0.06f)
    )
    // 三层隔板 + 书
    val bookColors = listOf(0xFFC97B63, 0xFF6C8EBF, 0xFFD9B26A, 0xFF7FA37F)
    for (level in 0..2) {
        val shelfY = baseY - u * (0.35f + level * 0.62f)
        drawRect(
            color = tint.darken(0.30f),
            topLeft = Offset(cx - u * 0.80f, shelfY),
            size = Size(u * 1.6f, u * 0.06f)
        )
        for (b in 0..2) {
            val bw = u * 0.14f
            val bh = u * (0.28f + (b % 2) * 0.06f)
            drawRect(
                color = Color(bookColors[(level + b) % bookColors.size]),
                topLeft = Offset(cx - u * 0.70f + b * (bw + u * 0.06f), shelfY - bh),
                size = Size(bw, bh)
            )
        }
    }
}

private fun DrawScope.drawPlant(cx: Float, baseY: Float, u: Float, tint: Color) {
    // 花盆（上宽下窄的梯形）
    val pot = Path().apply {
        moveTo(cx - u * 0.34f, baseY - u * 0.50f)
        lineTo(cx + u * 0.34f, baseY - u * 0.50f)
        lineTo(cx + u * 0.24f, baseY)
        lineTo(cx - u * 0.24f, baseY)
        close()
    }
    drawPath(pot, Color(0xFFC08457))
    drawRect(
        color = Color(0xFFB0724A),
        topLeft = Offset(cx - u * 0.36f, baseY - u * 0.58f),
        size = Size(u * 0.72f, u * 0.10f)
    )
    // 三片叶子：都是椭圆，靠角度撑开
    drawOval(
        color = tint,
        topLeft = Offset(cx - u * 0.26f, baseY - u * 1.22f),
        size = Size(u * 0.52f, u * 0.78f)
    )
    drawOval(
        color = tint.darken(0.12f),
        topLeft = Offset(cx - u * 0.62f, baseY - u * 1.06f),
        size = Size(u * 0.44f, u * 0.58f)
    )
    drawOval(
        color = tint.lighten(0.10f),
        topLeft = Offset(cx + u * 0.18f, baseY - u * 1.04f),
        size = Size(u * 0.42f, u * 0.56f)
    )
}

private fun DrawScope.drawRug(cx: Float, baseY: Float, u: Float, tint: Color) {
    // 地毯是平的：一个扁椭圆 + 一圈内边
    drawOval(
        color = tint,
        topLeft = Offset(cx - u * 2.0f, baseY - u * 0.42f),
        size = Size(u * 4.0f, u * 0.72f)
    )
    drawOval(
        color = tint.darken(0.10f),
        topLeft = Offset(cx - u * 1.60f, baseY - u * 0.34f),
        size = Size(u * 3.2f, u * 0.52f)
    )
    drawOval(
        color = tint.lighten(0.08f),
        topLeft = Offset(cx - u * 1.10f, baseY - u * 0.26f),
        size = Size(u * 2.2f, u * 0.36f)
    )
}

/**
 * 墙上的挂画（0.9.19 批次 C）。
 *
 * 唯一一件**不落地**的家具：`baseY` 在这里是画的底边（屏幕高度比例），
 * 不是地板位置。所以它没有接触阴影，也不参与前后遮挡（深度 −1，最先画）。
 *
 * 画法上刻意只有三层：深色画框、纸底、一条斜的色块。再多两笔它就会
 * 从「墙上一张画」变成「墙上一个小场景」，在 2400px 的屏上只剩噪点。
 */
private fun DrawScope.drawPoster(cx: Float, baseY: Float, u: Float, tint: Color) {
    val w = u * 1.10f
    val h = u * 1.40f
    // 画框
    drawRoundRect(
        color = tint.darken(0.28f),
        topLeft = Offset(cx - w / 2f, baseY - h),
        size = Size(w, h),
        cornerRadius = CornerRadius(u * 0.05f)
    )
    // 纸底
    drawRect(
        color = Color(0xFFEFE9DC),
        topLeft = Offset(cx - w / 2f + u * 0.09f, baseY - h + u * 0.09f),
        size = Size(w - u * 0.18f, h - u * 0.18f)
    )
    // 画面里的一块色（用的就是画本身的颜色，换画框色时画心也跟着换）
    val path = Path().apply {
        moveTo(cx - w * 0.30f, baseY - h * 0.24f)
        lineTo(cx + w * 0.28f, baseY - h * 0.58f)
        lineTo(cx + w * 0.28f, baseY - h * 0.30f)
        lineTo(cx - w * 0.30f, baseY - h * 0.16f)
        close()
    }
    drawPath(path, color = tint.copy(alpha = 0.75f))
    // 一抹高光，让它不像一块印刷品
    drawCircle(
        color = Color.White.copy(alpha = 0.55f),
        radius = u * 0.09f,
        center = Offset(cx + w * 0.16f, baseY - h * 0.68f)
    )
}

/** 认不出的家具：方盒子 + 一点提示，绝不静默消失 */
private fun DrawScope.drawUnknown(cx: Float, baseY: Float, u: Float, tint: Color) {
    drawRoundRect(
        color = tint,
        topLeft = Offset(cx - u * 0.55f, baseY - u * 0.90f),
        size = Size(u * 1.1f, u * 0.90f),
        cornerRadius = CornerRadius(u * 0.10f)
    )
    drawLine(
        color = Color.White.copy(alpha = 0.5f),
        start = Offset(cx - u * 0.30f, baseY - u * 0.62f),
        end = Offset(cx + u * 0.30f, baseY - u * 0.62f),
        strokeWidth = 2.dp.toPx()
    )
    drawLine(
        color = Color.White.copy(alpha = 0.5f),
        start = Offset(cx - u * 0.30f, baseY - u * 0.42f),
        end = Offset(cx + u * 0.10f, baseY - u * 0.42f),
        strokeWidth = 2.dp.toPx()
    )
}

// ==================================================================== 角色

/**
 * 朝向 → 立绘资源（0.9.18）。
 *
 * 写死 `when` 而不是按名字查 `getIdentifier`：`R` 是编译期常量，拼错就是编译错误。
 * `getIdentifier` 拼错只会在运行时安静地返回 0，然后你得到一张透明图 ——
 * 「她站在屋里却看不见」是最难查的一类 bug，宁可让它编译不过。
 */
private fun RoomView.drawableRes(): Int = when (this) {
    RoomView.FRONT -> R.drawable.luna_chibi_front
    RoomView.SIDE -> R.drawable.luna_chibi_side
    RoomView.BACK -> R.drawable.luna_chibi_back
}

// ==================================================================== 小工具

private fun Color.lighten(amount: Float): Color = mixWith(Color.White, amount)

private fun Color.darken(amount: Float): Color = mixWith(Color.Black, amount)

private fun Color.mixWith(other: Color, t: Float): Color {
    val k = t.coerceIn(0f, 1f)
    return Color(
        red = red + (other.red - red) * k,
        green = green + (other.green - green) * k,
        blue = blue + (other.blue - blue) * k,
        alpha = alpha
    )
}

/** 粗略感知亮度，只用来判「这墙是深色还是浅色」 */
private fun Color.luminanceApprox(): Float = 0.299f * red + 0.587f * green + 0.114f * blue

private fun Color.toArgbInt(): Int = (alpha * 255).toInt().coerceIn(0, 255) shl 24 or
    ((red * 255).toInt().coerceIn(0, 255) shl 16) or
    ((green * 255).toInt().coerceIn(0, 255) shl 8) or
    (blue * 255).toInt().coerceIn(0, 255)

/** Dialog 里的 LocalContext 是 ContextThemeWrapper，不是 Activity，得往下扒 */
private tailrec fun Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

// ==================================================================== 常量（批次 D）

/**
 * 缩放范围。
 *
 * 下限 0.85 而不是 0.5：再小她就成了屏幕中间一个点，看不出是在看房间。
 * 上限 2.6：她的脸大约占画框高度的 1/6，2.6 倍时刚好能看清表情细节，
 * 再往上就是数像素（立绘只有 960 高）。
 */
private const val MIN_ZOOM = 0.85f
private const val MAX_ZOOM = 2.6f

/**
 * 遮罩上限（0.9.20 起从 0.60 放宽到 0.72）。
 *
 * 深夜的作息亮度是 0.34，需要 0.66 的遮罩 —— 还按 0.60 卡，就会得到
 * 「说是深夜、屋里却是 40% 亮度」这种自相矛盾。0.72 对应 28% 亮度：
 * 能看清她在床上、能看见 Zzz，但一眼就知道是夜里。
 */
private const val MAX_SCRIM = 0.72f

/** 坐着时的整体收缩（没有坐姿立绘，只能压低一截近似） */
private const val SIT_SHRINK = 0.08f

/** 坐着时往下沉多少（相对画框高度）。压低 + 下沉，看着才像「坐下去」而不是「变小」 */
private const val SIT_DROP = 0.045f
