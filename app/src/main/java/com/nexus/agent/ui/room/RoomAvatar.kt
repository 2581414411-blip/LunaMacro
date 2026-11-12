package com.nexus.agent.ui.room

/**
 * 她的三个朝向（0.9.18）。
 *
 * ## 为什么是三视图而不是一个立绘
 *
 * 一个立绘只能表达「她对你」，表达不了「她背对你」。三视图给了三种关系：
 * 正视（跟你说话）、侧身（睡着了，或者看向别处）、背影（不想说话）。
 * 姿态本身不新增表情，**朝向就是表情** —— 转过身去这一下，比画一张苦脸更狠。
 *
 * @param zh 中文名，抬头显示里用
 */
enum class RoomView(val zh: String) {
    FRONT("正面"),
    SIDE("侧面"),
    BACK("背面")
}

/**
 * 姿态 → 立绘朝向 / 变换参数（0.9.18）。
 *
 * ## 为什么从 [RoomScreen] 里抽出来
 *
 * 「犯困该用哪张图、躺下转多少度、消沉要不要掉色」这些是**设计决策**，
 * 不是渲染细节。混在 Composable 里就只能靠肉眼验，抽成纯函数后单测能钉住
 * 「雀跃不许用背影」这类底线。这个文件刻意**不引用 `androidx.compose.*`**，
 * 和 [RoomMoodMapper] 一个待遇。
 *
 * ## 三张图的用法
 *
 * | 姿态 | 朝向 | 理由 |
 * |---|---|---|
 * | 平静 | 正面 | 看着你，正常状态 |
 * | 雀跃 | 正面 | 开心的时候当然要看着你 |
 * | 犯困 | 侧面 | **只有侧面能躺**，正面躺下像标本 |
 * | 消沉 | 背面 | 生气了 / 难过了，转过身去 —— 不说话比说话更响 |
 *
 * ## 角色也要吃光
 *
 * 立绘画在光影层之上（不然会被墙面遮住），代价是**她不在屋里**：
 * 房间黑了她还是亮的，像贴纸。所以给她单独套一个 ColorFilter，
 * 按 [FIGURE_LIGHT_SHARE] 的强度吃同一份暗和同一份色温 ——
 * 吃满会黑到看不清脸，一点不吃就是贴纸，55% 是两者之间。
 */
object RoomAvatar {

    /** 躺平时整体旋转角度。−88° 而不是 −90°：留一点角度才像「歪着睡过去」 */
    const val LIE_TILT_DEG = -88f

    /** 躺平时的额外缩小 —— 站立时她是高的，躺下后长度会顶到屏幕两侧 */
    const val LIE_SHRINK = 0.86f

    /**
     * 角色画框的宽高比，取三张图里**最宽**的那张（正面 736×960 ≈ 0.767）。
     *
     * 三张图共用一个画框，是为了让朝向切换时**画框不动**。按各自比例定画框，
     * 从正面切到侧面会看到整个房间跳一下；固定成最宽的那张，窄的图两侧留白，
     * 人物始终站在画面的同一个位置。
     */
    const val FRAME_ASPECT = 736f / 960f

    /** 侧视图的宽高比（629×960）。躺平时用的就是它 */
    const val SIDE_ASPECT = 629f / 960f

    /**
     * 躺平时整体右移的比例（相对画框高度）。
     *
     * 旋转的支点放在**脚底**而不是画框中心 —— 人是绕脚倒下去的，不是原地转圈。
     * 但绕脚转 88° 后，身体整个摊在支点左侧，所以要把支点往右推回去：
     * 身体长度 ≈ 画框高 × 0.86，居中就得右移一半，即 0.43。
     */
    const val LIE_SLIDE = 0.43f

    /**
     * 躺平时的抬升比例（相对画框宽度）。
     *
     * **不抬会沉进地板一半**：立绘是一张薄片，绕脚底转 88° 之后，它的「厚度」
     * （也就是侧视图的宽度）有一半落到支点下方。真实的人躺下时身体中线离地
     * 半个体厚，所以要往上抬半个厚度 = 侧视图宽 ÷ 2。
     * 侧视图宽 = 画框宽 × (侧视比例 ÷ 画框比例)。
     */
    const val LIE_LIFT = (SIDE_ASPECT / FRAME_ASPECT) / 2f

    /** 画框高度占可用高度的比例（剩下的留给抬头显示） */
    const val FRAME_HEIGHT_FRACTION = 0.46f

    /** 消沉时的去饱和量：不是调暗（那是光的事），是「她自己没颜色了」 */
    const val DOWN_DESATURATION = 0.62f

    /** 角色吃多少比例的室内暗度 */
    const val FIGURE_LIGHT_SHARE = 0.55f

    /**
     * 色温对角色通道的偏移幅度。
     *
     * 很小（±6%）：立绘是画好的，把蓝头发染成橘色就认不出来了。
     * 房间的色温主要靠环境色叠在墙上，角色只需要**一点点**同频。
     */
    const val WARMTH_GAIN = 0.06f

    fun viewOf(pose: RoomPose): RoomView = when (pose) {
        RoomPose.IDLE, RoomPose.EXCITED -> RoomView.FRONT
        RoomPose.SLEEPY -> RoomView.SIDE
        RoomPose.DOWN -> RoomView.BACK
    }

