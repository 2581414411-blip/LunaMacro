package com.nexus.agent.persona

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Calendar
import java.util.Date
import java.text.SimpleDateFormat

/**
 * 日记编解码与时间闸单测（0.9.15 阶段 0）。
 *
 * 这一份坏掉的表现是「她一早起来在念乱码」或「昨天的日记今天还在念」，
 * 而且都发生在冷启动第一句话 —— 用户第一眼就会看到。所以编解码和两道时间闸
 * 必须全部有测试顶着。
 */
class DiaryCodecTest {

    private val hour = 60L * 60 * 1000

    private fun sample(text: String = "今天他说项目要上线，我没敢多问。") = Diary(
        day = "2026-11-11",
        at = 1_700_000_000_000L,
        quick = PAD(0.2f, -0.1f, 0.3f),
        slow = PAD(-0.15f, 0.05f, 0.1f),
        text = text
    )

    private fun tempDir(): File =
        File(Files.createTempDirectory("luna-diary-test").toFile(), "diary")

    // ------------------------------------------------------------ 编解码

    @Test
    fun `编解码往返一致`() {
        val d = sample()
        val back = Diary.decode(Diary.encode(d))
        assertNotNull(back)
        back!!
        assertEquals(d.day, back.day)
        assertEquals(d.at, back.at)
        assertEquals(d.text, back.text)
        assertEquals(d.quick.p, back.quick.p, 0.001f)
        assertEquals(d.quick.a, back.quick.a, 0.001f)
        assertEquals(d.quick.d, back.quick.d, 0.001f)
        assertEquals(d.slow.p, back.slow.p, 0.001f)
    }

    @Test
    fun `引号换行反斜杠都能原样还原`() {
        val d = sample(text = "他说\"早点睡\"\n然后就没了\\")
        val back = Diary.decode(Diary.encode(d))
        assertEquals(d.text, back?.text)
    }

    @Test
    fun `编码结果是合法形态的 JSON`() {
        val raw = Diary.encode(sample())
        assertTrue(raw.startsWith("{"))
        assertTrue(raw.endsWith("}"))
        assertTrue(raw.contains("\"version\":1"))
        assertTrue(raw.contains("\"quick\":["))
    }

    @Test
    fun `坏数据一律返回 null`() {
        assertNull(Diary.decode(null))
        assertNull(Diary.decode(""))
        assertNull(Diary.decode("   "))
        assertNull(Diary.decode("not json at all"))
        assertNull(Diary.decode("{}"))
        assertNull(Diary.decode("{\"version\":1,\"day\":\"2026-11-11\"}"))
    }

    @Test
    fun `版本不认识就当没有`() {
        val raw = Diary.encode(sample()).replace("\"version\":1", "\"version\":7")
        assertNull(Diary.decode(raw))
    }

    @Test
    fun `三元组长度不对就当没有`() {
        val raw = Diary.encode(sample()).replace("\"quick\":[0.200,-0.100,0.300]", "\"quick\":[0.2,0.1]")
        assertNull(Diary.decode(raw))
    }

    @Test
    fun `正文为空就当没有`() {
        val raw = Diary.encode(sample(text = "x")).replace("\"text\":\"x\"", "\"text\":\"\"")
        assertNull(Diary.decode(raw))
    }

    // ------------------------------------------------------------ 文件

    @Test
    fun `写进去能读回来`() {
        val dir = tempDir()
        val d = sample()
        assertTrue(Diary.write(dir, d))
        val back = Diary.read(dir, d.day)
        assertEquals(d.text, back?.text)
    }

    @Test
    fun `没写过的日子读出来是 null`() {
        assertNull(Diary.read(tempDir(), "2026-01-01"))
    }

    @Test
    fun `目录不存在也不会崩`() {
        val dir = File(tempDir(), "a/b/c")
        assertNull(Diary.latest(dir, System.currentTimeMillis()))
        assertEquals(0, Diary.clear(dir))
    }

    // ------------------------------------------------------------ 时间闸

    @Test
    fun `刚写过就不该再写`() {
        val dir = tempDir()
        val now = System.currentTimeMillis()
        Diary.write(dir, sample().copy(at = now))
        assertFalse(Diary.shouldWrite(dir, now))
    }

