package com.nexus.agent.system

import android.os.Build
import android.util.Log

/**
 * ROM 画像 —— 只回答一个问题：「这台机器是不是那类特别爱杀后台的定制系统」。
 *
 * ## 为什么要单独认
 *
 * MIMU（和顺）这类定制 ROM 有三个共同点，全都直接打在这套 App 的命门上：
 *  1. **后台保活极严**：无障碍服务、前台服务都会被休眠策略掐掉，
 *     表现是「昨天还能读屏，今天一开就说无障碍未连接」；
 *  2. **自启动默认关**：Shizuku / Stellar 拉起不来 → 动作类工具全军覆没；
 *  3. **电池优化列表不标准**：通用的「去电池优化」按钮跳不到正确页面。
 *
 * 所以对这类机型不能只给通用引导，得给**专属三步**（自启动 / 锁后台 / 电池无限制），
 * 并且允许它退化成「纯数字生命」—— 只能聊天，不能操作手机。
 *
 * ## 识别依据
 *
 * 主判据是 [Build.DISPLAY]（定制 ROM 一般会把自家标识写进去，例如 `MIMU_...`），
 * 副判据是 [Build.BRAND] / [Build.MANUFACTURER]（有些包只在品牌字段写「和顺」）。
 * 判定放在纯函数 [matches] 里，方便单测直接喂字符串。
 */
object RomProfile {

    private const val TAG = "RomProfile"

    /** 定制系统标识（大写比较） */
    private const val MIMU = "MIMU"

    /** 中文厂商标识 */
    private const val HESHUN = "和顺"

    /** 当前机器的 DISPLAY 串（真机为空的情况也有，统一兜成空串） */
    fun display(): String = runCatching { Build.DISPLAY.orEmpty() }.getOrDefault("")

    /** 当前机器是不是 MIMU / 和顺 定制系统 */
    fun isMimu(): Boolean {
        val hit = matches(display()) ||
            matches(runCatching { Build.BRAND.orEmpty() }.getOrDefault("")) ||
            matches(runCatching { Build.MANUFACTURER.orEmpty() }.getOrDefault(""))
        if (hit) Log.i(TAG, "识别到定制 ROM：DISPLAY=${display()}")
        return hit
    }

    /** 纯判定：DISPLAY / 品牌串里出现 MIMU（任意大小写）或「和顺」 */
    internal fun matches(raw: String?): Boolean {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return false
        if (s.contains(HESHUN)) return true
        return s.uppercase().contains(MIMU)
    }

    /** 给用户看的系统名（提示语里用） */
    fun label(): String = if (isMimu()) "MIMU（和顺）定制系统" else "当前系统"
}
