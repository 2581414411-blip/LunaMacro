package com.nexus.agent.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexus.agent.game.ButtonInfo
import com.nexus.agent.game.DatasetAnnotator
import com.nexus.agent.game.ScreenAnalysis

private val SCREEN_TYPES = listOf("主界面", "委托界面", "派遣界面", "加载中", "战斗界面", "其他")

/**
 * SFT 数据集手动标注界面。
 *
 * 加载未标注截图 → 选界面类型 → 点图标记按钮 → 保存 label → 自动跳下一张。
 */
@Composable
fun DatasetAnnotatorScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    var images by remember { mutableStateOf<List<String>>(emptyList()) }
    var index by remember { mutableStateOf(0) }
    var screenType by remember { mutableStateOf("其他") }
    var buttons by remember { mutableStateOf<List<ButtonInfo>>(emptyList()) }
    var description by remember { mutableStateOf("") }
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    var showAddBtn by remember { mutableStateOf(false) }
    var pendingX by remember { mutableStateOf(0f) }
    var pendingY by remember { mutableStateOf(0f) }
    var btnText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { images = DatasetAnnotator.listUnannotated(context) }

    LaunchedEffect(index, images) {
        if (index >= 0 && index < images.size) {
            val bmp = DatasetAnnotator.loadImage(context, images[index])
            bitmap = bmp?.asImageBitmap()
            val existing = DatasetAnnotator.loadAnalysis(context, images[index])
            screenType = existing?.screenType ?: "其他"
            buttons = existing?.buttons ?: emptyList()
            description = existing?.description ?: ""
        }
    }

    if (images.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("没有未标注的截图", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text("先开启「训练数据采集」跑一会儿采集截图，再来标注。", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onBack) { Text("返回") }
        }
        return
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("标注 ${index + 1}/${images.size}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("返回") }
        }

        Text("界面类型", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SCREEN_TYPES.forEach { t ->
                val sel = screenType == t
                OutlinedButton(onClick = { screenType = t }, modifier = Modifier.weight(1f)) {
                    Text(t, fontSize = 11.sp, color = if (sel) Color(0xFF8AB4F8) else Color.Unspecified)
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // 截图显示区（点击标记按钮位置）
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            bitmap?.let { bmp ->
                Image(
                    bitmap = bmp,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                pendingX = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                pendingY = (offset.y / size.height.toFloat()).coerceIn(0f, 1f)
                                btnText = ""
                                showAddBtn = true
                            }
                        }
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Text("已标记按钮（${buttons.size}）· 点图上标记", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        Column(Modifier.height(120.dp).verticalScroll(rememberScrollState())) {
            buttons.forEachIndexed { i, b ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${b.text} (${"%.2f".format(b.x)}, ${"%.2f".format(b.y)})", fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { buttons = buttons.filterIndexed { j, _ -> j != i } }) {
                        Text("删除", color = Color(0xFFFF8A80), fontSize = 12.sp)
                    }
                }
            }
        }

        OutlinedTextField(description, { description = it }, label = { Text("描述（可选）") }, modifier = Modifier.fillMaxWidth())

        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { if (index < images.size - 1) index++ },
                modifier = Modifier.weight(1f)
            ) { Text("跳过") }
            Button(
                onClick = {
                    DatasetAnnotator.saveAnalysis(context, images[index], ScreenAnalysis(screenType, buttons, description))
                    if (index < images.size - 1) index++ else images = DatasetAnnotator.listUnannotated(context)
                },
                modifier = Modifier.weight(1f)
            ) { Text("保存") }
        }
    }

    if (showAddBtn) {
        AlertDialog(
            onDismissRequest = { showAddBtn = false },
            title = { Text("标记按钮") },
            text = {
                Column {
                    Text("坐标 (${"%.2f".format(pendingX)}, ${"%.2f".format(pendingY)})", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(btnText, { btnText = it }, label = { Text("按钮文本") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    buttons = buttons + ButtonInfo(btnText.ifBlank { "按钮" }, pendingX, pendingY)
                    showAddBtn = false
                }) { Text("添加") }
            },
            dismissButton = { TextButton(onClick = { showAddBtn = false }) { Text("取消") } }
        )
    }
}
