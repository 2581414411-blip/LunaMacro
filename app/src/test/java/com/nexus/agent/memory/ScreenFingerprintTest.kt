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

    // ==================== 0.9.34：结构 token / 档位 / 阈值 ====================

    /** 带 dep/sib 的节点（UiTreeParser 0.9.34 之后的输出格式） */
    private fun node(
        text: String,
        type: String,
        dep: Int,
        sib: Int,
        id: String? = null
    ): String {
        val idPart = if (id.isNullOrBlank()) "" else ",\"id\":\"$id\""
        return """{"text":"$text","type":"$type","center":[1,2],"dep":$dep,"sib":$sib$idPart}"""
    }

    @Test
    fun `无 id 的节点用结构坐标兜底`() {
        val keys = ScreenFingerprint.keysOf(
            ui(node("确定", "button", 4, 2), node("取消", "button", 4, 3), node("返回", "view", 2, 0))
        )
        assertTrue("同 dep 同类的第二项是 #4.2", keys.contains("c:button#4.2"))
        assertTrue(keys.contains("c:button#4.3"))
        assertTrue("不同 dep 序号各自从 0 起", keys.contains("c:view#2.0"))
    }

    @Test
    fun `有 id 的节点不再出结构 token`() {
        val keys = ScreenFingerprint.keysOf(ui(node("发送", "button", 5, 1, "send_btn")))
        assertTrue(keys.contains("i:send_btn"))
        assertFalse("有 id 就不该再出 c:，否则一个节点占两个来源位", keys.any { it.startsWith("c:") })
    }

    @Test
    fun `缺 dep 或 sib 时不编结构坐标`() {
        // 老格式 / 手写数据没有这两个字段：宁可少一个 token，也不编一个假坐标
        val keys = ScreenFingerprint.keysOf(ui(searchButton))
        assertFalse(keys.any { it.startsWith("c:") })
    }

    @Test
    fun `id 多的界面判 id 档`() {
        val keys = ScreenFingerprint.keysOf(
            ui(
                node("发送", "button", 5, 1, "send_btn"),
                node("取消", "button", 5, 2, "cancel_btn"),
                node("返回", "button", 3, 0, "back_btn")
            )
        )
        assertEquals(ScreenFingerprint.TIER_ID, ScreenFingerprint.tierOf(keys))
        assertTrue(ScreenFingerprint.of("com.a", ui(
            node("发送", "button", 5, 1, "send_btn"),
            node("取消", "button", 5, 2, "cancel_btn"),
            node("返回", "button", 3, 0, "back_btn")
        )).startsWith("fp-v1:id#"))
    }

    @Test
    fun `id 全空的界面判 cls 档`() {
        val json = ui(
            node("发送", "button", 5, 1),
            node("取消", "button", 5, 2),
            node("返回", "button", 3, 0)
        )
        val keys = ScreenFingerprint.keysOf(json)
        assertEquals(ScreenFingerprint.TIER_CLS, ScreenFingerprint.tierOf(keys))
        val fp = ScreenFingerprint.of("com.a", json)
        assertTrue(fp.startsWith("fp-v1:cls#"))
        assertFalse("cls 档不许进经验库", ScreenFingerprint.isStorable(fp))
    }

    @Test
    fun `文本 token 不参与档位分母`() {
        // 3 个带 id 的节点 + 一堆纯文本节点。
        // 分母只数来源 token（i:/c:）时：3/3 = 1.0 → id 档。
        // 若把 t: 也算进分母：3/13 ≈ 0.23 → 会被误判成 cls 档，于是这类界面永远
        // 进不了经验库 —— 文字多不是"没有 id"，这两件事必须分开看。
        // 注意用不同字：数字会被归一成 `#`，十行"标签1/标签2"归一后只剩一个 token
        val texts = listOf("甲", "乙", "丙", "丁", "戊", "己", "庚", "辛", "壬", "癸").map {
            """{"text":"$it","type":"text","center":[1,1]}"""
        }
        val keys = ScreenFingerprint.keysOf(
            ui(
                node("发送", "button", 5, 1, "send_btn"),
                node("取消", "button", 5, 2, "cancel_btn"),
                node("返回", "button", 3, 0, "back_btn"),
                *texts.toTypedArray()
            )
        )
        assertEquals(ScreenFingerprint.TIER_ID, ScreenFingerprint.tierOf(keys))
    }

    @Test
    fun `落库时过滤结构 token`() {
        val keys = ScreenFingerprint.keysOf(
            ui(node("发送", "button", 5, 1), node("取消", "button", 5, 2), node("返回", "button", 3, 0))
        )
        assertTrue(keys.any { it.startsWith("c:") })
        val csv = ScreenFingerprint.keyString(keys)
        assertFalse("c: 只能用于同会话，不许落库", csv.contains("c:"))
    }

    @Test
    fun `跨档不算同一界面`() {
        assertFalse(
            "id 档和 cls 档是两个 key 空间，比出来的相似度没有意义",
            ScreenFingerprint.sameTier("fp-v1:id#a1b2c3", "fp-v1:cls#a1b2c3")
        )
        assertTrue(ScreenFingerprint.sameTier("fp-v1:id#a1b2c3", "fp-v1:id#ffffff"))
        assertFalse("空指纹没法比", ScreenFingerprint.sameTier("", "fp-v1:id#a1b2c3"))
    }

    @Test
    fun `阈值按档位取`() {
        assertEquals(0.80f, ScreenFingerprint.thresholdOf("fp-v1:id#x"), 0.001f)
        assertEquals(0.70f, ScreenFingerprint.thresholdOf("fp-v1:cls#x"), 0.001f)
        // 老格式没有标签：按 id 档走保守阈值，别放宽
        assertEquals(0.80f, ScreenFingerprint.thresholdOf("com.tencent.mm#abcdef123456"), 0.001f)
        assertEquals(0.80f, ScreenFingerprint.thresholdOf(""), 0.001f)
    }

    @Test
    fun `指纹带版本与档位前缀`() {
        val fp = ScreenFingerprint.of("com.a", ui(searchButton, discover, chatTitle))
        assertTrue("格式必须与协议一致", fp.startsWith("fp-v1:id#"))
        assertEquals("fp-v1: 之后、# 之前就是档位", ScreenFingerprint.TIER_ID, ScreenFingerprint.tierOfFingerprint(fp))
    }

    @Test
    fun `可编辑标记带空格也能识别`() {
        // 手写 JSON / 别的序列化器会带空格；只认 `"editable":true` 会漏
        val withSpace = """{"text":"我的密码","type":"edit","center":[3,4],"editable": true,"id":"pwd"}"""
        val keys = ScreenFingerprint.keysOf(ui(searchButton, discover, chatTitle, withSpace))
        assertFalse(keys.any { it.contains("我的密码") })
    }
}
