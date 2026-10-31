package com.nexus.agent.ui

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexus.agent.copilot.CopilotConfig
import com.nexus.agent.copilot.CopilotEngine
import com.nexus.agent.copilot.PersonaEngine
import com.nexus.agent.ui.components.LunaButton
import com.nexus.agent.ui.components.LunaCard
import com.nexus.agent.ui.components.LunaTextButton
import com.nexus.agent.ui.theme.LunaErrFg
import com.nexus.agent.ui.theme.LunaOkFg
import com.nexus.agent.ui.theme.LunaOnBackground
import com.nexus.agent.ui.theme.LunaOnSurfaceVariant
import com.nexus.agent.ui.theme.LunaOutline
import com.nexus.agent.ui.theme.LunaPrimary
import com.nexus.agent.ui.theme.LunaSurfaceAlt

/**
 * 人格 Tab —— 标准聊天对话流（0.7.11）。
 *
 * 与旧版副驾驶面板（0.7.10 及以前）的区别：
 *  - 旧版是「开关面板 + 最近一条弹幕」，只在看配置，不能连续聊
 *  - 这里是消息流：用户气泡在右、助手气泡在左、工具执行单独一条「系统动作」气泡
 *  - 对话记忆由 [PersonaEngine] 维护（最近 6 轮），切换页面 / 重进 App 都还在
 *
 * 原来的两个开关（灵动岛 / 情绪陪玩）已挪到设置页的「助手身份」区块。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun PersonaTab() {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    var items by remember { mutableStateOf(PersonaEngine.items()) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var showDisclaimer by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var assistantName by remember { mutableStateOf(CopilotConfig.assistantName(context)) }

    DisposableEffect(Unit) {
        PersonaEngine.init(context)
        PersonaEngine.onChanged = { items = PersonaEngine.items() }
        items = PersonaEngine.items()
        assistantName = CopilotConfig.assistantName(context)
        if (!CopilotConfig.isDisclaimerAccepted(context)) showDisclaimer = true
        // 灵动岛的输入框接到副驾驶大脑（幂等，多调无害）
        CopilotEngine.bindIsland(context)
        onDispose { PersonaEngine.onChanged = null }
    }

    // 新消息 / 正在思考 / 键盘弹起时滚到底（键盘把可视区压短，不滚会看不到刚发的那句）
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(items.size, busy, imeVisible) {
        if (items.isNotEmpty()) {
            listState.animateScrollToItem(items.lastIndex)
        }
    }

    // 人格设置（0.8.0）：二级页面，不走导航库，用局部状态切换即可
    if (showSettings) {
        PersonaSettingsScreen(
            onBack = { showSettings = false },
            onSaved = {
                showSettings = false
                // 名字可能被改过，回来后顶栏要立刻反映
                assistantName = CopilotConfig.assistantName(context)
            }
        )
        return
    }

    if (showDisclaimer) {
        CopilotDisclaimerDialog(onAccept = {
            CopilotConfig.setDisclaimerAccepted(context, true)
            showDisclaimer = false
        })
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || busy) return
        input = ""
        PersonaEngine.addUserItem(text)
        busy = true
        try {
            CopilotEngine.ask(
                context = context,
                userText = text,
                // 声音/灵动岛由引擎内部处理；这里不做 onReply，
                // 否则会和 PersonaEngine 的记录重复
                onDone = { mainHandler.post { busy = false } },
                chatty = true
            )
        } catch (e: Throwable) {
            busy = false
            Toast.makeText(context, "发送失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        // ---- 顶部：名字 + 清空 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    assistantName,
                    style = MaterialTheme.typography.titleMedium,
                    color = LunaOnBackground
                )
                val memoryCount = PersonaEngine.memoriesSnapshot().size
                Text(
                    when {
                        items.isEmpty() -> "说点什么，它能看屏幕、也能替你动手"
                        memoryCount > 0 -> "已记住最近 ${PersonaEngine.turnCount()} 轮 · 长期记忆 $memoryCount 条"
                        else -> "已记住最近 ${PersonaEngine.turnCount()} 轮"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = LunaOnSurfaceVariant
                )
            }
            if (items.isNotEmpty()) {
                LunaTextButton(onClick = {
                    PersonaEngine.clearAll(context)
                    Toast.makeText(context, "聊天记录与记忆已清空", Toast.LENGTH_SHORT).show()
                }) {
                    Text("清空", fontSize = 13.sp, color = LunaOnSurfaceVariant)
                }
            }
            // 人格调教入口（0.8.0）：右上角齿轮
            IconButton(onClick = { showSettings = true }) {
                Text("⚙", fontSize = 18.sp, color = LunaOnSurfaceVariant)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(LunaOutline.copy(alpha = 0.4f))
        )

        // ---- 消息流 ----
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (items.isEmpty()) {
                item { EmptyHint() }
            }
            items(items.size) { idx ->
                when (val it = items[idx]) {
                    is PersonaEngine.ChatItem.User -> UserBubble(it.text)
                    is PersonaEngine.ChatItem.Assistant -> AssistantBubble(it.text)
                    is PersonaEngine.ChatItem.Action -> ActionBubble(it)
                }
            }
            if (busy) {
                item { ThinkingRow() }
            }
        }

        // ---- 输入区（键盘弹起时导航栏已收起，这里正好贴键盘上沿）----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("说点什么…", fontSize = 13.sp) },
                maxLines = 4,
                enabled = !busy
            )
            Spacer(modifier = Modifier.width(8.dp))
            LunaButton(
                onClick = { send() },
                enabled = input.isNotBlank() && !busy
            ) {
                Text("发送", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun EmptyHint() {
    LunaCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            "这里是对话",
            style = MaterialTheme.typography.titleMedium,
            color = LunaOnBackground
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "问它「我现在在什么软件里」，或者直接说「帮我打开设置」——" +
                "它能看屏幕，也能真的替你点。\n" +
                "要它干活时，动作会单独显示成一条系统气泡，不会和聊天混在一起。",
            style = MaterialTheme.typography.bodySmall,
            color = LunaOnSurfaceVariant,
            lineHeight = 19.sp
        )
    }
}

/** 用户气泡：靠右，主色底 */
@Composable
private fun UserBubble(text: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp))
                .background(LunaPrimary)
                .padding(horizontal = 13.dp, vertical = 9.dp)
        ) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = Color.White)
        }
    }
}

