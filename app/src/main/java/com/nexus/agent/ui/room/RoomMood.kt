package com.nexus.agent.ui.room

import com.nexus.agent.persona.PAD
import com.nexus.agent.persona.PadDynamics
import kotlin.math.roundToInt

/**
 * 站在房间里的姿势（0.9.17 批次 B）。
 *
 * 四个值对应 PAD 的四种「身体语言」，不是四个心情标签 —— 心情标签归
 * [PadDynamics.labelOf] 管，这里只管**身体怎么摆**。分开的理由：同一个八分区
 * （比如「依恋」+P+A−D）和「雀跃」+P+A+D 都要上下浮动，但前者不该飘到房间中央。
 */
enum class RoomPose(val zh: String) {
    /** 原地站着，呼吸缩放 1.00 ↔ 1.02 */
    IDLE("站着"),

    /** 躺平（横向压扁）+ 头上冒 Zzz */
    SLEEPY("躺平"),

    /** 悬浮在房间中间，上下浮动 */
    EXCITED("雀跃"),

    /** 抱膝坐着，谁也不看（消沉） */
    DOWN("发呆")
}

/**
 * 一瞬间的「房间该长什么样」。
 *
 * ## 为什么是纯数据 + 纯函数
 *
 * 这是**灵魂与视窗之间唯一的一道翻译**：左边是 [PAD] 和好感度，右边是亮度、色温、
 * 姿态、距离。翻译本身不该依赖 Compose、不该依赖 Android —— 否则想验证
 * 「犯困的时候真的只有 40% 亮度吗」就只能靠肉眼看截图。
 *
 * 所以这个文件里没有 `androidx.compose.*`，也就没有 `Color`：颜色一律用 ARGB Int，
 * 由 [RoomScreen] 在最后一刻包成 Compose 的 `Color`。纯 JVM 单测能直接断言通道值。
 *
 * ## 为什么用「暗角 alpha」而不是「亮度倍率」
 *
 * 亮度是**乘性**的，而 Compose 只能叠加半透明黑色：
 * `结果 = 原色 × (1 − α)`。所以 `α = 1 − brightness`，0.40 亮度就是 0.60 的黑遮罩，
 * 数学上严丝合缝，不需要给每个家具都套一层 ColorFilter。
 *
 * @property brightness 0.40 ~ 1.00，见 [RoomMoodMapper.DIM_BRIGHTNESS]
 * @property warmth     −1 冷 ~ +1 暖（色温，驱动墙面的环境色叠加）
 * @property distance   角色缩放 0.85 ~ 1.25（好感度越高离屏幕越近）
 * @property ambientArgb 环境色（不含 alpha）
 * @property tintAlpha  环境色叠加的 alpha
 * @property label      她此刻的状态（**直接复用** [PadDynamics.labelOf]，不另起一套词）
 */
data class RoomMood(
    val pose: RoomPose,
    val brightness: Float,
    val warmth: Float,
    val distance: Float,
    val ambientArgb: Int,
    val tintAlpha: Float,
    val label: String
) {
    /**
     * 盖在整间屋子上的黑色遮罩 alpha。
     *
     * 上限 0.60 —— 正好对应 40% 亮度，也就是「困到关灯」的下限。
     * 再暗下去用户会以为屏幕坏了（真出过这个反馈：夜间模式太黑，用户以为闪退）。
     */
    val scrimAlpha: Float
        get() = (1f - brightness).coerceIn(0f, MAX_SCRIM)

    /** 犯困才冒 Zzz；消沉没有 Zzz（那是「睡不着」，不是「不想动」） */
    val showZzz: Boolean get() = pose == RoomPose.SLEEPY

    /** 雀跃：悬浮在房间中间；其余姿态都踩在地上 */
    val hovering: Boolean get() = pose == RoomPose.EXCITED

    companion object {
        const val MAX_SCRIM = 0.60f
    }
}

/**
 * PAD + 好感度 → [RoomMood]（0.9.17 批次 B）。
 *
 * ## 四档姿态的判据为什么长这样
 *
 * 顺序不能换：
 *
 * 1. **先判中性**。坐标贴近原点时八分区的符号毫无意义（(0,0,0) 会被判成「雀跃」），
 *    这是 0.9.15 就踩过的坑，`PadState` 里的 `describeOf` 也是先拦这一道。
 * 2. **再判犯困**（A ≤ −0.30 且 P ≥ −0.20）。低唤醒是生理状态 —— 凌晨四点她困得
 *    说不出话，不代表她意志消沉。这两个阈值**直接引用** [PAD.DROWSY_A] /
 *    [PAD.DROWSY_MIN_P]，不另抄一份：提示词里说「犯困」而房间里在发呆，就是灵魂分裂。
 * 3. **再判雀跃**（P ≥ +0.30 且 A ≥ +0.30）。两个轴都要够，单轴高只是「有点高兴」，
 *    不该飘起来。
 * 4. **最后判消沉**（P ≤ −0.20 且 A ≤ 0）。同样复用 `PadState` 的语义：
 *    P 也低的时候才是真难过，不拿「困」把难过糊过去。
 *
 * ## 好感度只影响距离，不影响姿态
 *
 * 好感度高 = 她愿意站得离你近一点（画得大一点），但**不会**让她因为喜欢你
 * 就一直雀跃 —— 情绪只能由 PAD 决定，好感度是关系、不是心情。这两件事混在一起，
 * 用户会发现「好感度刷满之后她永远在笑」，那就成了表情包。
 */
object RoomMoodMapper {

    /** 犯困 / 消沉时的亮度：只剩四成，色温转冷 */
    const val DIM_BRIGHTNESS = 0.40f

