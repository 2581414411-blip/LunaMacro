package com.nexus.agent.persona

import com.nexus.agent.data.db.MemoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 长期记忆提取器的纯逻辑测试（0.8.0）。
 *
 * 只测不碰网络的三个函数：[MemoryExtractor.parseFacts]、[MemoryExtractor.classify]、
 * [MemoryExtractor.weightOf]。真正的 LLM 调用在真机上验证。
 *
 * 为什么解析要自己写而不是用 `org.json`：单测跑在纯 JVM 上，`org.json` 来自
 * android.jar 的空壳实现（方法全返回默认值），用它解析永远得到空数组 ——
 * 那这个最该被覆盖的函数就等于没测。
 */
class MemoryExtractorTest {

    @Test
    fun `标准数组能解析`() {
        val facts = MemoryExtractor.parseFacts("""["用户名叫老张","用户喜欢狗"]""")
        assertEquals(listOf("用户名叫老张", "用户喜欢狗"), facts)
    }

    @Test
    fun `代码块包裹也能解析`() {
        val raw = "```json\n[\"用户是程序员\"]\n```"
        assertEquals(listOf("用户是程序员"), MemoryExtractor.parseFacts(raw))
    }

    @Test
    fun `前面有废话也能解析`() {
        val raw = "好的，提取结果如下：[\"用户喜欢熬夜\",\"用户养了只猫\"] 希望有帮助"
        assertEquals(listOf("用户喜欢熬夜", "用户养了只猫"), MemoryExtractor.parseFacts(raw))
    }

    @Test
    fun `空数组返回空列表`() {
        assertTrue(MemoryExtractor.parseFacts("[]").isEmpty())
        assertTrue(MemoryExtractor.parseFacts("没有可提取的信息").isEmpty())
        assertTrue(MemoryExtractor.parseFacts("").isEmpty())
    }

    @Test
    fun `对象形式只取 value 不取 key`() {
        val raw = """[{"fact":"用户名叫老张"},{"fact":"用户是程序员"}]"""
        assertEquals(listOf("用户名叫老张", "用户是程序员"), MemoryExtractor.parseFacts(raw))
    }

    @Test
    fun `重复条目只留一条`() {
        val raw = """["用户喜欢狗","用户喜欢狗","用户喜欢猫"]"""
        assertEquals(listOf("用户喜欢狗", "用户喜欢猫"), MemoryExtractor.parseFacts(raw))
    }

    @Test
    fun `单条超长会被截断`() {
        val long = "啊".repeat(200)
        val facts = MemoryExtractor.parseFacts("[\"$long\"]")
        assertEquals(1, facts.size)
        assertTrue("没有截断：${facts[0].length}", facts[0].length <= 60)
    }

    @Test
    fun `一次最多收五条`() {
        val raw = (1..12).joinToString(",", "[", "]") { "\"事实$it\"" }
        assertEquals(5, MemoryExtractor.parseFacts(raw).size)
    }

    @Test
    fun `转义引号不会截断解析`() {
        val raw = """["用户说\"你好\"是口头禅"]"""
        assertEquals(listOf("""用户说"你好"是口头禅"""), MemoryExtractor.parseFacts(raw))
    }

    // ------------------------------------------------------------- 分类/权重

    @Test
    fun `名字类归到关系`() {
        assertEquals(MemoryEntity.CATEGORY_RELATION, MemoryExtractor.classify("用户名叫老张"))
    }

    @Test
    fun `喜好类归到偏好`() {
        assertEquals(MemoryEntity.CATEGORY_PREFERENCE, MemoryExtractor.classify("用户喜欢狗"))
        assertEquals(MemoryEntity.CATEGORY_PREFERENCE, MemoryExtractor.classify("用户讨厌早起"))
    }

    @Test
    fun `习惯与事件能分开`() {
        assertEquals(MemoryEntity.CATEGORY_HABIT, MemoryExtractor.classify("用户每天凌晨两点睡"))
        assertEquals(MemoryEntity.CATEGORY_EVENT, MemoryExtractor.classify("用户下周要面试"))
        assertEquals(MemoryEntity.CATEGORY_GENERAL, MemoryExtractor.classify("用户住在杭州"))
    }

    @Test
    fun `关系类权重最高、事件类最低`() {
        val relation = MemoryExtractor.weightOf("用户名叫老张")
        val event = MemoryExtractor.weightOf("用户下周要面试")
        assertTrue(relation > event)
        assertTrue(relation <= 1f)
        assertTrue(event > 0f)
    }

