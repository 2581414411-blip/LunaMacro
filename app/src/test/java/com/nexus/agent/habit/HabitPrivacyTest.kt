package com.nexus.agent.habit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 隐私守门员的靶场（0.9.11）。
 *
 * 这个文件里的每一条断言，对应的是「一旦失守就会出大事」的一条线：
 * 密码框、金融应用、验证码、邮箱、长正文。改 [HabitPrivacy] 之前先跑这里，
 * 红了就别合并 —— 功能可以糙，这条线不能退。
 */
class HabitPrivacyTest {

    // ---- 密码框 ----

    @Test
    fun `标记为密码的字段一律算密码框`() {
        assertTrue(HabitPrivacy.isPasswordField(isPassword = true, inputType = 0))
    }

    @Test
    fun `文本密码输入类型算密码框`() {
        // 0x01 TYPE_CLASS_TEXT | 0x80 TYPE_TEXT_VARIATION_PASSWORD
        assertTrue(HabitPrivacy.isPasswordField(false, 0x00000081))
        // 0x90 VISIBLE_PASSWORD（"显示密码"勾上之后就是这个）
        assertTrue(HabitPrivacy.isPasswordField(false, 0x00000091))
        // 0xE0 WEB_PASSWORD
        assertTrue(HabitPrivacy.isPasswordField(false, 0x000000E1))
    }

    @Test
    fun `数字密码输入类型算密码框`() {
        // 0x02 TYPE_CLASS_NUMBER | 0x10 TYPE_NUMBER_VARIATION_PASSWORD
        assertTrue(HabitPrivacy.isPasswordField(false, 0x00000012))
    }

    @Test
    fun `普通输入框不算密码框`() {
        // 普通文本
        assertFalse(HabitPrivacy.isPasswordField(false, 0x00000001))
        // 邮箱
        assertFalse(HabitPrivacy.isPasswordField(false, 0x00000021))
        // 没有任何类型信息
        assertFalse(HabitPrivacy.isPasswordField(false, 0))
    }

    @Test
    fun `自绘控件靠类名兜底`() {
        assertTrue(HabitPrivacy.isPasswordField(false, 0, "com.x.YzPasswordView"))
        assertTrue(HabitPrivacy.isPasswordField(false, 0, "com.x.PinBox"))
    }

    // ---- 应用免采 ----

    @Test
    fun `金融机构整包免采`() {
        assertFalse(HabitPrivacy.isPackageAllowed("com.icbc.mobilebank"))
        assertFalse(HabitPrivacy.isPackageAllowed("com.eg.android.AlipayGphone"))
        assertFalse(HabitPrivacy.isPackageAllowed("com.tencent.mm.plugin.wallet"))
    }

    @Test
    fun `应用显示名命中免采词也拒绝`() {
        assertFalse(HabitPrivacy.isPackageAllowed("com.unknown.app", "招商银行"))
        assertFalse(HabitPrivacy.isPackageAllowed("com.unknown.app", "支付宝"))
    }

    @Test
    fun `系统设置与锁屏不采`() {
        assertFalse(HabitPrivacy.isPackageAllowed("com.android.settings"))
        assertFalse(HabitPrivacy.isPackageAllowed("com.android.systemui"))
    }

    @Test
    fun `普通应用正常放行`() {
        assertTrue(HabitPrivacy.isPackageAllowed("com.tencent.mm", "微信"))
        assertTrue(HabitPrivacy.isPackageAllowed("tv.danmaku.bili", "哔哩哔哩"))
    }

    @Test
    fun `空包名不算可用`() {
        assertFalse(HabitPrivacy.isPackageAllowed(null))
        assertFalse(HabitPrivacy.isPackageAllowed("   "))
    }

    // ---- 敏感界面 ----

    @Test
    fun `登录注册支付界面整屏跳过`() {
        assertTrue(HabitPrivacy.isScreenSensitive("登录"))
        assertTrue(HabitPrivacy.isScreenSensitive("确认支付"))
        assertTrue(HabitPrivacy.isScreenSensitive("修改登录密码"))
        assertTrue(HabitPrivacy.isScreenSensitive("身份验证"))
    }

    @Test
    fun `类名带 Login 也算敏感界面`() {
        assertTrue(HabitPrivacy.isScreenSensitive(null, "com.x.LoginActivity"))
    }

    @Test
    fun `普通界面不算敏感`() {
        assertFalse(HabitPrivacy.isScreenSensitive("朋友圈"))
        assertFalse(HabitPrivacy.isScreenSensitive("推荐"))
        assertFalse(HabitPrivacy.isScreenSensitive(null))
    }

