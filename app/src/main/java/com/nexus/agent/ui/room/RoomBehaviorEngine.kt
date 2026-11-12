package com.nexus.agent.ui.room

import android.content.Context
import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.nexus.agent.copilot.PersonaEngine
import com.nexus.agent.data.db.RoomItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalTime

/**
 * 她在屋里正在做的事（0.9.20 批次 D）。
 *
 * ## 为什么和 [RoomPose] 是两个枚举
 *
 * [RoomPose] 是**情绪的身体语言**（她开心所以飘起来），由 PAD 决定；
 * 这个枚举是**她在干嘛**（她在吃饭），由作息和决策决定。两件事会打架：
 * 深夜她困得要死，但作息说该睡了 —— 如果只有一个枚举，就得在每个 if 里
 * 权衡「困」和「该睡」哪个优先，权重一定会在某个分支写错。
 *
 * 分开之后规则只有一句：**作息（[TimeRoutine]）管「做什么」，PAD 管「怎么做」**。
 * 深夜就是 SLEEPING，犯困的 PAD 只决定她躺得舒不舒服（光更暗、被盖得更严）。
 *
 * @property zh 中文名，抬头显示与提示词里用
 */
enum class RoomBehavior(val zh: String) {
    /** 原地站着发呆 */
    IDLE("发呆"),

    /** 从一个点走到另一个点 */
    WALKING("走动"),

    /** 坐着（午休 / 歇一会儿） */
    SITTING("坐着"),

    /** 躺在床上睡着（深夜强制） */
    SLEEPING("睡觉"),

    /** 在书桌边吃东西 */
    EATING("吃东西"),

    /** 被用户戳了一下，正在说话 */
    CHATTING("聊天")
}

/**
 * 行为逻辑（0.9.20 批次 D）—— 纯函数，**不引用 Compose、不引用 Android**。
 *
 * ## 为什么把「去哪儿」「待多久」「接下来干嘛」都做成纯函数
 *
 * 状态机最难验的不是「跑起来」，是「凌晨三点她会不会在屋里散步」。
 * 只要逻辑是纯的，这些都能在单测里一秒跑完 100 个时间点；
 * 混进 Ams（[androidx.compose.animation.core.Animatable]）之后就只剩肉眼盯着看。
 *
 * 于是分工是：**这个对象出主意，[rememberRoomLife] 负责动**。
 *
 * ## 走动为什么必须有「家具锚点」而不是随机数
 *
 * 「睡在床上」不是一句气氛描写 —— 位置错了用户一眼就发现：她躺在屋子正中间
 * 的地板上。所以目标点是**从房间里真实的家具推出来的**：睡觉去床、吃饭去桌、
 * 坐着去沙发。屋里没有那件家具时才退回默认点，而不是原地凭空躺下。
 */
object RoomBehaviorEngine {

    /** 她脚底的横向安全范围：再往外走，透明画框会把身体裁掉一截 */
    const val SAFE_FEET_X_MIN = 0.14f
    const val SAFE_FEET_X_MAX = 0.86f

    /**
     * 站位在地板带里的纵向上限。
     *
     * 0.76 换算成屏幕高度是 `0.60 + 0.40 × 0.76 = 0.904` —— 正好压在底部
     * 装修输入条（约 0.93 屏高）之上。再往下她的脚就藏在输入条后面，
     * 看起来像站在地板里。**这个数不是审美，是输入条的高度决定的。**
     */
    const val SAFE_FEET_Y_MAX = 0.76f

    /** 想挪的位置离现在不到这么远就别走了 —— 迈半步又停下像抽筋 */
    const val MIN_WALK_DELTA = 0.06f

    /** 发呆超过这么久，才允许自己找点事做（不然一进屋她就在乱走） */
    const val BORED_MS = 90_000L

    /** 行为引擎的心跳：每分钟看一次 */
    const val TICK_MS = 60_000L

    const val IDLE_HOLD_MS = 45_000L
    const val SIT_HOLD_MS = 100_000L
    const val EAT_HOLD_MS = 150_000L
    const val CHAT_HOLD_MS = 14_000L

    /** 走路时长：起步 900ms，走完全屏再加 1700ms */
    const val WALK_BASE_MS = 900
    const val WALK_SPAN_MS = 1_700