    // ------------------------------------------------------- 话题种子（0.9.4）

    @Test
    fun `新格式一次抽出事实和话题`() {
        val raw = """{"facts":["用户名叫老张"],"topics":["明天要体检","最近头疼"]}"""
        val e = MemoryExtractor.parseExtraction(raw)
        assertEquals(listOf("用户名叫老张"), e.facts)
        assertEquals(listOf("明天要体检", "最近头疼"), e.topics)
    }

    @Test
    fun `代码块包裹对象也认`() {
        val raw = "```json\n{\"facts\":[],\"topics\":[\"下周面试\"]}\n```"
        val e = MemoryExtractor.parseExtraction(raw)
        assertTrue(e.facts.isEmpty())
        assertEquals(listOf("下周面试"), e.topics)
    }

    @Test
    fun `模型偷懒只给裸数组时全当事实`() {
        val e = MemoryExtractor.parseExtraction("""["用户喜欢狗"]""")
        assertEquals(listOf("用户喜欢狗"), e.facts)
        assertTrue("没有 topics 段就不该编出话题", e.topics.isEmpty())
    }

    @Test
    fun `只有 facts 时话题为空`() {
        val e = MemoryExtractor.parseExtraction("""{"facts":["用户是程序员"],"topics":[]}""")
        assertEquals(listOf("用户是程序员"), e.facts)
        assertTrue(e.topics.isEmpty())
    }

    @Test
    fun `话题里的方括号不会截断解析`() {
        // 字符串里带 ] 时，按「第一个 ] 就收」会把内容切成半句
        val raw = """{"facts":[],"topics":["明天去看[流浪地球3]"]}"""
        val e = MemoryExtractor.parseExtraction(raw)
        assertEquals(listOf("明天去看[流浪地球3]"), e.topics)
    }

    @Test
    fun `事实最多五条话题最多三条`() {
        val facts = (1..9).joinToString(",", "[", "]") { "\"事实$it\"" }
        val topics = (1..9).joinToString(",", "[", "]") { "\"明天事$it\"" }
        val e = MemoryExtractor.parseExtraction("""{"facts":$facts,"topics":$topics}""")
        assertEquals(5, e.facts.size)
        assertEquals(3, e.topics.size)
    }

    @Test
    fun `空输出不炸`() {
        val e = MemoryExtractor.parseExtraction("")
        assertTrue(e.facts.isEmpty())
        assertTrue(e.topics.isEmpty())
        val e2 = MemoryExtractor.parseExtraction("""{"facts":[],"topics":[]}""")
        assertTrue(e2.facts.isEmpty())
        assertTrue(e2.topics.isEmpty())
    }

    @Test
    fun `计划和身体状况算种子`() {
        assertTrue(MemoryExtractor.looksLikeSeed("明天要体检"))
        assertTrue(MemoryExtractor.looksLikeSeed("下周面试"))
        assertTrue(MemoryExtractor.looksLikeSeed("最近头疼"))
        assertTrue(MemoryExtractor.looksLikeSeed("连续加班到很晚"))
    }

    @Test
    fun `吃过饭看过电影不算种子`() {
        // 这类一次性的事存下来，她就会三天后突然问「火锅好吃吗」
        assertFalse(MemoryExtractor.looksLikeSeed("今天吃了火锅"))
        assertFalse(MemoryExtractor.looksLikeSeed("刚看完一部电影"))
        assertFalse(MemoryExtractor.looksLikeSeed("哈"))
    }

    @Test
    fun `小病一天就过期、计划留更久`() {
        val t0 = 1_800_000_000_000L
        val h = 3_600_000L
        // 头疼这类：24 小时后就不该再问（人早好了）
        assertEquals(t0 + 24 * h, MemoryExtractor.expireAtFor("最近头疼", t0))
        // 明天的事：留到后天，够问一次
        assertEquals(t0 + 36 * h, MemoryExtractor.expireAtFor("明天要体检", t0))
        // 下周的事留 8 天
        assertEquals(t0 + 192 * h, MemoryExtractor.expireAtFor("下周要出差", t0))
        // 生日留一周
        assertEquals(t0 + 168 * h, MemoryExtractor.expireAtFor("快过生日了", t0))
    }

    @Test
    fun `没命中关键词时默认两天`() {
        assertEquals(
            1_800_000_000_000L + 48 * 3_600_000L,
            MemoryExtractor.expireAtFor("随便一件事", 1_800_000_000_000L)
        )
    }
}