    // ---- 控件标签脱敏 ----

    @Test
    fun `正常按钮保留`() {
        assertEquals("发送", HabitPrivacy.sanitizeLabel("发送"))
        assertEquals("朋友圈", HabitPrivacy.sanitizeLabel("  朋友圈  "))
        assertEquals("点赞", HabitPrivacy.sanitizeLabel("点赞"))
    }

    @Test
    fun `验证码与手机号一律丢弃`() {
        assertNull(HabitPrivacy.sanitizeLabel("123456"))
        assertNull(HabitPrivacy.sanitizeLabel("验证码 8848"))
        assertNull(HabitPrivacy.sanitizeLabel("13800138000"))
    }

    @Test
    fun `邮箱丢弃`() {
        assertNull(HabitPrivacy.sanitizeLabel("someone@example.com"))
    }

    @Test
    fun `长正文丢弃`() {
        val long = "今天下午三点开会在三楼会议室记得带上那份材料"
        assertNull(HabitPrivacy.sanitizeLabel(long))
    }

    @Test
    fun `纯符号或纯空白丢弃`() {
        assertNull(HabitPrivacy.sanitizeLabel("   "))
        assertNull(HabitPrivacy.sanitizeLabel("···"))
        assertNull(HabitPrivacy.sanitizeLabel(null))
    }

    @Test
    fun `涉及钱和密码的按钮丢弃`() {
        assertNull(HabitPrivacy.sanitizeLabel("立即支付"))
        assertNull(HabitPrivacy.sanitizeLabel("确认转账"))
        assertNull(HabitPrivacy.sanitizeLabel("输入密码"))
    }

    // ---- 端到端裁决 ----

    @Test
    fun `密码框事件被整条拦下`() {
        val e = HabitPrivacy.screen(
            at = 1_700_000_000_000,
            pkg = "com.tencent.mm",
            appLabel = "微信",
            kind = HabitEvent.KIND_TAP,
            screenTitle = null,
            className = "android.widget.EditText",
            label = "请输入密码",
            isPassword = true,
            inputType = 0x00000081
        )
        assertNull("密码框必须一条都不留", e)
    }

    @Test
    fun `金融应用事件被整条拦下`() {
        val e = HabitPrivacy.screen(
            at = 1_700_000_000_000,
            pkg = "com.icbc.mobilebank",
            appLabel = "工商银行",
            kind = HabitEvent.KIND_OPEN,
            screenTitle = "我的账户",
            className = null,
            label = null,
            isPassword = false,
            inputType = 0
        )
        assertNull(e)
    }

    @Test
    fun `正常点击通过并保留界面与标签`() {
        val e = HabitPrivacy.screen(
            at = 1_700_000_000_000,
            pkg = "tv.danmaku.bili",
            appLabel = "哔哩哔哩",
            kind = HabitEvent.KIND_TAP,
            screenTitle = "推荐",
            className = "android.widget.TextView",
            label = "点赞",
            isPassword = false,
            inputType = 0
        )
        assertNotNull(e)
        assertEquals("哔哩哔哩", e!!.app)
        assertEquals("推荐", e.screen)
        assertEquals("点赞", e.label)
        assertEquals(HabitEvent.KIND_TAP, e.kind)
    }

    @Test
    fun `没有显示名时退化成包名短名`() {
        val e = HabitPrivacy.screen(
            at = 1_700_000_000_000,
            pkg = "com.tencent.mm",
            appLabel = null,
            kind = HabitEvent.KIND_OPEN,
            screenTitle = null,
            className = null,
            label = null,
            isPassword = false,
            inputType = 0
        )
        assertNotNull(e)
        assertEquals("tencent.mm", e!!.app)
    }

    // ---- 序列化 ----

    @Test
    fun `事件能原样往返`() {
        val src = HabitEvent(
            at = 1_700_000_000_123,
            pkg = "com.tencent.mm",
            app = "微信",
            kind = HabitEvent.KIND_TAP,
            screen = "朋友圈",
            label = "点赞"
        )
        val back = HabitEvent.fromJson(src.toJson())
        assertEquals(src, back)
    }

    @Test
    fun `坏行不会炸`() {
        assertNull(HabitEvent.fromJson(""))
        assertNull(HabitEvent.fromJson("not json"))
        assertNull(HabitEvent.fromJson("{\"pkg\":\"x\"}"))
        assertNull(HabitEvent.fromJson(null))
    }
}