    /** 躺平进度 0→1 对应的旋转角度（0° → −88°） */
    fun tiltOf(lie: Float): Float = LIE_TILT_DEG * lie.sane(0f).coerceIn(0f, 1f)

    /** 躺平时的整体缩放（1 → 0.86），防止横过来顶出屏幕 */
    fun lieScaleOf(lie: Float): Float {
        val t = lie.sane(0f).coerceIn(0f, 1f)
        return 1f - (1f - LIE_SHRINK) * t
    }

    fun desaturateOf(pose: RoomPose): Float =
        if (pose == RoomPose.DOWN) DOWN_DESATURATION else 0f

    /**
     * 角色拿到的亮度（0 ~ 1）。
     *
     * 房间遮罩 alpha 0.60（犯困）→ 角色亮度 0.67；平静遮罩 0 → 1.00。
     * 不做成「和房间一样黑」是因为她得看得见 —— 屋子黑透了还能看见她缩在那儿，
     * 比一片全黑更有信息。
     */
    fun figureBrightnessOf(scrimAlpha: Float): Float =
        (1f - scrimAlpha.sane(0f).coerceIn(0f, 1f) * FIGURE_LIGHT_SHARE).coerceIn(0f, 1f)

    /**
     * 生成 4×5 颜色矩阵（Compose `ColorMatrix` 的 20 个值）。
     *
     * 三步按顺序乘：**饱和度 → 亮度 → 色温**。
     *
     * - 饱和度用标准 luminance 加权（0.299/0.587/0.114），`s=0` 时三通道并成灰度；
     * - 亮度是三个通道同比缩放；
     * - 色温只微调 R/B 两端（暖 +R −B，冷反之），绿通道不动 —— 动绿会发黄。
     *
     * @param brightness 0~1
     * @param warmth     −1 冷 ~ +1 暖
     * @param desaturate 0~1（0.62 大约剩三成颜色）
     */
    fun colorMatrixOf(brightness: Float, warmth: Float, desaturate: Float): FloatArray {
        val b = brightness.sane(1f).coerceIn(0f, 1f)
        val w = warmth.sane(0f).coerceIn(-1f, 1f)
        val s = (1f - desaturate.sane(0f).coerceIn(0f, 1f)).coerceIn(0f, 1f)
        val rGain = b * (1f + WARMTH_GAIN * w)
        val gGain = b
        val bGain = b * (1f - WARMTH_GAIN * w)

        val lr = 0.299f
        val lg = 0.587f
        val lb = 0.114f
        val inv = 1f - s

        return floatArrayOf(
            (lr * inv + s) * rGain, lg * inv * rGain, lb * inv * rGain, 0f, 0f,
            lr * inv * gGain, (lg * inv + s) * gGain, lb * inv * gGain, 0f, 0f,
            lr * inv * bGain, lg * inv * bGain, (lb * inv + s) * bGain, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
    }

    /**
     * 脚下影子的形状：(宽度比例, alpha)。
     *
     * 雀跃悬浮时影子缩小变淡（离地了），躺平后拉长变宽（摊在地上）。
     * 影子是「她在房间里」最便宜的证据 —— 没有影子，立绘看着像贴纸。
     */
    fun shadowOf(hover: Float, lie: Float): ShadowShape {
        val h = hover.sane(0f).coerceIn(0f, 1f)
        val l = lie.sane(0f).coerceIn(0f, 1f)
        val width = 0.52f + 0.34f * l - 0.16f * h
        val alpha = (0.30f - 0.22f * h) * (1f - 0.22f * l)
        return ShadowShape(width.coerceIn(0.18f, 0.95f), alpha.coerceIn(0f, 0.40f))
    }

    /** @param widthScale 相对角色可见宽度的倍数 @param alpha 0~1 */
    data class ShadowShape(val widthScale: Float, val alpha: Float)

    /**
     * Zzz 飘在画框里的哪个位置（比例坐标，左上角为原点）。
     *
     * ## 为什么不放在会旋转的那个框里
     *
     * 一开始 Zzz 是 `align(TopEnd)` 挂在角色框内的，想着「框转了它就跟着转到头顶」。
     * 实测算下来：框绕脚底转 88° 后，右上角会飞到 (−105, 429) —— **屏幕外面**。
     * 因为旋转支点在脚底，离角落最远。
     *
     * 所以 Zzz 单独摆：站着时在头顶右上方，躺下后移到画面左侧（她的头在那儿）。
     * 用比例而不是 dp，是为了换屏幕后仍然贴着头。
     */
    data class ZzzAnchor(val xRatio: Float, val yRatio: Float)

    fun zzzAnchorOf(lie: Float): ZzzAnchor {
        val t = lie.sane(0f).coerceIn(0f, 1f)
        return ZzzAnchor(
            xRatio = 0.82f + (0.20f - 0.82f) * t,
            yRatio = -0.05f + (0.52f - (-0.05f)) * t
        )
    }

    // ---------------------------------------------------------------- 小工具

    /**
     * 把 NaN / Infinity 换成 [fallback]。
     *
     * **`coerceIn` 对 NaN 是失效的**：它的实现是两个 `<` / `>` 比较，NaN 跟谁比都是
     * false，于是原样返回 NaN。NaN 一路流进 `graphicsLayer` 会让这一帧整层不画 ——
     * 表现是「她突然消失一帧」，极难复现。宁可先归一化再钳制。
     */
    private fun Float.sane(fallback: Float): Float = if (isFinite()) this else fallback
}
