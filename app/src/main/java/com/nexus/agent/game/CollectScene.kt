package com.nexus.agent.game

/**
 * 采集引导场景。
 *
 * 训练数据误差大的根因：采集时「无差别」截图 + 记录触摸坐标，缺少对用户操作的引导，
 * 导致采集到大量无意图/低质量帧。通过预定义场景，让用户「有目的地操作」，
 * 每帧数据都带上明确的 [key]（scene/intent 标签），标注与 SFT 转换据此精准过滤。
 */
object CollectScenes {

    /**
     * 单个采集引导场景。
     *
     * @param key      标签键（写入 label.json 的 scene 字段，也作为 SFT 过滤依据）
     * @param title    浮窗显示的标题（短）
     * @param hint     浮窗显示的引导语（告诉用户该怎么操作）
     */
    data class Scene(val key: String, val title: String, val hint: String)

    /** 预设场景清单（覆盖游戏常见操作场景，顺序即采集引导顺序） */
    val ALL: List<Scene> = listOf(
        Scene("main_menu", "主界面", "停留在主界面，浏览各入口按钮、活动、商城等布局"),
        Scene("battle", "战斗", "进入战斗：攻击、闪避、释放技能、切人，尽量覆盖多动作"),
        Scene("map_move", "地图移动", "在大地图上跑动、转向、传送、探索地形"),
        Scene("inventory", "背包", "打开背包：查看装备、材料、切换物品页签"),
        Scene("character", "角色/编队", "查看角色面板、装备详情、编队/切换角色"),
        Scene("dialog", "对话/剧情", "推进剧情对话，选择对话选项、跳过动画"),
        Scene("shop", "商店/兑换", "进入商店：浏览商品、兑换、购买、刷新"),
        Scene("quest", "任务/指引", "查看任务列表、追踪任务、领取奖励"),
        Scene("skill_ui", "技能界面", "打开技能/天赋界面：查看技能、升级、加点"),
    )

    /**
     * 根据标签键查找场景；未找到返回 null。
     */
    fun byKey(key: String): Scene? = ALL.firstOrNull { it.key == key }
}
