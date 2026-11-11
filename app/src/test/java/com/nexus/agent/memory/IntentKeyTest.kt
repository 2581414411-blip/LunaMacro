package com.nexus.agent.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 意图归一化单测（0.9.13）。
 *
 * 两条方向都要钉：
 *  - **不能太严**：只差客套话、标点的同一句话必须认成同一个意图，否则记忆永远打不中；
 *  - **不能太松**：主任务不同的两句话必须分开，否则会拿「打开微信」的记忆去执行
 *    「打开淘宝看看购物车」。
 */
class IntentKeyTest {

    @Test
    fun `剥掉客套前缀与句尾语气词`() {
        assertEquals("打开微信", IntentKey.normalize("帮我打开微信吧"))
        assertEquals("打开微信", IntentKey.normalize("麻烦你打开微信。"))
        assertEquals("打开微信", IntentKey.normalize("  打开微信  "))
    }

    @Test
    fun `标点与空白全部抹掉`() {
        assertEquals("打开微信搜订阅号", IntentKey.normalize("打开 微信，搜「订阅号」！"))
    }

    @Test
    fun `同一句话不同说法算同一个意图`() {
        assertTrue(IntentKey.same("帮我打开微信", "打开微信"))
        assertTrue(IntentKey.similar("打开微信吧", "打开微信"))
    }

    @Test
    fun `主任务不同就不算同一个意图`() {
        assertFalse(IntentKey.similar("打开微信", "打开淘宝看看购物车"))
        assertFalse(IntentKey.similar("打开微信", "打开微信搜订阅号"))
        assertFalse(IntentKey.similar("", "打开微信"))
    }

    @Test
    fun `二元组相似度对称且自反`() {
        val a = IntentKey.normalize("打开微信")
        val b = IntentKey.normalize("打开微信聊天")
        assertEquals(1f, IntentKey.similarity(a, a), 0.001f)
        assertEquals(IntentKey.similarity(a, b), IntentKey.similarity(b, a), 0.001f)
    }

    @Test
    fun `单字意图不炸`() {
        assertEquals("查", IntentKey.normalize("查"))
        assertEquals(setOf("查"), IntentKey.bigrams("查"))
    }

    @Test
    fun `空输入归一化后为空`() {
        assertEquals("", IntentKey.normalize(""))
        assertEquals("", IntentKey.normalize("，。！"))
        assertFalse(IntentKey.similar("", ""))
    }
}
