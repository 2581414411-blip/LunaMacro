package com.nexus.agent.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 界面指纹单测（0.9.13）。
 *
 * 这一层决定的不是「好不好用」，而是「会不会在错的界面上照旧点」。
 * 所以重点不是「能算出指纹」，而是三道排除规则一条都不能松：
 * 输入框不进指纹、超长正文不进指纹、计数不产生新指纹。
 */
class ScreenFingerprintTest {

    private fun ui(vararg elems: String) = elems.joinToString(",", prefix = "[", postfix = "]")

    private val searchButton = """{"text":"搜索","type":"button","center":[100,200]}"""
    private val discover = """{"text":"发现","type":"text","center":[10,20]}"""
    private val chatTitle = """{"text":"张三","type":"view","center":[1,2],"id":"chat_title"}"""

    @Test
    fun `抽出文字与控件 id`() {
        val keys = ScreenFingerprint.keysOf(ui(searchButton, discover, chatTitle))
        assertTrue(keys.contains("t:搜索"))
        assertTrue(keys.contains("t:发现"))
        assertTrue(keys.contains("i:chat_title"))
    }

    @Test
    fun `排除输入框里的用户内容`() {
        val input = """{"text":"我输入的那句话","type":"edit","center":[3,4],"editable":true,"id":"search_input"}"""
        val keys = ScreenFingerprint.keysOf(ui(searchButton, discover, chatTitle, input))
        assertFalse("输入框的 text 绝不能进指纹", keys.any { it.contains("我输入的那句话") })
        // 但它的 id 仍然可以进来 —— id 是结构，不是内容
        assertTrue(keys.contains("i:search_input"))
    }

    @Test
    fun `超长正文不进指纹`() {
        val article = """{"text":"这是一篇很长很长的文章标题它每次都不一样所以不能进指纹","type":"view","center":[1,2]}"""
        val keys = ScreenFingerprint.keysOf(ui(searchButton, discover, chatTitle, article))
        assertFalse(keys.any { it.contains("很长很长") })
    }

    @Test
    fun `计数变化不产生新指纹`() {
        val a = ui("""{"text":"3 条未读","type":"view","center":[1,2]}""", searchButton, discover)
        val b = ui("""{"text":"12 条未读","type":"view","center":[1,2]}""", searchButton, discover)
        assertEquals(ScreenFingerprint.keysOf(a), ScreenFingerprint.keysOf(b))
        assertEquals(
            ScreenFingerprint.of("com.tencent.mm", a),
            ScreenFingerprint.of("com.tencent.mm", b)
        )
    }

    @Test
    fun `节点顺序变化不影响指纹`() {
        val a = ui(searchButton, discover, chatTitle)
        val b = ui(chatTitle, discover, searchButton)
        assertEquals(ScreenFingerprint.of("com.tencent.mm", a), ScreenFingerprint.of("com.tencent.mm", b))
    }

    @Test
    fun `关键节点太少时不产指纹`() {
        assertEquals("", ScreenFingerprint.of("com.a", ui(searchButton)))
        assertEquals("", ScreenFingerprint.of("com.a", null))
        assertEquals("", ScreenFingerprint.of("com.a", "[]"))
    }

    @Test
    fun `不同应用同布局算不同指纹`() {
        val same = ui(searchButton, discover, chatTitle)
        assertNotEquals(
            ScreenFingerprint.of("com.tencent.mm", same),
            ScreenFingerprint.of("com.taobao", same)
        )
    }

    @Test
    fun `相似度按关键节点重合算`() {
        val base = ScreenFingerprint.keysOf(ui(searchButton, discover, chatTitle))
        // 多一个按钮：仍然很像
        val withExtra = ScreenFingerprint.keysOf(ui(searchButton, discover, chatTitle, """{"text":"返回","type":"button","center":[9,9]}"""))
        assertTrue(ScreenFingerprint.similarity(base, withExtra) >= 0.6f)

        // 完全不同的界面：不想像
        val other = ScreenFingerprint.keysOf(
            ui("""{"text":"购物车","type":"button","center":[1,1]}""", """{"text":"我的","type":"button","center":[2,2]}""", """{"text":"首页","type":"button","center":[3,3]}""")
        )
        assertTrue(ScreenFingerprint.similarity(base, other) < 0.02f)
    }

    @Test
    fun `空集合相似度为 0`() {
        assertEquals(0f, ScreenFingerprint.similarity(emptyList(), listOf("t:a")), 0.001f)
        assertEquals(0f, ScreenFingerprint.similarityOf("", ""), 0.001f)
    }

    @Test
    fun `落库字符串能原样解析回来`() {
        val keys = ScreenFingerprint.keysOf(ui(searchButton, discover, chatTitle))
        val csv = ScreenFingerprint.keyString(keys)
        assertEquals(keys.toSet(), ScreenFingerprint.parseKeys(csv))
    }
}