    /** 雀跃：亮度拉满 */
    const val FULL_BRIGHTNESS = 1.00f

    /**
     * 平静时的亮度下限。
     *
     * 不直接用 1.00 的原因：平静也分「今天挺舒服」和「今天有点闷」，
     * 用 P/A 在这个下限之上做连续调制（0.72 ~ 1.00），光影才有层次；
     * 恒定 1.00 会让房间在两种心情下一模一样。
     */
    const val IDLE_BRIGHTNESS_FLOOR = 0.72f

    const val EXCITED_P = 0.30f
    const val EXCITED_A = 0.30f
    const val DOWN_P = -0.20f
    const val DOWN_A = 0f

    const val COLD_WARMTH = -0.85f
    const val WARM_WARMTH = 0.90f

    /** 冷色温：偏蓝的夜灯 */
    const val COOL_ARGB = 0x5B7CC4

    /** 暖色温：偏橙的灯泡 */
    const val WARM_ARGB = 0xFFC46B

    /** 好感度 0 时的距离（最远） */
    const val FAR_DISTANCE = 0.85f

    /** 好感度 100 时的距离（贴到屏幕前） */
    const val NEAR_DISTANCE = 1.25f

    const val TINT_DIM = 0.26f
    const val TINT_BRIGHT = 0.10f
    const val TINT_IDLE = 0.06f

    /** 平静时色温随愉悦度轻微偏移的系数（−0.2 ~ +0.2） */
    const val IDLE_WARMTH_GAIN = 0.20f

    fun of(pad: PAD, affinity: Int): RoomMood {
        val safe = pad.clamp()
        val pose = poseOf(safe)
        val warmth = warmthOf(safe, pose)
        return RoomMood(
            pose = pose,
            brightness = brightnessOf(safe, pose),
            warmth = warmth,
            distance = distanceOf(affinity),
            ambientArgb = ambientArgbOf(warmth),
            tintAlpha = tintAlphaOf(pose),
            label = PadDynamics.labelOf(safe)
        )
    }

    fun poseOf(pad: PAD): RoomPose {
        if (pad.isNeutral()) return RoomPose.IDLE
        if (pad.a <= PAD.DROWSY_A && pad.p >= PAD.DROWSY_MIN_P) return RoomPose.SLEEPY
        if (pad.p >= EXCITED_P && pad.a >= EXCITED_A) return RoomPose.EXCITED
        if (pad.p <= DOWN_P && pad.a <= DOWN_A) return RoomPose.DOWN
        return RoomPose.IDLE
    }

    fun brightnessOf(pad: PAD, pose: RoomPose): Float = when (pose) {
        RoomPose.SLEEPY, RoomPose.DOWN -> DIM_BRIGHTNESS
        RoomPose.EXCITED -> FULL_BRIGHTNESS
        // 平静：P 和 A 各占一半权重，落在 [0.72, 1.00]
        RoomPose.IDLE -> {
            val pleasure = (pad.p + 1f) / 2f
            val arousal = (pad.a + 1f) / 2f
            val t = (0.5f * pleasure + 0.5f * arousal).coerceIn(0f, 1f)
            IDLE_BRIGHTNESS_FLOOR + (FULL_BRIGHTNESS - IDLE_BRIGHTNESS_FLOOR) * t
        }
    }.coerceIn(DIM_BRIGHTNESS, FULL_BRIGHTNESS)

    fun warmthOf(pad: PAD, pose: RoomPose): Float = when (pose) {
        RoomPose.SLEEPY, RoomPose.DOWN -> COLD_WARMTH
        RoomPose.EXCITED -> WARM_WARMTH
        // 平静：微微跟着愉悦度走，且不到极端 —— 平静就该是「几乎没滤镜」
        RoomPose.IDLE -> (IDLE_WARMTH_GAIN * pad.p).coerceIn(-IDLE_WARMTH_GAIN, IDLE_WARMTH_GAIN)
    }.coerceIn(-1f, 1f)

    /**
     * 好感度 → 角色缩放。
     *
     * 线性映射，故意不做曲线：0 好感 0.85 倍、满好感 1.25 倍，中间每一档都能
     * 看出差别。越界的输入直接钳制（脏数据不该让她缩成一个点）。
     */
    fun distanceOf(affinity: Int): Float {
        val t = affinity.coerceIn(0, 100) / 100f
        return FAR_DISTANCE + (NEAR_DISTANCE - FAR_DISTANCE) * t
    }

    /** 冷 ↔ 暖的线性插值（逐通道），warmth=0 时正好是两端的中间色 */
    fun ambientArgbOf(warmth: Float): Int {
        val t = ((warmth.coerceIn(-1f, 1f) + 1f) / 2f)
        return argbLerp(COOL_ARGB, WARM_ARGB, t)
    }

    fun tintAlphaOf(pose: RoomPose): Float = when (pose) {
        RoomPose.SLEEPY, RoomPose.DOWN -> TINT_DIM
        RoomPose.EXCITED -> TINT_BRIGHT
        RoomPose.IDLE -> TINT_IDLE
    }

    // ---------------------------------------------------------------- 工具

    /** ARGB 逐通道插值（不含 alpha，两个端点的 alpha 都被忽略） */
    fun argbLerp(from: Int, to: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun ch(shift: Int): Int {
            val a = (from shr shift) and 0xFF
            val b = (to shr shift) and 0xFF
            return (a + (b - a) * k).roundToInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

/** 取某一通道（0..255），单测和渲染层共用，免得各处自己写移位写错 */
fun Int.redOf(): Int = (this shr 16) and 0xFF
fun Int.greenOf(): Int = (this shr 8) and 0xFF
fun Int.blueOf(): Int = this and 0xFF
