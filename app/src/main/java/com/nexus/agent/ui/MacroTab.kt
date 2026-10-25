package com.nexus.agent.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexus.agent.macro.Macro
import com.nexus.agent.macro.MacroPlayer
import com.nexus.agent.macro.MacroRecorder
import com.nexus.agent.macro.MacroStep
import com.nexus.agent.macro.MacroStore
import com.nexus.agent.macro.StepType
import com.nexus.agent.overlay.ThinkingOverlay
import com.nexus.agent.ui.components.LunaOutlinedButton
import com.nexus.agent.ui.components.LunaTextButton
import com.nexus.agent.ui.components.GeometricTitle
import com.nexus.agent.ui.components.LunaButton
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 宏管理 Tab：列表 / 编辑 / 录制入口。
 */
@Composable
fun MacroTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var macros by remember { mutableStateOf<List<Macro>>(emptyList()) }
    var playStatus by remember { mutableStateOf("") }
    var isRecording by remember { mutableStateOf(false) }
    var recordCount by remember { mutableStateOf(0) }

    // 编辑状态
    var editing by remember { mutableStateOf(false) }
    var editId by remember { mutableStateOf<String?>(null) }
    var editName by remember { mutableStateOf("") }
    var editDesc by remember { mutableStateOf("") }
    var editPkg by remember { mutableStateOf("") }
    var editSteps by remember { mutableStateOf<List<MacroStep>>(emptyList()) }

    // 对话框状态
    var showRecordDialog by remember { mutableStateOf(false) }
    var recName by remember { mutableStateOf("") }
    var recDesc by remember { mutableStateOf("") }
    var recPkg by remember { mutableStateOf("") }
    var showAddStep by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch { macros = MacroStore.listAll() }
    }

    LaunchedEffect(Unit) {
        reload()
        // 录制回调（全局单例，设置一次）
        ThinkingOverlay.onRecordStop = {
            MacroRecorder.stopRecording { _ -> reload() }
            ThinkingOverlay.hide()
            Toast.makeText(context, "录制完成，已保存", Toast.LENGTH_SHORT).show()
        }
        ThinkingOverlay.onRecordCancel = {
            MacroRecorder.cancelRecording()
            ThinkingOverlay.hide()
        }
        ThinkingOverlay.onCrosshairConfirm = { x, y ->
            MacroRecorder.addManualTap(x, y)
            ThinkingOverlay.showRecording(context)
        }
        ThinkingOverlay.onCrosshairCancel = {
            ThinkingOverlay.showRecording(context)
        }
        MacroRecorder.onStepCountChanged = { count -> ThinkingOverlay.updateRecordingCount(count) }
        // 轮询录制状态（仅用于本页显示）
        while (true) {
            isRecording = MacroRecorder.isRecording
            recordCount = MacroRecorder.stepCount()
            delay(400)
        }
    }

    if (editing) {
        MacroEditor(
            initialName = editName,
            initialDesc = editDesc,
            initialPkg = editPkg,
            initialSteps = editSteps,
            onBack = { editing = false },
            onSave = { name, desc, pkg, steps ->
                val m = Macro(
                    id = editId ?: UUID.randomUUID().toString(),
                    name = name.ifBlank { "未命名宏" },
                    description = desc,
                    targetPackage = pkg.ifBlank { null },
                    steps = steps
                )
                scope.launch {
                    MacroStore.save(m)
                    reload()
                }
                editing = false
            }
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        GeometricTitle("宏")
        if (isRecording) {
            Text(
                "● 录制中（$recordCount 步）",
                color = Color(0xFFFF5252),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LunaButton(onClick = {
                editId = null
                editName = ""
                editDesc = ""
                editPkg = ""
                editSteps = emptyList()
                editing = true
            }) { Text("新建宏") }
            LunaOutlinedButton(onClick = {
                recName = ""
                recDesc = ""
                recPkg = ""
                showRecordDialog = true
            }) { Text("录制新宏") }
        }

        if (playStatus.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(playStatus, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (macros.isEmpty()) {
            Text("暂无宏。点「新建宏」手动添加步骤，或「录制新宏」录一段操作。", color = Color.Gray)
        } else {
            macros.forEach { m ->
                MacroCard(
                    macro = m,
                    onPlay = {
                        playStatus = "回放中…"
                        scope.launch {
                            val r = MacroPlayer.play(m)
                            playStatus = r.summary()
                        }
                    },
                    onEdit = {
                        editId = m.id
                        editName = m.name
                        editDesc = m.description
                        editPkg = m.targetPackage ?: ""
                        editSteps = m.steps
                        editing = true
                    },
                    onDelete = {
                        scope.launch {
                            MacroStore.delete(m.id)
                            reload()
                        }
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }

    // ---- 对话框 ----
    if (showRecordDialog) {
        AlertDialog(
            onDismissRequest = { showRecordDialog = false },
            title = { Text("录制新宏") },
            text = {
                Column {
                    OutlinedTextField(recName, { recName = it }, label = { Text("名称") }, singleLine = true)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(recDesc, { recDesc = it }, label = { Text("描述（可选）") }, singleLine = true)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(recPkg, { recPkg = it }, label = { Text("目标应用包名（可选）") }, singleLine = true)
                }
            },
            confirmButton = {
                LunaTextButton(onClick = {
                    showRecordDialog = false
                    MacroRecorder.startRecording(recName, recDesc, recPkg.ifBlank { null })
                    ThinkingOverlay.showRecording(context)
                }) { Text("开始录制") }
            },
            dismissButton = { LunaTextButton(onClick = { showRecordDialog = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun MacroCard(macro: Macro, onPlay: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row {
                Column(modifier = Modifier.weight(1f)) {
                    Text(macro.name, style = MaterialTheme.typography.titleMedium)
                    if (macro.description.isNotBlank()) {
                        Text(macro.description, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                    Text(
                        "步骤 ${macro.steps.size}${if (macro.targetPackage != null) " · ${macro.targetPackage}" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF8AB4F8)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LunaOutlinedButton(onClick = onPlay) { Text("回放") }
                LunaOutlinedButton(onClick = onEdit) { Text("编辑") }
                LunaTextButton(onClick = onDelete) { Text("删除", color = Color(0xFFFF8A80)) }
            }
        }
    }
}

/**
 * 宏编辑页：名称/描述/目标包名 + 步骤列表（添加/删除/上移/下移）+ 保存。
 */
@Composable
private fun MacroEditor(
    initialName: String,
    initialDesc: String,
    initialPkg: String,
    initialSteps: List<MacroStep>,
    onBack: () -> Unit,
    onSave: (name: String, desc: String, pkg: String, steps: List<MacroStep>) -> Unit
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(initialName) }
    var desc by remember { mutableStateOf(initialDesc) }
    var pkg by remember { mutableStateOf(initialPkg) }
    var steps by remember { mutableStateOf(initialSteps) }

    var showAddStep by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("编辑宏", style = MaterialTheme.typography.titleLarge)

        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(desc, { desc = it }, label = { Text("描述") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(pkg, { pkg = it }, label = { Text("目标应用包名（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())

        Spacer(modifier = Modifier.height(12.dp))
        Row {
            Text("步骤（${steps.size}）", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            LunaButton(onClick = { showAddStep = true }) { Text("+ 添加步骤") }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (steps.isEmpty()) {
            Text("暂无步骤，点「添加步骤」", color = Color.Gray)
        } else {
            steps.forEachIndexed { i, step ->
                StepRow(
                    index = i,
                    step = step,
                    total = steps.size,
                    onDelete = { steps = steps.filterIndexed { idx, _ -> idx != i } },
                    onMoveUp = {
                        if (i > 0) {
                            val list = steps.toMutableList()
                            val tmp = list[i]
                            list[i] = list[i - 1]
                            list[i - 1] = tmp
                            steps = list
                        }
                    },
                    onMoveDown = {
                        if (i < steps.size - 1) {
                            val list = steps.toMutableList()
                            val tmp = list[i]
                            list[i] = list[i + 1]
                            list[i + 1] = tmp
                            steps = list
                        }
                    }
                )
                Spacer(modifier = Modifier.height(6.dp))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LunaOutlinedButton(onClick = onBack) { Text("返回") }
            LunaButton(onClick = { onSave(name, desc, pkg, steps) }) { Text("保存") }
        }
    }

    if (showAddStep) {
        AddStepDialog(
            onDismiss = { showAddStep = false },
            onAdd = { step ->
                steps = steps + step
                showAddStep = false
            }
        )
    }
}

@Composable
private fun StepRow(
    index: Int,
    step: MacroStep,
    total: Int,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row {
                Text(
                    "${index + 1}. ${step.type.name}  ${paramsBrief(step)}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                LunaTextButton(onClick = onMoveUp, enabled = index > 0) { Text("↑", fontSize = 14.sp) }
                LunaTextButton(onClick = onMoveDown, enabled = index < total - 1) { Text("↓", fontSize = 14.sp) }
                LunaTextButton(onClick = onDelete) { Text("删除", color = Color(0xFFFF8A80), fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun AddStepDialog(onDismiss: () -> Unit, onAdd: (MacroStep) -> Unit) {
    var type by remember { mutableStateOf(StepType.TAP) }
    var x by remember { mutableStateOf("") }
    var y by remember { mutableStateOf("") }
    var x2 by remember { mutableStateOf("") }
    var y2 by remember { mutableStateOf("") }
    var ms by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加步骤") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(StepType.TAP, StepType.LONG_PRESS, StepType.SWIPE, StepType.WAIT).forEach { t ->
                        val selected = type == t
                        OutlinedButton(
                            onClick = { type = t },
                            modifier = Modifier.weight(1f)
                        ) { Text(t.name, fontSize = 11.sp, color = if (selected) Color(0xFF8AB4F8) else Color.Unspecified) }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                when (type) {
                    StepType.TAP, StepType.LONG_PRESS -> {
                        Row {
                            OutlinedTextField(x, { x = it }, label = { Text("X") }, singleLine = true, modifier = Modifier.weight(1f))
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedTextField(y, { y = it }, label = { Text("Y") }, singleLine = true, modifier = Modifier.weight(1f))
                        }
                    }
                    StepType.SWIPE -> {
                        Row {
                            OutlinedTextField(x, { x = it }, label = { Text("起X") }, singleLine = true, modifier = Modifier.weight(1f))
                            Spacer(modifier = Modifier.width(4.dp))
                            OutlinedTextField(y, { y = it }, label = { Text("起Y") }, singleLine = true, modifier = Modifier.weight(1f))
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row {
                            OutlinedTextField(x2, { x2 = it }, label = { Text("止X") }, singleLine = true, modifier = Modifier.weight(1f))
                            Spacer(modifier = Modifier.width(4.dp))
                            OutlinedTextField(y2, { y2 = it }, label = { Text("止Y") }, singleLine = true, modifier = Modifier.weight(1f))
                        }
                    }
                    StepType.WAIT -> {
                        OutlinedTextField(ms, { ms = it }, label = { Text("等待毫秒") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    else -> {}
                }
            }
        },
        confirmButton = {
            LunaTextButton(onClick = {
                val step = when (type) {
                    StepType.TAP -> {
                        val px = x.toFloatOrNull() ?: return@LunaTextButton
                        val py = y.toFloatOrNull() ?: return@LunaTextButton
                        MacroStep(StepType.TAP, mapOf("x" to px.toDouble(), "y" to py.toDouble()))
                    }
                    StepType.LONG_PRESS -> {
                        val px = x.toFloatOrNull() ?: return@LunaTextButton
                        val py = y.toFloatOrNull() ?: return@LunaTextButton
                        MacroStep(StepType.LONG_PRESS, mapOf("x" to px.toDouble(), "y" to py.toDouble(), "duration" to 800))
                    }
                    StepType.SWIPE -> {
                        val px = x.toFloatOrNull() ?: return@LunaTextButton
                        val py = y.toFloatOrNull() ?: return@LunaTextButton
                        val px2 = x2.toFloatOrNull() ?: return@LunaTextButton
                        val py2 = y2.toFloatOrNull() ?: return@LunaTextButton
                        MacroStep(StepType.SWIPE, mapOf("x1" to px.toDouble(), "y1" to py.toDouble(), "x2" to px2.toDouble(), "y2" to py2.toDouble(), "duration" to 300))
                    }
                    StepType.WAIT -> {
                        val m = ms.toLongOrNull() ?: return@LunaTextButton
                        MacroStep(StepType.WAIT, mapOf("ms" to m))
                    }
                    else -> return@LunaTextButton
                }
                onAdd(step)
            }) { Text("添加") }
        },
        dismissButton = { LunaTextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 步骤参数简要展示 */
private fun paramsBrief(step: MacroStep): String {
    return when (step.type) {
        StepType.TAP, StepType.LONG_PRESS -> {
            val x = step.params["x"]
            val y = step.params["y"]
            "($x, $y)"
        }
        StepType.SWIPE -> {
            "(${step.params["x1"]},${step.params["y1"]})→(${step.params["x2"]},${step.params["y2"]})"
        }
        StepType.WAIT -> "${step.params["ms"]}ms"
        StepType.MULTI_TOUCH -> "${(step.params["points"] as? List<*>)?.size ?: 0} 点"
        else -> step.params.toString()
    }
}