    /**
     * 屋里可以随便站的位置（x, 地板带 y）。
     *
     * 手工挑的五个点，落在中景与近景之间：再往后会钻到床底下，
     * 再往前会被底部输入条压住脚。**不用随机数生成** —— 随机生成的点常常
     * 连着两次落在同一块地方，看起来像卡住了；固定池子里换着走，反而像在屋里转。
     */
    val WALK_SPOTS: List<Spot> = listOf(
        Spot(0.30f, 0.62f),
        Spot(0.68f, 0.58f),
        Spot(0.46f, 0.74f),
        Spot(0.22f, 0.74f),
        Spot(0.74f, 0.72f)
    )

    /** 一个位置：x 是舞台横向 0~1，y 是**地板带**内的 0~1（不是整屏） */
    data class Spot(val x: Float, val y: Float)

    /** 站着不动的默认点（= 0.9.17 起她一直站的地方） */
    fun homeSpot(): Spot = Spot(RoomGeometry.HERO_X, RoomGeometry.HERO_Y)

    /**
     * 某种行为该站在哪儿。
     *
     * 找不到对应家具就退回 [homeSpot]：宁可站在屋子中间，也不能凭空
     * 「躺在空气里」—— 那比不做更吓人。
     */
    fun spotOf(behavior: RoomBehavior, items: List<RoomItem>): Spot = when (behavior) {
        RoomBehavior.SLEEPING -> spotNear(RoomItem.KIND_BED, items) ?: homeSpot()
        RoomBehavior.EATING -> spotNear(RoomItem.KIND_DESK, items) ?: homeSpot()
        RoomBehavior.SITTING -> spotNear(RoomItem.KIND_SOFA, items) ?: homeSpot()
        // 聊天就站在她现在的原地，由调用方保留坐标
        RoomBehavior.CHATTING, RoomBehavior.IDLE, RoomBehavior.WALKING -> homeSpot()
    }

    /**
     * 某类家具旁边该站的位置。
     *
     * 用家具**槽位的锚点**，不是家具自己的绘制坐标 —— 锚点是「这件东西在屋里的
     * 位置」的唯一事实，绘制坐标里还混了尺寸和偏移，拿来当站位会偏半个身子。
     *
     * y 额外钳到 [SAFE_FEET_Y_MAX] 以下：前排（`front_*` 的 y = 0.86~0.90）会撞上底部输入条，
     * 她的脚被压住看起来像陷进地板。
     */
    fun spotNear(kind: String, items: List<RoomItem>): Spot? {
        val item = items.firstOrNull { it.kind == kind } ?: return null
        val anchor = RoomGeometry.anchorOf(item.slot)
        // 墙面件的 y 是屏幕比例，不是地板带，站不过去
        if (anchor.depth < 0) return null
        return Spot(
            x = clampFeetX(anchor.x),
            y = anchor.y.coerceIn(0f, SAFE_FEET_Y_MAX)
        )
    }

    fun clampFeetX(x: Float): Float =
        if (!x.isFinite()) RoomGeometry.HERO_X else x.coerceIn(SAFE_FEET_X_MIN, SAFE_FEET_X_MAX)

    /**
     * 空闲太久时自己找点事做。
     *
     * [seed] 由调用方给（时间戳 / 点击次数都行），这里只取模 —— 这样单测能
     * 让同一个 seed 永远得到同一个结果，不用为了测随机而放宽断言。
     *
     * **强制状态优先于一切**：深夜就是睡觉，`bored` 传什么都不该把她叫起来。
     */
    fun nextAutoBehavior(
        routine: TimeRoutine,
        bored: Boolean,
        seed: Int,
        current: RoomBehavior
    ): RoomBehavior {
        routine.forced?.let { return it }
        // 正在聊天时不要被「一分钟到了」打断，让它自己待够
        if (current == RoomBehavior.CHATTING) return current
        // 没闷够 = **保持现状**，不是「切回发呆」。
        // 这里曾经返回 IDLE，后果是：早上 7:30 进屋她开始吃早饭，
        // 60 秒后心跳一到就被拽回发呆 —— 饭只吃了一分钟。
        // 「不主动找事做」和「把手上的事放下」是两件事，别混成一件。
        if (!bored) return current
        val pool = routine.preferred.filter { it != RoomBehavior.SLEEPING && it != RoomBehavior.CHATTING }
        if (pool.isEmpty()) return RoomBehavior.IDLE
        val i = ((seed % pool.size) + pool.size) % pool.size
        return pool[i]
    }

