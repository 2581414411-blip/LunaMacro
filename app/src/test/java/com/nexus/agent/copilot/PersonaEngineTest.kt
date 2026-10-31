package com.nexus.agent.copilot

import com.nexus.agent.data.db.ChatRecord
import com.nexus.agent.data.db.PersonaStore
import com.nexus.agent.data.db.ProfileSnapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 人格记忆的纯逻辑测试（不依赖 Android）。
 *
 * 重点验证：
 *  1. 记忆窗口只留 [PersonaEngine.MAX_TURNS] 轮，多了丢最旧的
 *  2. 空回复不会被记成一轮（否则历史里会出现「你：」后面啥都没有）
 *  3. 0.7.12 起聊天记录/档案走 [PersonaStore]，写穿与恢复都要对
 *
 * 用假存档代替 Room：[PersonaStore] 是接口，Room 实现在这里根本不加载，
 * 所以这组测试仍然能在纯 JVM 上跑（不需要 Robolectric / 真机）。
 */
class PersonaEngineTest {

    /** 假存档：只记调用，不落盘 */
    private class FakeStore : PersonaStore {
        val appended = mutableListOf<ChatRecord>()
        var savedProfile: ProfileSnapshot? = null
        var chatCleared = false
        var allCleared = false

        /** 预置的读盘结果：(档案, 聊天流, 对话流水) */
        var stub: Triple<ProfileSnapshot, List<ChatRecord>, List<ChatRecord>>? = null

        override fun load(
            recentItems: Int,
            recentTurns: Int,
            onLoaded: (ProfileSnapshot, List<ChatRecord>, List<ChatRecord>) -> Unit
        ) {
            stub?.let { onLoaded(it.first, it.second, it.third) }
        }

        override fun appendChat(record: ChatRecord) {
            appended.add(record)
        }

        override fun saveProfile(profile: ProfileSnapshot) {
            savedProfile = profile
        }

        override fun clearChat() {
            chatCleared = true
        }

        override fun clearAll() {
            allCleared = true
        }
    }

    @Before
    fun setUp() = PersonaEngine.resetForTest()

    @After
    fun tearDown() = PersonaEngine.resetForTest()

    @Test
    fun `初始状态没有记忆`() {
        assertEquals(0, PersonaEngine.turnCount())
        assertTrue(PersonaEngine.historyMessages().isEmpty())
        assertEquals("", PersonaEngine.historyText())
    }

    @Test
    fun `记一轮后能取回`() {
        PersonaEngine.recordTurn("在吗", "在")
        assertEquals(1, PersonaEngine.turnCount())
        val msgs = PersonaEngine.historyMessages()
        assertEquals(2, msgs.size)
        assertEquals("user", msgs[0].role)
        assertEquals("在吗", msgs[0].content)
        assertEquals("assistant", msgs[1].role)
        assertEquals("在", msgs[1].content)
    }

    @Test
    fun `超过十轮丢最旧的`() {
        // 0.7.12：窗口从 6 轮放宽到 10 轮（启动时也从库里读 10 轮）
        for (i in 1..15) {
            PersonaEngine.recordTurn("问题$i", "回答$i")
        }
        assertEquals(PersonaEngine.MAX_TURNS, PersonaEngine.turnCount())

        val msgs = PersonaEngine.historyMessages()
        // 应该只剩 6~15 轮，最早的是「问题6」
        assertEquals(PersonaEngine.MAX_TURNS * 2, msgs.size)
        assertEquals("问题6", msgs.first().content)
        assertEquals("回答15", msgs.last().content)
    }

    @Test
    fun `空回复不计入记忆`() {
        PersonaEngine.recordTurn("在吗", "")
        PersonaEngine.recordTurn("在吗", "   ")
        assertEquals(0, PersonaEngine.turnCount())
    }

    @Test
    fun `空提问不计入记忆`() {
        PersonaEngine.recordTurn("", "在")
        PersonaEngine.recordTurn("   ", "在")
        assertEquals(0, PersonaEngine.turnCount())
    }

