package com.nexus.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nexus.agent.ui.components.LunaButton
import com.nexus.agent.ui.components.LunaTextButton
import com.nexus.agent.ui.theme.LunaOnSurfaceVariant
import com.nexus.agent.ui.theme.LunaPrimary
import com.nexus.agent.util.UpdateChecker
import java.io.File

/** 更新流程所处阶段 */
enum class UpdatePhase {
    /** 正在下载安装包 */
    DOWNLOADING,
    /** 已下载完成，等待安装 */
    READY,
    /** 已拉起系统安装器 */
    INSTALLING,
    /** 下载/安装出错 */
    ERROR
}

/** 更新界面状态（Activity 字段持有，Compose 只读渲染） */
data class UpdateUiState(
    val version: String,
    val phase: UpdatePhase,
    val percent: Float = 0f,
    val downloaded: Long = 0L,
    val total: Long = 0L,
    val message: String = ""
)

/** 字节数转 MB 文本 */
fun fmtMb(bytes: Long): String = String.format("%.1f MB", bytes / 1024.0 / 1024.0)

/**
 * 「发现新版本」询问框。
 *
 * 只有两个出口：立即更新（走应用内下载）或稍后。不再有「去 GitHub 下载」——
 * 国内跳浏览器下载经常失败，用户要求全流程留在 App 内。
 */
@Composable
fun UpdatePromptDialog(
    info: UpdateChecker.UpdateInfo,
    currentVersion: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    /**
     * 实验版（预发布）标记。
     *
     * 实验版以前没有弹窗入口，只能靠设置页一行不可点的红字，等于装不了。
     * 现在手动「检查更新」命中实验版也会走这个弹窗，所以标题和提示必须区分开，
     * 免得用户把 alpha 当成正式版，装完一脸问号。
     */
    isExperimental: Boolean = false
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (isExperimental) "发现实验版 v${info.version}" else "发现新版本 v${info.version}")
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "当前版本 v$currentVersion → 新版本 v${info.version}",
                    style = MaterialTheme.typography.bodySmall,
                    color = LunaOnSurfaceVariant
                )
                if (isExperimental) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "实验版是未定型的预发布版本，可能有未知问题。想尝鲜就装，出问题可以随时装回正式版。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFE53935)
                    )
                }
                if (info.apkSize > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "安装包 ${fmtMb(info.apkSize)} · 应用内下载安装",
                        style = MaterialTheme.typography.bodySmall,
                        color = LunaOnSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    info.notes.ifBlank { "有新版本可更新，建议升级以获得最新修复。" },
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            LunaTextButton(onClick = onConfirm) { Text("立即更新") }
        },
        dismissButton = {
            LunaTextButton(onClick = onDismiss) { Text("稍后") }
        }
    )
}

/**
 * 强制更新全屏遮罩。
 *
 * 设计意图：**不更新就不给用**。所以：
 * - 铺满屏幕、深色遮罩，底下的界面看得到但点不到（[androidx.compose.ui.input.pointer.pointerInput]
 *   消费掉手势，避免误触穿透）
 * - 返回键由 MainActivity 的 BackHandler 吞掉，这里不提供任何「关闭」入口
 * - 只留「去下载 / 重试」两个动作；下载进度直接显示在遮罩上
 *
 * @param progress 非空表示正在下载/安装，显示进度与阶段文案
 */