    /** 这个行为该保持多久（毫秒）。WALKING 由路程决定，这里给个保守值 */
    fun holdMillis(behavior: RoomBehavior): Long = when (behavior) {
        RoomBehavior.IDLE -> IDLE_HOLD_MS
        RoomBehavior.SITTING -> SIT_HOLD_MS
        RoomBehavior.EATING -> EAT_HOLD_MS
        RoomBehavior.CHATTING -> CHAT_HOLD_MS
        RoomBehavior.WALKING -> 4_000L
        // 睡觉的时长不由这里定：由「天亮了」决定
        RoomBehavior.SLEEPING -> Long.MAX_VALUE / 4
    }

    /** 两个点之间的距离（用 x 与 y 的最大值，够近似） */
    fun distanceOf(a: Spot, b: Spot): Float {
        val dx = (a.x - b.x)
        val dy = (a.y - b.y)
        return kotlin.math.max(kotlin.math.abs(dx), kotlin.math.abs(dy))
    }

    /** 路程 → 动画时长。太近直接返回 0，调用方据此跳过动画 */
    fun walkMillis(from: Spot, to: Spot): Int {
        val d = distanceOf(from, to)
        if (d < MIN_WALK_DELTA) return 0
        return (WALK_BASE_MS + WALK_SPAN_MS * d).toInt().coerceIn(WALK_BASE_MS, WALK_BASE_MS + WALK_SPAN_MS)
    }

    /**
     * 朝向：−1 朝左 / +1 朝右 / 0 正面。
     *
     * 走动时用侧面立绘 + 这个朝向做镜像，走起来才像在「走」而不是「平移」。
     * 位移小于 1% 就保持正面 —— 免得她原地抖一下就翻个面。
     */
    fun facingOf(fromX: Float, toX: Float): Int {
        val d = toX - fromX
        return when {
            d > 0.01f -> 1
            d < -0.01f -> -1
            else -> 0
        }
    }

    /**
     * 挑一个「随便走走」的目标。
     *
     * 排除离当前位置最近的那个点：否则她可能连续两次选到脚下，
     * 表现为「一分钟到了，她原地抖了一下」。
     */
    fun wanderSpot(current: Spot, seed: Int): Spot {
        val far = WALK_SPOTS.filter { distanceOf(it, current) >= MIN_WALK_DELTA }
        if (far.isEmpty()) return homeSpot()
        val i = ((seed % far.size) + far.size) % far.size
        return far[i]
    }
}

// ==================================================================== 组合层

/**
 * 房间生命体的当前快照（0.9.20 批次 D）。
 *
 * 不可变，每帧重建一次 —— 里面只有四个 Float/枚举，重建的代价远小于
 * 「让 Composable 自己去读十个 mutableState 然后在某个角落漏掉一次重组」。
 *
 * @property spot     她脚底的位置（x 舞台比例 / y 地板带比例）
 * @property facing   朝向：−1 朝左 / +1 朝右 / 0 正面
 * @property bubble   头顶气泡文字；null = 不显示
 * @property thinking 正在等模型回话（气泡显示「…」）
 * @property onTap    点她一下
 */
@Immutable
data class RoomLife(
    val behavior: RoomBehavior,
    val spot: RoomBehaviorEngine.Spot,
    val facing: Int,
    val bubble: String?,
    val thinking: Boolean,
    val onTap: () -> Unit
)

/**
 * 把 [RoomBehaviorEngine] 的主意变成动画（0.9.20 批次 D）。
 *
 * ## 为什么走动用 [Animatable] 而不是 `animateFloatAsState`
 *
 * `animateFloatAsState` 是「目标值变了就跟过去」，中途换了目标会从当前位置
 * 重新起跳，而且**拿不到「走完了」这个时刻** —— 而「到达后坐下」正需要那一帧。
 * [Animatable.animateTo] 是挂起函数，走完才返回，顺着写就是正确的时间线。
 *
 * ## 为什么 x 和 y 分开两个 Animatable 但要同时跑
 *
 * 走斜线时如果先走 x 再走 y，她会走出一个 L 形。两个 `launch` 包在
 * `coroutineScope` 里同时启动，用时取同一个 `durationMillis`，插值同步，
 * 路径就是直线。
 *
 * ## 为什么这个函数在 RoomBehaviorEngine.kt 里
 *
 * 「她怎么动」和「她为什么动」放同一个文件，改的时候一眼能看到另一个。
 * 但**逻辑全在纯对象里**（上面那半个文件），这里只负责动画与协程 ——
 * 单测只碰纯对象，不碰这个函数。
 */
