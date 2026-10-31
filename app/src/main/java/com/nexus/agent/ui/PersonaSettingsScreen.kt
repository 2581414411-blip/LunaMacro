package com.nexus.agent.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexus.agent.copilot.CopilotConfig
import com.nexus.agent.copilot.PersonaEngine
import com.nexus.agent.data.db.PersonaProfile
import com.nexus.agent.ui.components.LunaButton
import com.nexus.agent.ui.components.LunaClickableCard
import com.nexus.agent.ui.components.LunaTextButton
import com.nexus.agent.ui.theme.LunaErrFg
import com.nexus.agent.ui.theme.LunaOnBackground
import com.nexus.agent.ui.theme.LunaOnSurfaceVariant
import com.nexus.agent.ui.theme.LunaOutline
import com.nexus.agent.ui.theme.LunaSurface
import com.nexus.agent.ui.theme.LunaSurfaceAlt

/**
 * 人格调教界面（0.8.0）。
 *
 * 设计目标：**零门槛**。原来的「人设」是一段写死的提示词常量，用户想让它
 * 傲娇一点、话多一点，除了让开发者改代码没有别的办法。这里把「它是谁」
 * 拆成能直接点的东西 —— 选个模板、填三个框、拖三根条，保存就生效。
 *
 * 四个区块对应需求：预设模板 / 基本信息 / 性格滑动条 / 风险提示。
 *
 * 与 [SettingsTab] 里「助手身份」的分工：
 *  - 那里只管**名字**（灵动岛、弹幕、非人格模式提示词都读它）
 *  - 这里管**完整的它是谁**，保存时会把名字同步写回那一边，避免精分
 */
