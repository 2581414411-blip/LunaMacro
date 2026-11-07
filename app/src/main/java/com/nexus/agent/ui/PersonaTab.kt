package com.nexus.agent.ui

import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.nexus.agent.copilot.ChatMedia
import com.nexus.agent.copilot.CopilotConfig
import com.nexus.agent.copilot.CopilotEngine
import com.nexus.agent.copilot.EmojiTag
import com.nexus.agent.copilot.PersonaEngine
import com.nexus.agent.copilot.VisionRouter
import com.nexus.agent.persona.HeartbeatScheduler
import com.nexus.agent.persona.InnerMonologueEngine
import com.nexus.agent.ui.components.LunaButton
import com.nexus.agent.ui.components.LunaCard
import com.nexus.agent.ui.components.LunaTextButton
import com.nexus.agent.ui.theme.LunaErrBg
import com.nexus.agent.ui.theme.LunaErrFg
import com.nexus.agent.ui.theme.LunaOkBg
import com.nexus.agent.ui.theme.LunaOnBackground
import com.nexus.agent.ui.theme.LunaOnSurfaceVariant
import com.nexus.agent.ui.theme.LunaOutline
import com.nexus.agent.ui.theme.LunaPrimary
import com.nexus.agent.ui.theme.LunaShadow
import com.nexus.agent.ui.theme.LunaSurface
import com.nexus.agent.ui.theme.LunaSurfaceAlt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
fun PersonaTab(onOpenSettings: () -> Unit = {}) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    var items by remember { mutableStateOf(PersonaEngine.items()) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var showDisclaimer by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var assistantName by remember { mutableStateOf(CopilotConfig.assistantName(context)) }

    // 0.9.2 批次四：羁绊卡片的状态。独立于 items，因为好感度/心情变化时
    // 聊天流可能一条都没变（比如对方只是夸了她一句），靠 items 更新会漏刷。
    val initialProfile = remember { PersonaEngine.profileSnapshot() }
    var bondAffinity by remember { mutableStateOf(initialProfile.affinity) }
    var bondMood by remember { mutableStateOf(initialProfile.mood) }
    var memoryTurns by remember { mutableStateOf(PersonaEngine.turnCount()) }
    var memoryFacts by remember { mutableStateOf(PersonaEngine.memoriesSnapshot().size) }
    var heartbeatOn by remember { mutableStateOf(CopilotConfig.isHeartbeatEnabled(context)) }
    /** 她刚才一个人在想什么（0.9.6 心智引擎）；null = 没在想 / 已经用掉 */
    var innerThought by remember { mutableStateOf(PersonaEngine.innerThoughtSnapshot()) }

    /** 选图/取帧/落盘都在这里做，不要占主线程 */
    val scope = rememberCoroutineScope()
    /** 「发图」入口的弹出菜单（相册图片 / 相册视频 / 拍照） */
    var attachMenu by remember { mutableStateOf(false) }
    /**
     * 0.9.10：发图前的视觉可用性预检。
     *
     * 非 null = 云端和本地都看不了图，弹窗引导去配置；null = 可以发（关闭状态）。
     */
    var visionGate by remember { mutableStateOf<VisionRouter.Readiness?>(null) }

    /** 一次性把卡片要的四个数字全部拉一遍（初始化与每次变更共用） */
    fun refreshBond() {
        val p = PersonaEngine.profileSnapshot()
        bondAffinity = p.affinity
        bondMood = p.mood
        memoryTurns = PersonaEngine.turnCount()
        memoryFacts = PersonaEngine.memoriesSnapshot().size
        innerThought = PersonaEngine.innerThoughtSnapshot()
    }

    /**
     * 发一张图 / 一段视频给她看（0.9.6 批次一）。
     *
     * 顺序：先落盘（聊天记录要能长期回看，相册 Uri 的临时读权限靠不住）→
     * 立刻在聊天流里显示 → 再把位图交给她看。
     * 落盘失败就当场说清楚，绝不发一条「空图片」让她凭空编。
     *
     * 0.9.9 批次一：这一层的每一步都打 `VisionDebug` 日志。
     * 用户报「发了图她装傻」时，第一个要回答的问题就是「图到底有没有从 UI 出去」——
     * 以前这一步完全静默，解码失败只弹个 Toast，日志里连一张图的痕迹都没有。
     */
    fun deliverImage(bitmap: Bitmap?, kind: String) {
        if (bitmap == null) {
            Log.e(
                VisionRouter.DEBUG_TAG,
                "UI层: ${kind}解码失败，没有位图可发（这一轮她什么都不会收到）"
            )
            Toast.makeText(context, "这个${kind}读不出来，换一个试试", Toast.LENGTH_SHORT).show()
            return
        }
        if (bitmap.isRecycled) {
            Log.e(VisionRouter.DEBUG_TAG, "UI层: ${kind}位图已被回收，丢弃")
            Toast.makeText(context, "这个${kind}读不出来了，换一个试试", Toast.LENGTH_SHORT).show()
            return
        }
        Log.d(
            VisionRouter.DEBUG_TAG,
            "UI层: 用户发送了${kind}，大小: ${bitmap.width}×${bitmap.height}" +
                "（${bitmap.byteCount / 1024}KB），busy=$busy，准备传给Engine"
        )
        if (busy) {
            Log.w(VisionRouter.DEBUG_TAG, "UI层: 上一轮还没结束，${kind}被丢弃")
            bitmap.recycle()
            Toast.makeText(context, "等我这一句说完再发", Toast.LENGTH_SHORT).show()
            return
        }
        busy = true
        scope.launch {
            val path = withContext(Dispatchers.IO) { ChatMedia.save(context, bitmap, "user") }
            if (path == null) {
                Log.e(VisionRouter.DEBUG_TAG, "UI层: ${kind}落盘失败，未传给Engine（她看不到这张图）")
                bitmap.recycle()
                busy = false
                Toast.makeText(context, "这个${kind}没能存下来", Toast.LENGTH_SHORT).show()
                return@launch
            }
            Log.d(
                VisionRouter.DEBUG_TAG,
                "UI层: ${kind}已落盘 $path，开始转交 CopilotEngine.ask" +
                    "（userImage=${bitmap.width}×${bitmap.height}，kind=$kind）"
            )
            PersonaEngine.addUserItem("", path)
            CopilotEngine.ask(
                context = context,
                userText = "",
                chatty = true,
                userImage = bitmap,
                userImageKind = kind,
                onDone = {
                    mainHandler.post {
                        busy = false
                        Log.d(VisionRouter.DEBUG_TAG, "UI层: 这一轮结束，解除 busy 并回收位图")
                        // 位图归这里所有：ask 结束后她肯定已经看完了
                        if (!bitmap.isRecycled) runCatching { bitmap.recycle() }
                    }
                }
            )
        }
    }

    // 相册图片：用系统照片选择器，不需要任何存储权限
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        Log.d(VisionRouter.DEBUG_TAG, "UI层: 相册选择器返回 uri=$uri")
        if (uri != null) {
            scope.launch {
                val bmp = withContext(Dispatchers.IO) { ChatMedia.decodeUri(context, uri) }
                Log.d(
                    VisionRouter.DEBUG_TAG,
                    "UI层: 相册图解码结果=" +
                        (bmp?.let { "${it.width}×${it.height}" } ?: "null（读不出来）")
                )
                deliverImage(bmp, "图片")
            }
        }
    }
    // 相册视频：只取一帧给她看（视觉模型看不了视频）
    val pickVideo = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        Log.d(VisionRouter.DEBUG_TAG, "UI层: 视频选择器返回 uri=$uri")
        if (uri != null) {
            scope.launch {
                val bmp = withContext(Dispatchers.IO) { ChatMedia.videoFrame(context, uri) }
                Log.d(
                    VisionRouter.DEBUG_TAG,
                    "UI层: 视频取帧结果=" +
                        (bmp?.let { "${it.width}×${it.height}" } ?: "null（取不到首帧）")
                )
                deliverImage(bmp, "视频")
            }
        }
    }
    // 拍照：写到 cache 里，再经 FileProvider 交给相机 App（不给它写相册的权限）
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val takePhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok ->
        val uri = cameraUri
        Log.d(VisionRouter.DEBUG_TAG, "UI层: 相机返回 ok=$ok uri=$uri")
        if (ok && uri != null) {
            scope.launch {
                val bmp = withContext(Dispatchers.IO) { ChatMedia.decodeUri(context, uri) }
                Log.d(
                    VisionRouter.DEBUG_TAG,
                    "UI层: 照片解码结果=" +
                        (bmp?.let { "${it.width}×${it.height}" } ?: "null（读不出来）")
                )
                deliverImage(bmp, "照片")
            }
        }
    }

    DisposableEffect(Unit) {
        PersonaEngine.init(context)
        PersonaEngine.onChanged = {
            items = PersonaEngine.items()
            refreshBond()
        }
        items = PersonaEngine.items()
        refreshBond()
        assistantName = CopilotConfig.assistantName(context)
        heartbeatOn = CopilotConfig.isHeartbeatEnabled(context)
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
        // ---- 顶部：羁绊卡片（0.9.2 批次四）----
        // 以前这里是「名字 + 已记住 N 轮」两行字，只说明她在运行，不说明你俩什么关系。
        BondCard(
            name = assistantName,
            affinity = bondAffinity,
            mood = bondMood,
            memoryTurns = memoryTurns,
            memoryFacts = memoryFacts,
            heartbeatEnabled = heartbeatOn,
            innerThought = innerThought,
            onHeartbeatChange = { on ->
                heartbeatOn = on
                HeartbeatScheduler.setEnabled(context, on)
                Toast.makeText(
                    context,
                    if (on) "她会在你沉默一天后主动找你" else "已关掉主动问候",
                    Toast.LENGTH_SHORT
                ).show()
            },
            onTryHeartbeat = {
                // 手动触发走 force 分支：跳过时间窗与冷却，用户点了就一定要有结果，
                // 不然「点了没反应」比不发还糟
                HeartbeatScheduler.runNow(context)
                Toast.makeText(context, "正在让她想一句话，几秒后看通知栏", Toast.LENGTH_SHORT).show()
            },
            onThinkNow = {
                // 心智引擎通常两小时才转一次，手动触发只为验证它在不在跑
                InnerMonologueEngine.runNow(context)
                Toast.makeText(context, "她正在一个人想事情，几秒后这里会出现", Toast.LENGTH_SHORT).show()
            }
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End
        ) {
            if (items.isNotEmpty()) {
                LunaTextButton(onClick = {
                    PersonaEngine.clearAll(context)
                    refreshBond()
                    Toast.makeText(context, "聊天记录与记忆已清空", Toast.LENGTH_SHORT).show()
                }) {
                    Text("清空", fontSize = 12.sp, color = LunaOnSurfaceVariant)
                }
            }
            // 人格调教入口（0.8.0）：右上角齿轮
            IconButton(onClick = { showSettings = true }) {
                Text("⚙", fontSize = 17.sp, color = LunaOnSurfaceVariant)
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
                    is PersonaEngine.ChatItem.User -> UserBubble(it.text, it.imagePath)
                    is PersonaEngine.ChatItem.Assistant -> AssistantBubble(it.text)
                    is PersonaEngine.ChatItem.Action -> ActionBubble(it)
                    is PersonaEngine.ChatItem.SystemNotice -> SystemBubble(it)
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
            // 发图入口（0.9.6）：不占主视线，放在输入框左边
            IconButton(
                onClick = {
                    // 0.9.10：先看她现在到底能不能看见，别让用户发完等十几秒才被告知「看不了」。
                    // 预检只读配置 + stat 模型文件，同步执行也不会卡。
                    val r = VisionRouter.readiness(context)
                    if (r.ready) attachMenu = true else visionGate = r
                },
                enabled = !busy
            ) {
                Text("＋", fontSize = 20.sp, color = if (busy) LunaOutline else LunaOnSurfaceVariant)
            }
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

    // ---- 视觉不可用时的强制引导（0.9.10）----
    // 用户点「＋」时如果云端/本地两条腿都断了，就在这里把话说清楚，并直接给去配置的入口。
    visionGate?.let { r ->
        AlertDialog(
            onDismissRequest = { visionGate = null },
            title = { Text("她暂时看不了图", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "⚠️ 当前未配置视觉模型 Key，且本地视觉未下载，AI 将无法看图。请前往设置配置。",
                        fontSize = 13.sp,
                        color = LunaOnBackground
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        r.summary,
                        fontSize = 12.sp,
                        color = LunaOnSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "两条路任选一条：\n" +
                            "① 设置 → LLM 配置 → 云端视觉模型，填通义千问 Key（推荐，快且准）\n" +
                            "② 设置 → 下载模型，装本地视觉（6 个文件，离线可用）",
                        fontSize = 12.sp,
                        color = LunaOnSurfaceVariant
                    )
                }
            },
            confirmButton = {
                LunaTextButton(onClick = {
                    visionGate = null
                    onOpenSettings()
                }) { Text("去设置", fontSize = 13.sp) }
            },
            dismissButton = {
                LunaTextButton(onClick = {
                    // 允许硬发：用户可能只是想先把图存进聊天记录。
                    // 真发出去之后解析失败会有红字系统气泡兜底，不会静默。
                    visionGate = null
                    attachMenu = true
                }) { Text("仍然发图", fontSize = 13.sp) }
            }
        )
    }

    // ---- 发图菜单（0.9.6）----
    if (attachMenu) {
        AttachSheet(
            onDismiss = { attachMenu = false },
            onPickImage = {
                attachMenu = false
                pickImage.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onPickVideo = {
                attachMenu = false
                pickVideo.launch("video/*")
            },
            onTakePhoto = {
                attachMenu = false
                // 相机输出写到 cacheDir，再经 FileProvider 授权；FileProvider 已经配了 cache-path "."
                runCatching {
                    val f = File(context.cacheDir, "camera_${System.currentTimeMillis()}.jpg")
                    val uri = FileProvider.getUriForFile(
                        context, "${context.packageName}.fileprovider", f
                    )
                    cameraUri = uri
                    takePhoto.launch(uri)
                }.onFailure {
                    Toast.makeText(context, "打不开相机：${it.message}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
}

/**
 * 发图方式选择（0.9.6）。
 *
 * 用系统的照片选择器而不是自建网格：不需要任何存储权限，也不会让用户在
 * 「这个 App 为什么要读我全部相册」这件事上迟疑。
 */
@Composable
private fun AttachSheet(
    onDismiss: () -> Unit,
    onPickImage: () -> Unit,
    onPickVideo: () -> Unit,
    onTakePhoto: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发点什么给她看", fontSize = 15.sp, color = LunaOnBackground) },
        text = {
            Column {
                AttachRow("🖼", "从相册选图片", "她能看懂画面里是什么", onPickImage)
                Spacer(modifier = Modifier.height(10.dp))
                AttachRow("🎬", "从相册选视频", "她只看首帧，我会告诉你", onPickVideo)
                Spacer(modifier = Modifier.height(10.dp))
                AttachRow("📷", "现拍一张", "拍完直接发给她", onTakePhoto)
            }
        },
        confirmButton = {
            LunaTextButton(onClick = onDismiss) {
                Text("取消", fontSize = 13.sp, color = LunaOnSurfaceVariant)
            }
        }
    )
}

@Composable
private fun AttachRow(emoji: String, title: String, hint: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = LunaSurfaceAlt
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(emoji, fontSize = 18.sp)
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(title, fontSize = 13.sp, color = LunaOnBackground)
                Text(hint, fontSize = 10.sp, color = LunaOnSurfaceVariant)
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

/** 用户气泡：靠右，主色底；带图时先显示缩略图（0.9.6） */
@Composable
private fun UserBubble(text: String, imagePath: String? = null) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(horizontalAlignment = Alignment.End) {
            if (!imagePath.isNullOrBlank()) {
                ChatImage(imagePath)
                if (text.isNotBlank()) Spacer(modifier = Modifier.height(6.dp))
            }
            if (text.isNotBlank()) {
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
    }
}

/**
 * 聊天里的图片缩略图（0.9.6）。
 *
 * 解码放 IO 线程：原图虽然落盘时压到 1280，`decodeFile` 还是要读盘 + 采样，
 * 在主线程做会让滚动明显卡顿。解码结果跟着 path 缓存，滚动回来不重复解。
 */
@Composable
private fun ChatImage(path: String) {
    val bmp by produceState<Bitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) { ChatMedia.decodeFile(path, ChatMedia.MAX_SHOW_EDGE) }
    }
    val loaded = bmp
    if (loaded == null) {
        // 文件被清理 / 解码失败：留个占位，不让整条记录消失
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(LunaOutline.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center
        ) {
            Text("图片已清理", fontSize = 9.sp, color = LunaOnSurfaceVariant)
        }
    } else {
        Image(
            bitmap = loaded.asImageBitmap(),
            contentDescription = "发给她的图片",
            modifier = Modifier
                .widthIn(max = 210.dp)
                .heightIn(max = 280.dp)
                .clip(RoundedCornerShape(14.dp)),
            contentScale = ContentScale.Fit
        )
    }
}

/** 助手气泡：靠左，浅底；文本里的 [标签] 换成 emoji（0.9.6） */
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
            Text(
                EmojiTag.expand(text),
                style = MaterialTheme.typography.bodyMedium,
                color = LunaOnBackground
            )
        }
    }
}

/**
 * 系统动作卡片（0.9.7 批次三升级）：像微信小程序卡片一样的「执行卡片」。
 *
 * 让她「不仅会说，而且真的动手做了」这件事在聊天流里可见：
 * 左侧一块彩色图标区（成功蓝 / 失败红 / 进行中黄），右侧标题 + 副标题。
 * 和聊天气泡明显区分开，一眼就能看出这是「她真的做了」而非「她说了一句」。
 */
@Composable
private fun ActionBubble(item: PersonaEngine.ChatItem.Action) {
    // 状态三色：成功浅蓝、失败浅红、进行中（ok=false 但非报错）浅黄。
    // Action.ok 目前只区分「成 / 不成」，这里按 ok 给蓝/红；detail 以「执行/打开」开头时视为动作。
    val isError = !item.ok
    val iconBg = if (isError) LunaErrBg else LunaOkBg
    val iconFg = if (isError) LunaErrFg else LunaPrimary
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        Row(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .shadow(elevation = 4.dp, shape = shape, ambientColor = LunaShadow, spotColor = LunaShadow)
                .clip(shape)
                .background(LunaSurface)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 图标区：圆角方块，动作符号
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(iconBg),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (item.ok) "⚡" else "⚠",
                    fontSize = 16.sp,
                    color = iconFg
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = LunaOnBackground,
                    fontWeight = FontWeight.SemiBold
                )
                if (item.detail.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        item.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isError) LunaErrFg else LunaOnSurfaceVariant,
                        lineHeight = 16.sp,
                        maxLines = 4
                    )
                }
            }
            // 状态角标
            Text(
                if (item.ok) "已执行" else "未完成",
                fontSize = 10.sp,
                color = if (item.ok) LunaPrimary.copy(alpha = 0.55f) else LunaErrFg,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * 系统红字消息（0.9.9）。
 *
 * 视觉解析失败这类「不是她说的、但用户必须立刻知道」的话放这里：
 * 居中、浅红底、⚠ 图标，和她的聊天气泡明显区分，一眼能看出是系统在报障。
 * 以前这类信息只写进 logcat，用户在界面上什么都看不到 —— 体感就是「装死」。
 */
@Composable
private fun SystemBubble(item: PersonaEngine.ChatItem.SystemNotice) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        Row(
            modifier = Modifier
                .widthIn(max = 330.dp)
                .clip(shape)
                .background(LunaErrBg)
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text("⚠", fontSize = 13.sp, color = LunaErrFg)
            Spacer(modifier = Modifier.width(7.dp))
            Text(
                item.text,
                style = MaterialTheme.typography.bodySmall,
                color = LunaErrFg,
                lineHeight = 17.sp
            )
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
