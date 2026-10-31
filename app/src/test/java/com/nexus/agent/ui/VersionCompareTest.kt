package com.nexus.agent.ui

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
}