/** 助手气泡：靠左，浅底 */
@Composable
private fun AssistantBubble(text: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Box(
            modifier = Modifier
                .widthIn(max = 290.dp)
                .clip(RoundedCornerShape(14.dp, 14.dp, 14.dp, 4.dp))
                .background(LunaSurfaceAlt)
                .padding(horizontal = 13.dp, vertical = 9.dp)
        ) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = LunaOnBackground)
        }
    }
}

/**
 * 系统动作气泡：居中窄条，等宽小字。
 *
 * 刻意和聊天气泡区分开——用户要能一眼看出「这句是人设说的」还是「它真的动了手」。
 */
@Composable
private fun ActionBubble(item: PersonaEngine.ChatItem.Action) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(LunaOutline.copy(alpha = 0.16f))
                .padding(horizontal = 12.dp, vertical = 7.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (item.ok) "⚙" else "⚠",
                    fontSize = 12.sp,
                    color = if (item.ok) LunaOnSurfaceVariant else LunaErrFg
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    item.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (item.ok) LunaOnSurfaceVariant else LunaErrFg
                )
            }
            if (item.detail.isNotBlank()) {
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    item.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.ok) LunaOnSurfaceVariant else LunaErrFg,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

@Composable
private fun ThinkingRow() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
            color = LunaPrimary
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text("正在想…", style = MaterialTheme.typography.bodySmall, color = LunaOnSurfaceVariant)
    }
}
