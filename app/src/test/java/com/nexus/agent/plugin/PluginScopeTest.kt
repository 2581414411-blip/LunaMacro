package com.nexus.agent.plugin

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 插件工具白名单的纯逻辑测试（0.8.12）。
 *
 * 重点验证：
 *  1. 没写 `allowedTools`（null）时**不限制** —— 老插件必须原样能跑
 *  2. 写了就在执行前拦下越权调用，报错文案要能让用户看懂
 *  3. `finish` 永远放行（它是收尾信号，拦掉会让任务卡死在最后一步）
 *  4. `end()` 之后立刻恢复不限
 */
class PluginScopeTest {

    private fun manifest(allowed: List<String>?): PluginManifest =
        PluginManifest(id = "demo", name = "示例", allowedTools = allowed)

    @Before
    fun setUp() = PluginScope.resetForTest()

    @After
    fun tearDown() = PluginScope.resetForTest()

    @Test
    fun `没有作用域时不限制任何工具`() {
        assertNull(PluginScope.blockReason("open_settings"))
        assertNull(PluginScope.blockReason("any_tool"))
    }

    @Test
    fun `未声明白名单的插件不受限制`() {
        PluginScope.begin(manifest(null))
        assertNull(PluginScope.blockReason("open_settings"))
        assertNull(PluginScope.blockReason("shutdown"))
    }

    @Test
    fun `白名单内的工具放行`() {
        PluginScope.begin(manifest(listOf("tap", "swipe")))
        assertNull(PluginScope.blockReason("tap"))
        assertNull(PluginScope.blockReason("swipe"))
    }

    @Test
    fun `白名单外的工具被拒绝且文案可读`() {
        PluginScope.begin(manifest(listOf("tap")))
        val reason = PluginScope.blockReason("open_settings")

        assertNotNull(reason)
        assertTrue(reason!!.contains("该插件无权使用此工具"))
        assertTrue(reason.contains("open_settings"))
    }

    @Test
    fun `finish 永远放行`() {
        PluginScope.begin(manifest(emptyList()))
        assertNull(PluginScope.blockReason("finish"))
        assertNotNull(PluginScope.blockReason("tap"))
    }

    @Test
    fun `空白名单等于禁止一切动作`() {
        PluginScope.begin(manifest(emptyList()))
        assertNotNull(PluginScope.blockReason("tap"))
        assertNotNull(PluginScope.blockReason("launch_app"))
    }

    @Test
    fun `end 之后恢复不限`() {
        PluginScope.begin(manifest(listOf("tap")))
        assertNotNull(PluginScope.blockReason("open_settings"))
        PluginScope.end()
        assertNull(PluginScope.blockReason("open_settings"))
        assertTrue(!PluginScope.isActive())
    }

    @Test
    fun `作用域生效时 isActive 为真`() {
        PluginScope.begin(manifest(listOf("tap")))
        assertTrue(PluginScope.isActive())
    }

    @Test
    fun `滑动条范围写反时回落到默认区间`() {
        val bad = PluginField("x", "程度", PluginFieldType.SLIDER, minValue = 100, maxValue = 0)
        assertEquals(PluginField.DEFAULT_SLIDER_MIN..PluginField.DEFAULT_SLIDER_MAX, bad.sliderRange)

        val ok = PluginField("x", "程度", PluginFieldType.SLIDER, minValue = 1, maxValue = 5)
        assertEquals(1..5, ok.sliderRange)
    }

    @Test
    fun `类型解析认 slider 别名`() {
        assertEquals(PluginFieldType.SLIDER, PluginFieldType.of("slider"))
        assertEquals(PluginFieldType.SLIDER, PluginFieldType.of("range"))
        assertEquals(PluginFieldType.SLIDER, PluginFieldType.of("percent"))
        assertEquals(PluginFieldType.NUMBER, PluginFieldType.of("number"))
    }
}