@Composable
fun rememberRoomLife(
    context: Context,
    items: List<RoomItem>,
    routine: TimeRoutine,
    time: LocalTime
): RoomLife {
    val scope = rememberCoroutineScope()
    val xAnim = remember { Animatable(RoomGeometry.HERO_X) }
    val yAnim = remember { Animatable(RoomGeometry.HERO_Y) }

    var behavior by remember { mutableStateOf(RoomBehavior.IDLE) }
    var facing by remember { mutableStateOf(0) }
    var bubble by remember { mutableStateOf<String?>(null) }
    var thinking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var lastActionAt by remember { mutableStateOf(System.currentTimeMillis()) }
    var tick by remember { mutableStateOf(0) }
    var chatSeed by remember { mutableStateOf(0) }

    /**
     * 平滑挪到某处。返回是否真的走了。
     *
     * `durationMs == 0` 表示原地不动 —— 这时**不切 WALKING**：切了会看到
     * 她朝空气走了两步又停下。
     */
    suspend fun moveTo(target: RoomBehaviorEngine.Spot, durationMs: Int): Boolean {
        if (durationMs <= 0) return false
        val from = RoomBehaviorEngine.Spot(xAnim.value, yAnim.value)
        facing = RoomBehaviorEngine.facingOf(from.x, target.x)
        behavior = RoomBehavior.WALKING
        coroutineScope {
            launch {
                xAnim.animateTo(
                    target.x,
                    tween(durationMs, easing = LinearOutSlowInEasing)
                )
            }
            launch {
                yAnim.animateTo(
                    target.y,
                    tween(durationMs, easing = LinearOutSlowInEasing)
                )
            }
        }
        facing = 0
        lastActionAt = System.currentTimeMillis()
        return true
    }

    /** 保证同一时刻只有一个「走向目标」的动作在跑 */
    val gate = remember { Mutex() }

    /** 执行一个行为：该走过去的走过去，该坐下的坐下 */
    suspend fun perform(next: RoomBehavior) = gate.withLock {
        when (next) {
            RoomBehavior.SLEEPING, RoomBehavior.EATING, RoomBehavior.SITTING -> {
                val target = RoomBehaviorEngine.spotOf(next, items)
                val from = RoomBehaviorEngine.Spot(xAnim.value, yAnim.value)
                moveTo(target, RoomBehaviorEngine.walkMillis(from, target))
                behavior = next
            }
            RoomBehavior.WALKING -> {
                val from = RoomBehaviorEngine.Spot(xAnim.value, yAnim.value)
                val target = RoomBehaviorEngine.wanderSpot(from, tick + chatSeed)
                val walked = moveTo(target, RoomBehaviorEngine.walkMillis(from, target))
                // 挑到的点就在脚下（walked = false）时保持原地，别原地抖
                behavior = when {
                    !walked -> RoomBehavior.IDLE
                    // 到了目的地：站着或坐下，二选一（任务书要求「自动切换为 IDLE 或 SITTING」）
                    (tick and 1) == 0 -> RoomBehavior.IDLE
                    else -> RoomBehavior.SITTING
                }
                lastActionAt = System.currentTimeMillis()
            }
            RoomBehavior.IDLE, RoomBehavior.CHATTING -> behavior = next
        }
    }

    // ---- ① 进屋 / 换时段：先对齐当前时间 ----
    //
    // key 用 slot 而不是 routine 整个对象：同一时段里每帧都会重建 routine，
    // 用它当 key 会每秒重新「起床」一次。
    LaunchedEffect(routine.slot) {
        val next = routine.forced
            ?: routine.preferred.firstOrNull { it != RoomBehavior.SLEEPING }
            ?: RoomBehavior.IDLE
        perform(next)
    }

    // ---- ①′ 屋里添了家具：只在「必须睡床上」时才重新对齐 ----
    //
    // 以前 items.size 是挂在上面那个 effect 的 key 上的，于是**装修一下她就换动作**：
    // 正吃着饭，用户加了个台灯，她立刻被切回候选池的第一项。
    // 现在拆出来：深夜加张床才需要把她挪过去，别的时段让装修安静地发生。
    var seenItemCount by remember { mutableStateOf(items.size) }
    LaunchedEffect(items.size) {
        if (items.size == seenItemCount) return@LaunchedEffect
        seenItemCount = items.size
        routine.forced?.let { perform(it) }
    }

    // ---- ② 每分钟醒一次：闲着太久就自己找点事做 ----
    LaunchedEffect(routine.slot) {
        while (true) {
            delay(RoomBehaviorEngine.TICK_MS)
            tick += 1
            if (busy) continue
            val idleFor = System.currentTimeMillis() - lastActionAt
            val bored = idleFor >= RoomBehaviorEngine.BORED_MS + RoomBehaviorEngine.holdMillis(behavior)
            // **没闲够就什么都不做**：以前这里无条件调 nextAutoBehavior，
            // 而它在 !bored 时返回 IDLE —— 结果刚坐下 60 秒就被叫起来站着，
            // 坐姿形同虚设。现在只有「闲够」或「时段强制」才会换动作。
            val next = when {
                routine.forced != null -> routine.forced!!
                bored -> RoomBehaviorEngine.nextAutoBehavior(routine, true, tick, behavior)
                else -> behavior
            }
            if (next != behavior) perform(next)
        }
    }

    // ---- ③ 点她一下 ----
    fun chat() {
        if (busy) return
        busy = true
        chatSeed += 1
        scope.launch {
            val previous = behavior
            behavior = RoomBehavior.CHATTING
            thinking = true
            bubble = null
            Log.i(TAG_CHAT, "[点她] 进入 CHATTING：时段=${routine.slot} 原行为=$previous")

            // 0.9.21 问题四：这里**必须**兜住 respond 抛出的所有异常。
            //
            // 以前只有 withTimeoutOrNull，它只管超时不管异常。Key 没配、数据库没就绪、
            // 云端返回体解析失败 —— 任何一个都会把这个协程整个带走：
            // thinking 停在 true、busy 停在 true，而 chat() 开头有 `if (busy) return`，
            // 于是**之后怎么点都没反应**。用户看到的就是「她是个哑巴」。
            var line: String? = null
            try {
                line = withTimeoutOrNull(CHAT_TIMEOUT_MS) {
                    PersonaEngine.respond(
                        context = context,
                        userText = TimeRoutineManager.triggerOf(time, routine, previous),
                        allowScreenRead = false
                    )
                }?.trim()?.takeIf { it.isNotEmpty() }
                Log.i(
                    TAG_CHAT,
                    if (line == null) "[点她] 模型没给出文本（未配置 / 超时 / 空回复），用本地台词"
                    else "[点她] 模型回了 ${line.length} 字"
                )
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Log.w(TAG_CHAT, "[点她] 模型调用抛了 ${t.javaClass.simpleName}：${t.message}，退回本地台词")
            }

            thinking = false
            // 气泡里只放一句：模型偶尔会写一大段，房间里塞不下
            val say = line ?: TimeRoutineManager.fallbackLine(routine, previous, chatSeed)
            bubble = say.lineSequence().firstOrNull { it.isNotBlank() }?.take(60) ?: say.take(60)
            Log.i(TAG_CHAT, "[点她] 气泡=\"${bubble}\"")
            delay(BUBBLE_LINGER_MS)
            bubble = null
            // 聊完回到本来该做的事：该睡的继续睡
            behavior = routine.forced ?: RoomBehavior.IDLE
            lastActionAt = System.currentTimeMillis()
            busy = false
        }
    }

    return RoomLife(
        behavior = behavior,
        spot = RoomBehaviorEngine.Spot(xAnim.value, yAnim.value),
        facing = facing,
        bubble = bubble,
        thinking = thinking,
        onTap = { chat() }
    )
}

/** 等模型的上限：超过就先用本地台词顶上，不能让用户以为她死了 */
private const val CHAT_TIMEOUT_MS = 20_000L

/** 气泡停留时长 */
private const val BUBBLE_LINGER_MS = 9_000L

/**
 * 点她这条链路的日志 TAG。
 *
 * 排障口诀：`adb logcat -s RoomChat`
 * 「[点她] 进入 CHATTING」= 点击命中了；
 * 「模型回了 N 字」= 模型通；「模型没给出文本」= 模型层的问题（Key / 超时 / 离线）；
 * 「气泡="…"」= 文字确实进到界面了。
 */
const val TAG_CHAT = "RoomChat"
