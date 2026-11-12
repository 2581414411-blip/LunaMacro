package com.nexus.agent.ui.room

import com.nexus.agent.data.db.RoomItem

/**
 * 装修用的颜色词表（0.9.19 批次 C）。
 *
 * ## 为什么不让模型直接给 ARGB
 *
 * 「给我个 0xB8503F 的沙发」听起来很灵活，实际是两次退化：
 * 一是模型算不准色值（它给的往往是上一个项目里的品牌色）；
 * 二是**没有名字就没法回话**——她只能说「改好了」，说不出「换成红色了」。
 * 词表把颜色变成**有限名词**，顺带让「这个红不好看」有得聊。
 *
 * ## 为什么低饱和
 *
 * 房间的底色是米的墙、橡木的地。纯红纯蓝摆进去像贴纸。这里所有色都掺了灰，
 * 跟极简色板同一个调子 —— 第一眼看不出「有颜色的家具」，只觉得屋里东西不寡。
 */
object RoomPalette {

    /** @param name 提示词里给的名字 @param zh 回话用的中文 @param argb 不透明色 */
    data class Swatch(val name: String, val zh: String, val argb: Int)

    /**
     * 提示词里**公示**的颜色。
     *
     * 比任务书里的 `red/blue/green/yellow/pink/dark` 多一个 `gray`：任务书自己给的
     * 主题示例就是 `{"wall":"dark","floor":"gray"}`，只公示六个的话，模型照抄示例
     * 就会被自己的白名单拒掉。公示集必须覆盖示例用到的每一个词。
     */
    val SWATCHES: List<Swatch> = listOf(
        Swatch("red", "红色", 0xFFB8503F.toInt()),
        Swatch("blue", "蓝色", 0xFF5C7C9E.toInt()),
        Swatch("green", "绿色", 0xFF6E8F6E.toInt()),
        Swatch("yellow", "黄色", 0xFFD9B45C.toInt()),
        Swatch("pink", "粉色", 0xFFD98C9C.toInt()),
        Swatch("dark", "深色", 0xFF3E3A3A.toInt()),
        Swatch("gray", "灰色", 0xFF9A9A9A.toInt())
    )

    /**
     * 额外接受、但不写进提示词的词 —— 用户嘴里会蹦出来的那些。
     *
     * 公开是为了让 [RoomIntent] 的颜色扫描和这里共用一份词表：
     * 两处各抄一份，早晚会出现「关键词认得出木色、校验器认不出」的裂缝。
     */
    val EXTRA: List<Swatch> = listOf(
        Swatch("white", "白色", 0xFFEDE7DC.toInt()),
        Swatch("wood", "木色", 0xFFB08A62.toInt()),
        Swatch("navy", "深蓝色", 0xFF2F3B4E.toInt())
    )

    private val BY_NAME: Map<String, Swatch> =
        (SWATCHES + EXTRA).associateBy { it.name }

    /**
     * 文本里可能出现的**所有写法**，长的在前。
     *
     * 这里列的是「表面形式」（surface form），不是语义分类 —— 因为中文里
     * 「深蓝」和「蓝色」是两个词，用户不会说「把墙换成深蓝色」再等着系统去
     * 剥掉那个「色」字。少列一种写法，那句话就白说了。
     *
     * 长的在前 + 命中后挖空（见 [hits]），保证「深蓝」不会被「蓝」抢先，
     * 也保证「深蓝」不会同时被算成 blue 和 navy 两个颜色。
     */
    private val SURFACES: List<Pair<String, String>> = listOf(
        "深蓝色" to "navy", "藏蓝色" to "navy", "深蓝" to "navy", "藏蓝" to "navy",
        "navy" to "navy",
        "红色" to "red", "红" to "red", "red" to "red",
        "蓝色" to "blue", "蓝" to "blue", "blue" to "blue",
        "绿色" to "green", "绿" to "green", "green" to "green",
        "黄色" to "yellow", "黄" to "yellow", "yellow" to "yellow",
        "粉色" to "pink", "粉红" to "pink", "粉" to "pink", "pink" to "pink",
        "深色" to "dark", "暗色" to "dark", "黑" to "dark", "dark" to "dark",
        "灰色" to "gray", "灰" to "gray", "gray" to "gray", "grey" to "gray",
        "白色" to "white", "白" to "white", "white" to "white",
        "木色" to "wood", "原木" to "wood", "wood" to "wood"
    ).sortedByDescending { it.first.length }

    /** 一处命中：在原文里的位置、长度、以及它到底算什么颜色 */
    data class Hit(val index: Int, val length: Int, val name: String)

    /**
     * 扫出文本里所有的颜色词，按出现位置排序。
     *
     * 命中后把那几个字挖成 `\u0000`（长度不变，所以索引仍然对得上原文），
     * 避免「深蓝色」被「蓝色」「蓝」各算一次。
     */
    fun hits(text: String): List<Hit> {
        if (text.isEmpty()) return emptyList()
        val buf = StringBuilder(text.lowercase())
        val out = ArrayList<Hit>()
        for ((surface, name) in SURFACES) {
            var from = 0
            while (true) {
                val i = buf.indexOf(surface, from)
                if (i < 0) break
                out.add(Hit(i, surface.length, name))
                for (k in surface.indices) buf.setCharAt(i + k, '\u0000')
                from = i + surface.length
            }
        }
        return out.sortedBy { it.index }
    }

    /** 认不出返回 null —— 调用方决定「退回默认色」还是「整条拒绝」 */
    fun argbOf(raw: String?): Int? =
        hits(raw.orEmpty()).firstOrNull()?.let { BY_NAME[it.name]?.argb }

    fun nameOf(raw: String?): String? = hits(raw.orEmpty()).firstOrNull()?.name

    /** 回话用的中文色名；认不出返回 null（那就不提颜色） */
    fun zhOf(argb: Int?): String? =
        argb?.let { v -> (SWATCHES + EXTRA).firstOrNull { it.argb == v }?.zh }

    /**
     * 某种家具「没说颜色」时的颜色。
     *
     * 刻意跟 [RoomDefaults] 的种子色一致：用户说「加个沙发」拿到的沙发，
     * 应该和第一次进屋看到的那只沙发是同一个颜色 —— 否则「多了一只奇怪的沙发」。
     */
    fun defaultTintOf(kind: String): Int = when (kind.trim().lowercase()) {
        RoomItem.KIND_SOFA -> 0xFFB98F86.toInt()
        RoomItem.KIND_BED -> 0xFF9FB2C6.toInt()
        RoomItem.KIND_DESK -> 0xFFB08A62.toInt()
        RoomItem.KIND_LAMP -> 0xFFD8B26A.toInt()
        RoomItem.KIND_SHELF -> 0xFF9A8571.toInt()
        RoomItem.KIND_PLANT -> 0xFF7FA37F.toInt()
        RoomItem.KIND_RUG -> 0xFFD9C4A8.toInt()
        RoomItem.KIND_POSTER -> 0xFFA98C7A.toInt()
        else -> RoomItem.DEFAULT_TINT
    }
}
