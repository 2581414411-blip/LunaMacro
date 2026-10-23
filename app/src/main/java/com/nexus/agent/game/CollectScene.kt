package com.nexus.agent.game

/**
 * 分步采集向导流程。
 *
 * 每步都需用户点「开始」，不会让用户赶：
 * 点击开始 → 浮窗提示第 1 步 → 用户准备好点「开始」→ 采集 → 采满自动停 →
 * 提示下一步 → 用户准备好再点「开始」→ … 循环到最后一步完成。
 * 每帧 label 写入当前步骤的 [sceneKey]，标注与 SFT 转换据此精准过滤。
 */
object CollectFlow {

    /**
     * 单个采集步骤。
     *
     * @param title       浮窗显示的步骤标题（短）
     * @param hint        引导语（告诉用户该做什么操作）
     * @param targetCount 目标采集张数；0 表示纯引导步骤（不采集，点「开始」即进入下一步）
     * @param sceneKey    写入 label.json 的 scene 字段（SFT 过滤依据）
     */
    data class Step(
        val title: String,
        val hint: String,
        val targetCount: Int,
        val sceneKey: String
    )

    /**
     * 预设原神采集流程（每步不同张数，按场景重要性分配）。
     *
     * 总张数约 210 张：战斗最多（动作最丰富、误差来源最大），界面类偏少。
     */
    val STEPS: List<Step> = listOf(
        Step("打开原神", "请先打开原神游戏，进入后点「开始」", 0, "launch"),
        Step("主界面停留", "停留在主界面，浏览各入口、活动、商城等布局，准备好后点「开始」", 25, "main_menu"),
        Step("战斗操作", "进入战斗：攻击、闪避、释放技能、切换角色，尽量覆盖多动作", 80, "battle"),
        Step("地图移动", "在大地图上跑动、转向、传送、探索地形", 40, "map_move"),
        Step("背包界面", "打开背包：查看装备、材料、切换物品页签", 25, "inventory"),
        Step("任务/剧情", "推进任务与剧情对话，选择选项、领取奖励", 40, "quest"),
    )
}