    @Test
    fun `最近一条回复能取到`() {
        PersonaEngine.recordTurn("一", "第一次")
        PersonaEngine.recordTurn("二", "第二次")
        assertEquals("第二次", PersonaEngine.lastAssistantReply())
    }

    @Test
    fun `历史文本带角色前缀且长内容被截断`() {
        val long = "啊".repeat(200)
        PersonaEngine.recordTurn(long, long)
        val text = PersonaEngine.historyText()
        assertTrue(text.startsWith("用户："))
        assertTrue(text.contains("\n你："))
        // 每条截断到 60 字：整段应该远小于 400 字
        assertTrue("历史文本过长：${text.length}", text.length < 200)
    }

    @Test
    fun `清空记忆后回到初始状态`() {
        PersonaEngine.recordTurn("在吗", "在")
        PersonaEngine.clearMemory()
        assertEquals(0, PersonaEngine.turnCount())
        assertEquals("", PersonaEngine.historyText())
    }

    // ------------------------------------------------------ 0.7.12 存档相关

    @Test
    fun `聊天记录写穿到存档`() {
        val fake = FakeStore()
        PersonaEngine.useStoreForTest(fake)

        PersonaEngine.addUserItem("帮我打开设置")
        PersonaEngine.addAssistantItem("开了")
        PersonaEngine.addActionItem("已打开", "设置", true)

        assertEquals(3, fake.appended.size)
        assertEquals("user", fake.appended[0].role)
        assertEquals("帮我打开设置", fake.appended[0].content)
        assertEquals("assistant", fake.appended[1].role)
        assertEquals("action", fake.appended[2].role)
        assertEquals("已打开", fake.appended[2].content)
    }

    @Test
    fun `恢复存档后档案与最近十轮生效`() {
        val dialogue = (1..12).flatMap { i ->
            listOf(
                ChatRecord("user", "问$i", createdAt = i * 2L),
                ChatRecord("assistant", "答$i", createdAt = i * 2L + 1)
            )
        }
        PersonaEngine.restore(
            ProfileSnapshot("小月", "嘴硬心软", "青梅竹马", 72, "有点困"),
            dialogue,
            dialogue
        )

        assertEquals(72, PersonaEngine.profileSnapshot().affinity)
        assertEquals("有点困", PersonaEngine.profileSnapshot().mood)
        assertEquals("小月", PersonaEngine.profileSnapshot().name)

        // 12 轮里只留窗口大小的 10 轮，最早是「问3」
        assertEquals(PersonaEngine.MAX_TURNS, PersonaEngine.turnCount())
        assertEquals("问3", PersonaEngine.historyMessages().first().content)
        assertEquals("答12", PersonaEngine.historyMessages().last().content)

        // 聊天流也该恢复出来
        assertEquals(dialogue.size, PersonaEngine.items().size)
    }

    @Test
    fun `恢复存档不冲掉内存里刚说的话`() {
        PersonaEngine.addUserItem("刚说的一句")
        val old = listOf(ChatRecord("user", "旧话", createdAt = 1L))
        PersonaEngine.restore(ProfileSnapshot("宏月", "", "", 50, "平静"), old, old)

        val items = PersonaEngine.items()
        assertEquals(2, items.size)
        assertEquals("旧话", (items[0] as PersonaEngine.ChatItem.User).text)
        assertEquals("刚说的一句", (items[1] as PersonaEngine.ChatItem.User).text)
    }

    @Test
    fun `好感度越界会被夹住并落库`() {
        val fake = FakeStore()
        PersonaEngine.useStoreForTest(fake)

        PersonaEngine.bumpAffinity(80) // 50 + 80 = 130 → 100
        assertEquals(100, PersonaEngine.profileSnapshot().affinity)

        PersonaEngine.bumpAffinity(-500) // 100 - 500 → 0
        assertEquals(0, PersonaEngine.profileSnapshot().affinity)

        assertNotNull(fake.savedProfile)
        assertEquals(0, fake.savedProfile!!.affinity)
    }
}
