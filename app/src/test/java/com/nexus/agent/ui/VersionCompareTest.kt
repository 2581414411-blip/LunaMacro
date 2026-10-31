package com.nexus.agent.ui

import com.nexus.agent.util.UpdateChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版本号比较（0.8.0 起带上预发布后缀）。
 *
 * 起因很实际：0.8.0-alpha1 装上去之后，如果比较逻辑只认数字段，
 * 「0.8.0 正式版」和「0.8.0-alpha1」会被判成相等 —— 尝鲜的人永远收不到正式版。
 */
class VersionCompareTest {

    @Test
    fun `普通 patch 递增`() {
        assertTrue(isNewerVersion("0.7.13", "0.7.12"))
        assertFalse(isNewerVersion("0.7.12", "0.7.12"))
        assertFalse(isNewerVersion("0.7.11", "0.7.12"))
    }

    @Test
    fun `跨大版本`() {
        assertTrue(isNewerVersion("0.8.0", "0.7.12"))
        assertTrue(isNewerVersion("1.0.0", "0.8.0-alpha1"))
    }

    @Test
    fun `预发布比同号正式版旧`() {
        assertTrue(isNewerVersion("0.8.0", "0.8.0-alpha1"))
        assertFalse(isNewerVersion("0.8.0-alpha1", "0.8.0"))
    }

    @Test
    fun `预发布之间按前缀和序号比`() {
        assertTrue(isNewerVersion("0.8.0-alpha2", "0.8.0-alpha1"))
        assertTrue(isNewerVersion("0.8.0-alpha10", "0.8.0-alpha2"))
        assertTrue(isNewerVersion("0.8.0-beta1", "0.8.0-alpha9"))
        assertFalse(isNewerVersion("0.8.0-alpha1", "0.8.0-alpha2"))
    }

    @Test
    fun `alpha 之上还有更高的数字版`() {
        assertTrue(isNewerVersion("0.8.1", "0.8.0-alpha1"))
        assertFalse(isNewerVersion("0.8.0-alpha1", "0.8.1"))
    }

    @Test
    fun `带 v 前缀与缺段也能处理`() {
        assertTrue(isNewerVersion("v0.8.0", "0.7.12"))
        assertTrue(isNewerVersion("0.8", "0.7.12"))
        assertFalse(isNewerVersion("0.7", "0.7.12"))
    }

    /**
     * 防止版本比较再被拆成两份实现。
     *
     * 曾经 [isNewerVersion] 和 [UpdateChecker.isNewer] 各写一套，只修了 UI 那份，
     * 于是「0.8.0-alpha2 比 0.8.0-alpha1 新」在一条链路上成立、在另一条上不成立 ——
     * 实际表现就是：装了 alpha1，点「检查更新」永远提示已是最新，实验版根本更新不了。
     * 现在 UI 层只做转调，这条用例把两边绑死。
     */
    @Test
    fun `更新检查与UI用同一份版本比较`() {
        val pairs = listOf(
            "0.8.0-alpha2" to "0.8.0-alpha1",  // 关键回归点
            "0.8.0-alpha1" to "0.8.0-alpha2",
            "0.8.0" to "0.8.0-alpha1",
            "0.8.0-alpha1" to "0.8.0",
            "0.7.12" to "0.7.11",
            "0.7.11" to "0.7.12"
        )
        pairs.forEach { (remote, current) ->
            assertEquals(
                "($remote vs $current) 两条链路结论不一致",
                UpdateChecker.isNewer(remote, current),
                isNewerVersion(remote, current)
            )
        }
        // 直接对唯一实现断言：这个组合被判成 false 时，实验版更新就是死路
        assertTrue(UpdateChecker.isNewer("0.8.0-alpha2", "0.8.0-alpha1"))
    }
}