@Composable
fun MandatoryUpdateOverlay(
    info: UpdateChecker.UpdateInfo,
    currentVersion: String,
    progress: UpdateUiState?,
    onDownload: () -> Unit,
    onRetry: () -> Unit,
    onOpenReleases: () -> Unit
) {
    val downloading = progress != null &&
        (progress.phase == UpdatePhase.DOWNLOADING || progress.phase == UpdatePhase.INSTALLING)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF0101318))
            .pointerInput(Unit) { detectTapGestures { /* 吞掉所有点击 */ } },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "需要更新",
                style = MaterialTheme.typography.headlineSmall,
                color = Color(0xFFFF6B6B)
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                "为了修复严重问题，请必须更新",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFEDEFF3),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "当前 v$currentVersion → 最新 v${info.version}",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF9AA3AF)
            )

            if (info.notes.isNotBlank()) {
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    info.notes,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFC7CDD6),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .verticalScroll(rememberScrollState())
                )
            }

            if (progress != null) {
                Spacer(modifier = Modifier.height(16.dp))
                val p = progress.percent.coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { p },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                    color = LunaPrimary,
                    trackColor = Color(0xFF2A2F3A)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    progress.message.ifBlank { "准备中…" },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF9AA3AF)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LunaButton(
                    onClick = {
                        if (info.apkUrl != null) onDownload() else onOpenReleases()
                    }
                ) {
                    Text(if (downloading) "下载中…" else if (info.apkUrl != null) "去下载" else "打开下载页")
                }
                LunaTextButton(onClick = onRetry) { Text("重试") }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                "更新完成后本页面会自动消失",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF6F7885)
            )
        }
    }
}

/**
 * 更新下载/安装进度框。
 *
 * 下载阶段可取消；下载完成后只剩「立即安装」（或去授权）。不能用返回键关闭，
 * 避免误触把正在下的包丢掉（.part 会续传，但用户会以为白下了）。
 */
@Composable
fun UpdateProgressDialog(
    state: UpdateUiState,
    apk: File,
    onCancel: () -> Unit,
    onInstall: () -> Unit,
    onRetry: () -> Unit
) {
    val title = when (state.phase) {
        UpdatePhase.DOWNLOADING -> "正在下载 v${state.version}"
        UpdatePhase.READY -> "v${state.version} 已下载"
        UpdatePhase.INSTALLING -> "v${state.version} 准备安装"
        UpdatePhase.ERROR -> "更新失败"
    }

    AlertDialog(
        onDismissRequest = {
            // 安装/错误阶段允许点外部关闭；下载中不允许（防误触中断）
            if (state.phase != UpdatePhase.DOWNLOADING) onCancel()
        },
        title = { Text(title) },
        text = {
            Column {
                if (state.phase == UpdatePhase.DOWNLOADING) {
                    val p = state.percent.coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { p },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp),
                        color = LunaPrimary,
                        trackColor = LunaPrimary.copy(alpha = 0.15f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            state.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = LunaOnSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "${(p * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = LunaPrimary
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "下载走国内加速镜像，中断了会从断点续传。",
                        style = MaterialTheme.typography.bodySmall,
                        color = LunaOnSurfaceVariant
                    )
                } else {
                    Text(
                        state.message.ifBlank {
                            if (state.phase == UpdatePhase.READY) {
                                "点「立即安装」调起系统安装器完成覆盖升级。"
                            } else {
                                ""
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.phase == UpdatePhase.ERROR) Color(0xFFD32F2F) else LunaOnSurfaceVariant
                    )
                    if (state.phase == UpdatePhase.READY && apk.exists()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "安装包：${fmtMb(apk.length())}",
                            style = MaterialTheme.typography.bodySmall,
                            color = LunaOnSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            when (state.phase) {
                UpdatePhase.DOWNLOADING -> LunaTextButton(onClick = onCancel) { Text("取消下载") }
                UpdatePhase.READY -> LunaTextButton(onClick = onInstall) { Text("立即安装") }
                UpdatePhase.INSTALLING -> LunaTextButton(onClick = onCancel) { Text("知道了") }
                UpdatePhase.ERROR -> LunaTextButton(onClick = onRetry) { Text("重试") }
            }
        },
        dismissButton = when (state.phase) {
            UpdatePhase.READY -> {
                { LunaTextButton(onClick = onCancel) { Text("稍后") } }
            }
            UpdatePhase.ERROR -> {
                { LunaTextButton(onClick = onCancel) { Text("关闭") } }
            }
            else -> null
        }
    )
}
