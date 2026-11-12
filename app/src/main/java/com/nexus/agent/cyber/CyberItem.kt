package com.nexus.agent.cyber

/**
 * 一条「她在网上看到的东西」（0.9.26 批次 F）。
 *
 * ## 为什么只有这几个字段
 *
 * 电视屏幕就巴掌大，一条内容能显示的只有**一张图和一行字**。
 * 抓回来一堆结构化字段（作者、播放量、弹幕数、分区…）既放不下，
 * 又会让模型在生成弹幕时抓错重点 —— 用户要的是「这个猫好搞笑」，
 * 不是「该视频播放量 128 万」。
 *
 * @property id       稳定标识（源 + 原始 id），用于去重
 * @property title    标题，屏幕上最多显示两行
 * @property source   来源中文名（「B站热门」「微博热搜」），角落那行小字
 * @property imageUrl 缩略图；文字类内容为 null（纯文字卡片）
 * @property pageUrl  原文链接，分享出去时附在配文后面
 * @property hot      热度文案（「128 万播放」「热搜第 3」），可空
 */
data class CyberItem(
    val id: String,
    val title: String,
    val source: String,
    val imageUrl: String?,
    val pageUrl: String?,
    val hot: String? = null
) {
    /** 有没有图。有图走大图卡片，没图走纯文字卡片 */
    val hasImage: Boolean get() = !imageUrl.isNullOrBlank()
}
