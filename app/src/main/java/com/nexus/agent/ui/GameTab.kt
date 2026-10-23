package com.nexus.agent.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexus.agent.game.DatasetCollector
import com.nexus.agent.game.DatasetExporter
import com.nexus.agent.game.CollectFlow
import com.nexus.agent.game.GameKnowledge
import com.nexus.agent.game.GameKnowledgeStore
import com.nexus.agent.game.TouchDatasetCollector
import com.nexus.agent.game.GameProfile
import com.nexus.agent.game.GameProfileStore
import com.nexus.agent.game.GameRule
import com.nexus.agent.game.GameRuleRunner
import com.nexus.agent.game.RuleAction
import com.nexus.agent.overlay.ThinkingOverlay
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

/**
 * 游戏自动化 Tab：GameProfile 列表 / 编辑 / 启动停止。
 */
@Composable
fun GameTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var profiles by remember { mutableStateOf<List<GameProfile>>(emptyList()) }
    var isRunning by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var isCollecting by remember { mutableStateOf(false) }
    var collectCount by remember { mutableStateOf(0) }
    var annotating by remember { mutableStateOf(false) }
    var touchCollecting by remember { mutableStateOf(false) }
    var touchCount by remember { mutableStateOf(0) }
    var touchResult by remember { mutableStateOf("") }
    var touchStepText by remember { mutableStateOf("") }
    var touchStepProgress by remember { mutableStateOf("") }
    var touchResumeInfo by remember { mutableStateOf("") }
    var developerMode by remember { mutableStateOf(false) }
    var titleClicks by remember { mutableStateOf(0) }
    var supportedGames by remember { mutableStateOf<List<GameKnowledge>>(emptyList()) }

    // 编辑状态
    var editing by remember { mutableStateOf(false) }
    var editId by remember { mutableStateOf<String?>(null) }
    var editName by remember { mutableStateOf("") }
    var editPkg by remember { mutableStateOf("") }
    var editFps by remember { mutableStateOf("5") }
    var editRules by remember { mutableStateOf<List<GameRule>>(emptyList()) }

    fun reload() {
        scope.launch { profiles = GameProfileStore.listAll() }
    }

    LaunchedEffect(Unit) {
        supportedGames = GameKnowledgeStore.listPresetIds(context)
            .mapNotNull { GameKnowledgeStore.loadPresetFromAssets(context, it) }
        reload()
        // 检测采集断点
        if (TouchDatasetCollector.hasUnfinishedProgress()) {
            val p = TouchDatasetCollector.getProgressInfo()
            touchResumeInfo = "上次采到第 ${p.stepIndex + 1} 步，共 ${p.totalCount} 张"
        }
        DatasetCollector.onCountChanged = { c ->
            collectCount = c
            ThinkingOverlay.updateCollectCount(c, 1000)
        }
        DatasetCollector.onStateChanged = { on -> isCollecting = on }
        ThinkingOverlay.onCollectStart = {
            TouchDatasetCollector.startStep()
        }
        ThinkingOverlay.onCollectStop = {
            DatasetCollector.enableCollection(false)
            if (TouchDatasetCollector.isFlowActive) {
                val r = TouchDatasetCollector.stopCollection()
                touchCollecting = false
                touchStepText = ""
                touchStepProgress = ""
                touchResult = "已采集 ${r.collected} 张"
            }
        }
        TouchDatasetCollector.onCountChanged = { c ->
            touchCount = c
            ThinkingOverlay.updateCollectCount(c, 2000)
        }
        TouchDatasetCollector.onStepReady = { stepIndex, totalSteps, title, hint ->
            ThinkingOverlay.updateCollectStepReady(stepIndex, totalSteps, title, hint)
            touchStepText = "第 ${stepIndex + 1}/$totalSteps 步 · $title"
            touchStepProgress = "准备好点「开始」"
        }
        TouchDatasetCollector.onStepProgress = { stepIndex, totalSteps, title, hint, collected, target ->
            ThinkingOverlay.updateCollectStep(stepIndex, totalSteps, title, hint, collected, target)
            touchStepText = "第 ${stepIndex + 1}/$totalSteps 步 · $title"
            touchStepProgress = "$collected/$target 张"
        }
        TouchDatasetCollector.onFlowComplete = {
            touchCollecting = false
            touchStepText = ""
            touchStepProgress = ""
            ThinkingOverlay.hide()
            touchResult = "全部步骤采集完成，共 $touchCount 张"
        }
        while (true) {
            isRunning = GameRuleRunner.isRunning
            isCollecting = DatasetCollector.isCollecting()
            collectCount = DatasetCollector.getCollectedCount()
            delay(400)
        }
    }

    if (editing) {
        GameProfileEditor(
            initialId = editId,
            initialName = editName,
            initialPkg = editPkg,
            initialFps = editFps,
            initialRules = editRules,
            onBack = { editing = false },
            onSave = { id, name, pkg, fps, rules ->
                val profile = GameProfile(
                    id = id ?: UUID.randomUUID().toString(),
                    name = name.ifBlank { "未命名" },
                    packageName = pkg.ifBlank { null },
                    perceptionFps = fps.toIntOrNull()?.coerceIn(1, 10) ?: 5,
                    rules = rules
                )
                scope.launch {
                    GameProfileStore.save(profile)
                    reload()
                }
                editing = false
            }
        )
        return
    }

    if (annotating) {
        DatasetAnnotatorScreen(onBack = { annotating = false })
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            "游戏",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.clickable {
                titleClicks++
                if (titleClicks >= 5) { developerMode = true; titleClicks = 0 }
            }
        )
        if (developerMode) {
            Text("开发者模式已开启", color = Color(0xFF8AB4F8), style = MaterialTheme.typography.bodySmall)
        }
        if (isRunning) {
            Text("● 自动化运行中", color = Color(0xFF69F0AE), style = MaterialTheme.typography.bodyMedium)
        }
        if (status.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(status, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 众包采集卡片（所有人可见，边玩边采训练数据）
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("帮我采集原神数据", style = MaterialTheme.typography.titleMedium)
                Text("边玩边采集训练数据，一起让自动化更聪明", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                Spacer(modifier = Modifier.height(8.dp))

                // 启动采集流程（resume=true 续采，false 从头）
                fun startCollectFlow(resume: Boolean) {
                    if (TouchDatasetCollector.startFlow(resume)) {
                        touchCollecting = true
                        touchResult = ""
                        touchResumeInfo = ""
                        ThinkingOverlay.showCollecting(context, 2000, true)
                        val p = TouchDatasetCollector.getProgressInfo()
                        val idx = p.stepIndex.coerceIn(0, CollectFlow.STEPS.size - 1)
                        val s = CollectFlow.STEPS[idx]
                        ThinkingOverlay.updateCollectStepReady(idx, CollectFlow.STEPS.size, s.title, s.hint)
                    } else {
                        Toast.makeText(context, "采集启动失败（需开启无障碍服务）", Toast.LENGTH_SHORT).show()
                    }
                }

                if (!touchCollecting) {
                    if (touchResumeInfo.isNotEmpty()) {
                        Text(touchResumeInfo, color = Color(0xFFFFD54F), style = MaterialTheme.typography.bodySmall)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { startCollectFlow(true) }) { Text("继续采集") }
                            OutlinedButton(onClick = { startCollectFlow(false) }) { Text("重新开始") }
                        }
                    } else {
                        Button(onClick = { startCollectFlow(false) }) { Text("开始采集") }
                    }
                } else {
                    Text("● $touchStepText", color = Color(0xFF8AB4F8), style = MaterialTheme.typography.bodySmall)
                    Text("已采集 $touchCount 张 · 本步 $touchStepProgress", color = Color(0xFF8AB4F8), style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = {
                        touchCollecting = false
                        val r = TouchDatasetCollector.stopCollection()
                        ThinkingOverlay.hide()
                        touchStepText = ""
                        touchStepProgress = ""
                        touchResult = "已采集 ${r.collected} 张（可继续）"
                        if (r.collected > 0) {
                            val p = TouchDatasetCollector.getProgressInfo()
                            touchResumeInfo = "上次采到第 ${p.stepIndex + 1} 步，共 ${p.totalCount} 张"
                        }
                    }) { Text("停止采集") }
                }
                if (touchResult.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(touchResult, color = Color(0xFF69F0AE), style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { exportTouchDataset(context) }) { Text("提交数据") }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 已支持的游戏（只读列表）
        Text("已支持的游戏", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        if (supportedGames.isEmpty()) {
            Text("暂无已支持的游戏", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        } else {
            supportedGames.forEach { game ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(game.gameName, style = MaterialTheme.typography.titleMedium)
                        Text(game.packageName, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        // 开发者模式：采集/标注/导出 + Profile 配置
        if (developerMode) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("训练数据采集", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(
                    checked = isCollecting,
                    onCheckedChange = { on ->
                        DatasetCollector.enableCollection(on)
                        if (on) ThinkingOverlay.showCollecting(context, 1000, false) else ThinkingOverlay.hide()
                    }
                )
            }
            if (isCollecting) {
                Text("已采集 $collectCount/1000 张", color = Color(0xFF8AB4F8), style = MaterialTheme.typography.bodySmall)
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    editId = null
                    editName = ""
                    editPkg = ""
                    editFps = "5"
                    editRules = emptyList()
                    editing = true
                }) { Text("新建 Profile") }
                OutlinedButton(onClick = { annotating = true }) { Text("标注数据集") }
                OutlinedButton(onClick = {
                    val zip = DatasetExporter.export(context)
                    if (zip != null) DatasetExporter.share(context, zip)
                    else Toast.makeText(context, "暂无已标注数据，先采集并标注", Toast.LENGTH_SHORT).show()
                }) { Text("导出数据集") }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (profiles.isEmpty()) {
                Text("暂无游戏配置。点「新建 Profile」创建。", color = Color.Gray)
            } else {
                profiles.forEach { p ->
                    GameProfileCard(
                        profile = p,
                        onStart = {
                            GameRuleRunner.setGameProfile(p)
                            GameRuleRunner.start()
                            status = "已启动 ${p.name}"
                        },
                        onStop = {
                            GameRuleRunner.stop()
                            status = "已停止"
                        },
                        onEdit = {
                            editId = p.id
                            editName = p.name
                            editPkg = p.packageName ?: ""
                            editFps = p.perceptionFps.toString()
                            editRules = p.rules
                            editing = true
                        },
                        onDelete = {
                            scope.launch {
                                GameProfileStore.delete(p.id)
                                reload()
                            }
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

private fun exportTouchDataset(context: android.content.Context) {
    val zip = TouchDatasetCollector.export()
    if (zip != null) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zip)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "提交原神采集数据"))
    } else {
        Toast.makeText(context, "暂无采集数据", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun GameProfileCard(
    profile: GameProfile,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(profile.name, style = MaterialTheme.typography.titleMedium)
            if (profile.packageName != null) {
                Text(profile.packageName, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            Text(
                "${profile.rules.size} 条规则 · ${profile.perceptionFps} FPS",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF8AB4F8)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onStart) { Text("启动") }
                OutlinedButton(onClick = onStop) { Text("停止") }
                OutlinedButton(onClick = onEdit) { Text("编辑") }
                TextButton(onClick = onDelete) { Text("删除", color = Color(0xFFFF8A80)) }
            }
        }
    }
}

@Composable
private fun GameProfileEditor(
    initialId: String?,
    initialName: String,
    initialPkg: String,
    initialFps: String,
    initialRules: List<GameRule>,
    onBack: () -> Unit,
    onSave: (id: String?, name: String, pkg: String, fps: String, rules: List<GameRule>) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var pkg by remember { mutableStateOf(initialPkg) }
    var fps by remember { mutableStateOf(initialFps) }
    var rules by remember { mutableStateOf(initialRules) }

    var showAddRule by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("编辑游戏配置", style = MaterialTheme.typography.titleLarge)

        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(pkg, { pkg = it }, label = { Text("游戏包名（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(fps, { fps = it }, label = { Text("感知帧率（1-10）") }, singleLine = true, modifier = Modifier.fillMaxWidth())

        Spacer(modifier = Modifier.height(12.dp))
        Row {
            Text("规则（${rules.size}）", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Button(onClick = { showAddRule = true }) { Text("+ 添加规则") }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (rules.isEmpty()) {
            Text("暂无规则。规则示例：条件「healthPercent < 30」，动作 TAP，参数 {\"x\":540,\"y\":1200}", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        } else {
            rules.forEachIndexed { i, rule ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row {
                            Text(
                                "${i + 1}. [${rule.action}] ${rule.name}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { rules = rules.filterIndexed { idx, _ -> idx != i } }) {
                                Text("删除", color = Color(0xFFFF8A80), fontSize = 12.sp)
                            }
                        }
                        Text("条件：${rule.condition.ifBlank { "无（总是触发）" }}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onBack) { Text("返回") }
            Button(onClick = { onSave(initialId, name, pkg, fps, rules) }) { Text("保存") }
        }
    }

    if (showAddRule) {
        AddRuleDialog(
            onDismiss = { showAddRule = false },
            onAdd = { rule ->
                rules = rules + rule
                showAddRule = false
            }
        )
    }
}

@Composable
private fun AddRuleDialog(onDismiss: () -> Unit, onAdd: (GameRule) -> Unit) {
    var ruleName by remember { mutableStateOf("") }
    var condition by remember { mutableStateOf("") }
    var action by remember { mutableStateOf(RuleAction.TAP) }
    var paramsJson by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加规则") },
        text = {
            Column {
                OutlinedTextField(ruleName, { ruleName = it }, label = { Text("规则名（可选）") }, singleLine = true)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(condition, { condition = it }, label = { Text("条件，如 healthPercent < 30") }, singleLine = true)
                Spacer(modifier = Modifier.height(8.dp))
                Text("动作类型", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    RuleAction.entries.forEach { a ->
                        val selected = action == a
                        OutlinedButton(onClick = { action = a }, modifier = Modifier.weight(1f)) {
                            Text(a.name, fontSize = 10.sp, color = if (selected) Color(0xFF8AB4F8) else Color.Unspecified)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    paramsJson,
                    { paramsJson = it },
                    label = { Text("参数 JSON（如 {\"x\":540,\"y\":1200}）") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val params = parseParams(paramsJson)
                onAdd(
                    GameRule(
                        id = UUID.randomUUID().toString(),
                        name = ruleName.ifBlank { "规则" },
                        condition = condition.trim(),
                        action = action,
                        actionParams = params
                    )
                )
            }) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 解析参数 JSON → Map<String, Any> */
private fun parseParams(json: String): Map<String, Any> {
    if (json.isBlank()) return emptyMap()
    return try {
        val obj = JSONObject(json)
        val map = mutableMapOf<String, Any>()
        obj.keys().forEach { key -> map[key] = obj.get(key) }
        map
    } catch (e: Throwable) {
        emptyMap()
    }
}