@Composable
fun PersonaSettingsScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val initial = remember { PersonaEngine.profileSnapshot() }

    var name by remember { mutableStateOf(initial.name) }
    var identity by remember { mutableStateOf(initial.identity) }
    var catchphrase by remember { mutableStateOf(initial.catchphrase) }
    // 性格底色不是用户直接输入的，由模板决定；这里留一份等保存时原样写回
    var soul by remember { mutableStateOf(initial.soul) }

    var extroversion by remember { mutableStateOf(initial.extroversion.toFloat()) }
    var sensibility by remember { mutableStateOf(initial.sensibility.toFloat()) }
    var rebelliousness by remember { mutableStateOf(initial.rebelliousness.toFloat()) }

    var pickedTemplate by remember { mutableStateOf<String?>(null) }
    var nameError by remember { mutableStateOf("") }

    // 系统返回键也要能退出这个二级页面（否则会直接退出 App）
    BackHandler(enabled = true) { onBack() }

    fun applyTemplate(t: PersonaTemplate) {
        pickedTemplate = t.key
        identity = t.identity
        soul = t.soul
        catchphrase = t.catchphrase
        extroversion = t.extroversion.toFloat()
        sensibility = t.sensibility.toFloat()
        rebelliousness = t.rebelliousness.toFloat()
    }

    fun save() {
        val clean = name.trim().ifBlank { CopilotConfig.DEFAULT_ASSISTANT_NAME }
        // 名字长度校验交给 CopilotConfig：两边规则只留一份，避免不同步
        if (!CopilotConfig.setAssistantName(context, clean)) {
            nameError = "名字最长 12 个字"
            return
        }
        nameError = ""
        PersonaEngine.saveProfile(
            context,
            initial.copy(
                name = clean,
                identity = identity.trim(),
                soul = soul,
                catchphrase = catchphrase.trim(),
                extroversion = extroversion.toInt(),
                sensibility = sensibility.toInt(),
                rebelliousness = rebelliousness.toInt()
            )
        )
        Toast.makeText(context, "人格已保存，下一句话就生效", Toast.LENGTH_SHORT).show()
        onSaved()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ---- 顶栏：返回 / 标题 / 保存 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 12.dp, top = 10.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LunaTextButton(onClick = onBack) {
                Text("‹ 返回", fontSize = 14.sp, color = LunaOnSurfaceVariant)
            }
            Text(
                "人格设置",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = LunaOnBackground
            )
            LunaButton(onClick = { save() }) {
                Text("保存", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(LunaOutline.copy(alpha = 0.4f))
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            SectionTitle("预设模板", "先挑一个顺眼的，下面会自动填好，再按自己口味微调")
            Spacer(modifier = Modifier.height(10.dp))
            TemplateRow(
                PersonaTemplates.take(2),
                pickedTemplate,
                ::applyTemplate
            )
            Spacer(modifier = Modifier.height(10.dp))
            TemplateRow(
                PersonaTemplates.drop(2),
                pickedTemplate,
                ::applyTemplate
            )

            Spacer(modifier = Modifier.height(22.dp))
            SectionTitle("基本信息", "名字会显示在聊天页顶部；身份设定和口癖会进提示词")
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { raw ->
                    name = raw.replace("\n", "").replace("\r", "")
                    nameError = ""
                },
                label = { Text("助手名称", fontSize = 12.sp) },
                placeholder = { Text(CopilotConfig.DEFAULT_ASSISTANT_NAME, fontSize = 13.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (nameError.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(nameError, fontSize = 12.sp, color = LunaErrFg)
            }
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = identity,
                onValueChange = { if (it.length <= IDENTITY_MAX) identity = it },
                label = { Text("身份设定", fontSize = 12.sp) },
                placeholder = { Text("例：赛博黑客 / 青梅竹马 / 私人管家", fontSize = 13.sp) },
                minLines = 2,
                maxLines = 3,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = catchphrase,
                onValueChange = {
                    // 口癖最长 6 个字：太长了每句话都塞会很吵，也挤 prompt
                    catchphrase = it.replace("\n", "").replace("\r", "")
                        .take(PersonaProfile.CATCHPHRASE_MAX)
                },
                label = { Text("专属口癖", fontSize = 12.sp) },
                placeholder = { Text("例：喵 / 哼 / ……（可留空）", fontSize = 13.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (soul.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "性格底色：$soul（选模板时自动带入）",
                    fontSize = 12.sp,
                    color = LunaOnSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(22.dp))
            SectionTitle("性格", "拖动滑块，数值会变成提示词里的一句自然语言")
            Spacer(modifier = Modifier.height(6.dp))
            TraitSlider(
                title = "内向 · 外向",
                value = extroversion,
                onValueChange = { extroversion = it },
                leftLabel = "内向",
                rightLabel = "外向",
                describe = PersonaEngine.describeExtroversion(extroversion.toInt())
            )
            TraitSlider(
                title = "理性 · 感性",
                value = sensibility,
                onValueChange = { sensibility = it },
                leftLabel = "理性",
                rightLabel = "感性",
                describe = PersonaEngine.describeSensibility(sensibility.toInt())
            )
            TraitSlider(
                title = "顺从 · 叛逆",
                value = rebelliousness,
                onValueChange = { rebelliousness = it },
                leftLabel = "顺从",
                rightLabel = "叛逆",
                describe = PersonaEngine.describeRebelliousness(rebelliousness.toInt())
            )

            Spacer(modifier = Modifier.height(18.dp))
            Text(
                "⚠️ 过度修改身份设定或性格，可能导致 AI 出现幻觉或逻辑混乱，建议微调。",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = LunaOnSurfaceVariant
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                "当前性格会被描述为：${PersonaEngine.traitsSentence(extroversion.toInt(), sensibility.toInt(), rebelliousness.toInt())}",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = LunaOnSurfaceVariant
            )
            // 底部留白：防止最后一行被输入法/导航栏压住
            Spacer(modifier = Modifier.height(60.dp))
        }
    }
}

/** 身份设定的字数上限：再长就会把提示词挤满，反而让模型抓不住重点 */
private const val IDENTITY_MAX = 60

@Composable
private fun SectionTitle(title: String, hint: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = LunaOnBackground)
    Spacer(modifier = Modifier.height(4.dp))
    Text(hint, style = MaterialTheme.typography.bodySmall, color = LunaOnSurfaceVariant)
}

/** 一行两张模板卡（2×2 网格，不用 LazyVerticalGrid 省得嵌在滚动列里出问题） */
@Composable
private fun TemplateRow(
    items: List<PersonaTemplate>,
    picked: String?,
    onPick: (PersonaTemplate) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items.forEach { t ->
            val selected = picked == t.key
            LunaClickableCard(
                onClick = { onPick(t) },
                modifier = Modifier.weight(1f),
                container = if (selected) LunaSurfaceAlt else LunaSurface,
                contentPadding = 12.dp
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t.emoji, fontSize = 18.sp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        t.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = LunaOnBackground
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    t.desc,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = LunaOnSurfaceVariant
                )
                if (selected) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("✓ 已选", fontSize = 11.sp, color = LunaOnBackground)
                }
            }
        }
        // 补齐空位，避免最后一行只有一张卡时被拉伸成整行
        if (items.size == 1) Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun TraitSlider(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    leftLabel: String,
    rightLabel: String,
    describe: String
) {
    Spacer(modifier = Modifier.height(14.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = LunaOnBackground)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            "$describe · ${value.toInt()}",
            fontSize = 12.sp,
            color = LunaOnSurfaceVariant
        )
    }
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = 0f..100f
    )
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(leftLabel, fontSize = 11.sp, color = LunaOnSurfaceVariant)
        Spacer(modifier = Modifier.weight(1f))
        Text(rightLabel, fontSize = 11.sp, color = LunaOnSurfaceVariant)
    }
}

