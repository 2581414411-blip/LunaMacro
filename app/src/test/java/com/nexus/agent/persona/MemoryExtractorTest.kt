package com.nexus.agent.persona

import com.nexus.agent.data.db.MemoryEntity
import org.junit.Assert.assertEquals
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
}