    @Test
    fun `隔了二十小时可以再写`() {
        val dir = tempDir()
        val now = System.currentTimeMillis()
        Diary.write(dir, sample().copy(at = now - 21 * hour))
        assertTrue(Diary.shouldWrite(dir, now))
    }

    @Test
    fun `两天前的日记不再注入`() {
        val dir = tempDir()
        val now = System.currentTimeMillis()
        Diary.write(dir, sample().copy(at = now - 48 * hour))
        assertNull(Diary.latest(dir, now))
    }

    @Test
    fun `一天内的日记会被读出来`() {
        val dir = tempDir()
        val now = System.currentTimeMillis()
        Diary.write(dir, sample().copy(at = now - 3 * hour))
        assertNotNull(Diary.latest(dir, now))
    }

    @Test
    fun `有多份时取最新的那份`() {
        val dir = tempDir()
        val now = System.currentTimeMillis()
        Diary.write(dir, sample(text = "前天的事").copy(day = "2026-11-09", at = now - 48 * hour))
        Diary.write(dir, sample(text = "昨天的事").copy(day = "2026-11-10", at = now - 10 * hour))
        assertEquals("昨天的事", Diary.latest(dir, now)?.text)
    }

    @Test
    fun `清空会把目录里的日记删掉`() {
        val dir = tempDir()
        Diary.write(dir, sample())
        assertEquals(1, Diary.clear(dir))
        assertNull(Diary.latest(dir, System.currentTimeMillis()))
    }

    // ------------------------------------------------------------ 兜底模板

    @Test
    fun `兜底模板永远有内容`() {
        val t = Diary.template(PAD.ZERO, PAD.ZERO, emptyList())
        assertTrue(t.isNotBlank())
        assertTrue(t.contains("今天"))
    }

    @Test
    fun `兜底模板会带上没说完的事`() {
        val t = Diary.template(PAD(-0.5f, -0.1f, -0.1f), PAD.ZERO, listOf("明天体检"))
        assertTrue(t.contains("明天体检"))
        assertTrue("心情要用口语，不能是八分区那种分类词", t.contains("发沉"))
    }

    @Test
    fun `兜底模板不会说出敌意焦躁这种词`() {
        // 八分区的词是给提示词用的分类标签，塞进第一人称日记会变成病历口吻：
        // 「今天心里有点敌意。」—— 半夜读到自己这句，比没有日记更吓人
        val samples = listOf(
            PAD(-0.12f, -0.05f, 0f) to "敌意",
            PAD(-0.5f, 0.4f, -0.3f) to "焦躁",
            PAD(-0.5f, -0.3f, -0.3f) to "消沉",
            PAD(0.5f, 0.3f, 0.3f) to "雀跃"
        )
        samples.forEach { (pad, banned) ->
            val t = Diary.template(pad, PAD.ZERO, emptyList())
            assertTrue("「$banned」不该出现在日记里：$t", !t.contains(banned))
        }
    }

    @Test
    fun `兜底模板的心情短语覆盖主要象限`() {
        assertEquals("挺平静的", Diary.moodPhraseOf(PAD.ZERO))
        assertEquals("挺轻快的", Diary.moodPhraseOf(PAD(0.4f, 0.2f, 0f)))
        assertEquals("挺安稳的", Diary.moodPhraseOf(PAD(0.4f, 0f, 0f)))
        assertEquals("有点烦", Diary.moodPhraseOf(PAD(-0.4f, 0.2f, 0f)))
        assertEquals("有点发沉", Diary.moodPhraseOf(PAD(-0.4f, -0.1f, 0f)))
        assertEquals("有点提不起劲", Diary.moodPhraseOf(PAD(0f, -0.4f, 0f)))
    }

    @Test
    fun `兜底模板不会超字数`() {
        val long = "一件非常非常长的事情".repeat(10)
        assertTrue(Diary.template(PAD.ZERO, PAD.ZERO, listOf(long)).length < 80)
    }

    @Test
    fun `日期键用本地时区`() {
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 11, 9, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val expected = SimpleDateFormat("yyyy-MM-dd").format(Date(cal.timeInMillis))
        assertEquals("2026-11-11", expected)
        assertEquals(expected, Diary.dayKey(cal.timeInMillis))
    }
}