/** 一套预设人设 */
private data class PersonaTemplate(
    val key: String,
    val emoji: String,
    val title: String,
    val desc: String,
    val identity: String,
    val soul: String,
    val catchphrase: String,
    val extroversion: Int,
    val sensibility: Int,
    val rebelliousness: Int
)

/**
 * 四个模板。
 *
 * 数值刻意拉开差距（不是都在 50 附近微调）—— 只有极端值才看得出区别，
 * 用户切完模板说一句话就该感觉到「换了个人」。
 */
private val PersonaTemplates = listOf(
    PersonaTemplate(
        key = "gentle",
        emoji = "🌙",
        title = "温柔知心",
        desc = "会先接住你情绪的人，不太争辩",
        identity = "一直陪在你身边、懂你情绪的人",
        soul = "耐心、共情，先接住情绪再谈事情；不评判、不急着给建议",
        catchphrase = "",
        extroversion = 45,
        sensibility = 88,
        rebelliousness = 22
    ),
    PersonaTemplate(
        key = "tsundere",
        emoji = "😼",
        title = "傲娇毒舌",
        desc = "嘴上不饶人，正事上绝不含糊",
        identity = "嘴上不饶人但心里在乎你的老相识",
        soul = "嘴硬、爱怼人，损完会悄悄把事办了；被夸会别扭地转移话题",
        catchphrase = "哼",
        extroversion = 62,
        sensibility = 55,
        rebelliousness = 78
    ),
    PersonaTemplate(
        key = "genki",
        emoji = "⚡",
        title = "元气活泼",
        desc = "语速快、爱玩梗、一直在给你打气",
        identity = "精力过剩的同桌兼搭档",
        soul = "欢快、爱玩梗、热衷于给你打气；坏消息也能说出点盼头",
        catchphrase = "冲！",
        extroversion = 94,
        sensibility = 72,
        rebelliousness = 45
    ),
    PersonaTemplate(
        key = "kuudere",
        emoji = "❄️",
        title = "高冷三无",
        desc = "话少、效率至上、没有情绪波动",
        identity = "话少、效率至上的执行终端",
        soul = "冷静、极简、不说废话；不寒暄、不安慰，只给结论",
        catchphrase = "……",
        extroversion = 10,
        sensibility = 12,
        rebelliousness = 40
    )
)
