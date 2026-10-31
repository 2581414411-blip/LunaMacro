package com.nexus.agent.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import com.nexus.agent.schedule.ScheduleRunner
import com.nexus.agent.schedule.ScheduleScheduler
import com.nexus.agent.schedule.ScheduleStore
import com.nexus.agent.schedule.ScheduledTask
import com.nexus.agent.ui.components.LunaButton
import com.nexus.agent.ui.components.LunaCard
import com.nexus.agent.ui.components.LunaOutlinedButton
import com.nexus.agent.ui.components.LunaTextButton
import com.nexus.agent.ui.theme.LunaErrFg
import com.nexus.agent.ui.theme.LunaOnBackground
import com.nexus.agent.ui.theme.LunaOnSurfaceVariant
import com.nexus.agent.ui.theme.LunaPrimary
import com.nexus.agent.ui.theme.LunaSurfaceAlt
import com.nexus.agent.ui.theme.LunaWarnFg
import java.util.Calendar
import java.util.UUID

/**
 * 首页「定时任务」区块。
 *
 * 交互取舍：
 * - 一条任务一行：名字 + 下次触发时间 + 开关；次要操作（试跑 / 删除）放到下一行小字里，
 *   避免主行太挤。
 * - 编辑走弹窗，不改时间也能直接存；保存后立刻重排闹钟，不依赖 App 重启。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScheduleSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var tasks by remember { mutableStateOf(ScheduleStore.list(context)) }
    var editing by remember { mutableStateOf<ScheduledTask?>(null) }
    var creating by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ScheduledTask?>(null) }
    var exactOk by remember { mutableStateOf(ScheduleScheduler.canUseExactAlarms(context)) }

    fun refresh() {
        tasks = ScheduleStore.list(context)
        exactOk = ScheduleScheduler.canUseExactAlarms(context)
    }

    LunaCard(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "⏰ 定时任务",
                style = MaterialTheme.typography.titleMedium,
                color = LunaOnBackground,
                modifier = Modifier.weight(1f)
            )
            LunaTextButton(onClick = { creating = true }) {
                Text("+ 新建", style = MaterialTheme.typography.labelMedium, color = LunaPrimary)
            }
        }
        Text(
            "到点自动替你干一件事，比如早上签到、晚上清后台",
            style = MaterialTheme.typography.bodySmall,
            color = LunaOnSurfaceVariant
        )

        if (!exactOk) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "未授予精确闹钟，触发可能晚几分钟",
                    style = MaterialTheme.typography.bodySmall,
                    color = LunaWarnFg,
                    modifier = Modifier.weight(1f)
                )
                LunaTextButton(onClick = { openExactAlarmSettings(context) }) {
                    Text("去开启", style = MaterialTheme.typography.labelSmall, color = LunaPrimary)
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (tasks.isEmpty()) {
            Text(
                "还没有定时任务。点右上角「+ 新建」加一条。",
                style = MaterialTheme.typography.bodySmall,
                color = LunaOnSurfaceVariant
            )
        } else {
            tasks.forEach { task ->
                TaskRow(
                    task = task,
                    onToggle = { on ->
                        val updated = task.copy(enabled = on)
                        ScheduleStore.upsert(context, updated)
                        if (on) ScheduleScheduler.schedule(context, updated)
                        else ScheduleScheduler.cancel(context, task.id)
                        refresh()
                    },
                    onEdit = { editing = task },
                    onRunNow = {
                        if (ScheduleRunner.isRunning) {
                            Toast.makeText(context, "已有任务在跑，稍后再试", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "开始执行：${task.title}", Toast.LENGTH_SHORT).show()
                            ScheduleRunner.trigger(context, task.id, manual = true)
                        }
                    },
                    onDelete = { pendingDelete = task }
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                "任务执行时手机需解锁、屏幕可亮；锁屏或无障碍断开会自动跳过并通知你。",
                style = MaterialTheme.typography.labelSmall,
                color = LunaOnSurfaceVariant
            )
        }
    }

    if (creating) {
        ScheduleEditDialog(
            initial = null,
            onDismiss = { creating = false },
            onSave = { task ->
                ScheduleStore.upsert(context, task)
                if (task.enabled) ScheduleScheduler.schedule(context, task)
                creating = false
                refresh()
            }
        )
    }

    editing?.let { task ->
        ScheduleEditDialog(
            initial = task,
            onDismiss = { editing = null },
            onSave = { updated ->
                ScheduleStore.upsert(context, updated)
                if (updated.enabled) ScheduleScheduler.schedule(context, updated)
                else ScheduleScheduler.cancel(context, updated.id)
                editing = null
                refresh()
            }
        )
    }

    pendingDelete?.let { task ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除定时任务") },
            text = { Text("「${task.title}」会被删掉，之后不会再自动执行。") },
            confirmButton = {
                LunaTextButton(onClick = {
                    ScheduleScheduler.cancel(context, task.id)
                    ScheduleStore.delete(context, task.id)
                    pendingDelete = null
                    refresh()
                }) { Text("删除", color = LunaErrFg) }
            },
            dismissButton = {
                LunaTextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun TaskRow(
    task: ScheduledTask,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onRunNow: () -> Unit,
    onDelete: () -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    task.title.ifBlank { task.prompt.take(12) },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = LunaOnBackground
                )
                Text(
                    if (task.enabled) {
                        "${task.repeatText()} · 下次 ${formatNext(task.nextTriggerAt())}"
                    } else {
                        "${task.repeatText()} · 已关闭"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = LunaOnSurfaceVariant
                )
            }
            Switch(checked = task.enabled, onCheckedChange = onToggle)
        }

        if (task.lastRunAt > 0L) {
            val statusText = when (task.lastStatus) {
                "ok" -> "成功"
                "skip" -> "跳过"
                "fail" -> "失败"
                else -> ""
            }
            Text(
                "上次 ${formatDateTime(task.lastRunAt)} $statusText" +
                    if (task.lastSummary.isNotBlank()) "：${task.lastSummary.take(40)}" else "",
                style = MaterialTheme.typography.labelSmall,
                color = if (task.lastStatus == "ok") LunaOnSurfaceVariant else LunaWarnFg
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            LunaTextButton(onClick = onEdit) {
                Text("编辑", style = MaterialTheme.typography.labelSmall, color = LunaPrimary)
            }
            LunaTextButton(onClick = onRunNow) {
                Text("试跑", style = MaterialTheme.typography.labelSmall, color = LunaPrimary)
            }
            Spacer(modifier = Modifier.weight(1f))
            LunaTextButton(onClick = onDelete) {
                Text("删除", style = MaterialTheme.typography.labelSmall, color = LunaErrFg)
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
    }
}

/**
 * 新建 / 编辑弹窗。
 *
 * [initial] 为空即新建。校验只有一条硬规则：任务内容不能空。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleEditDialog(
    initial: ScheduledTask?,
    onDismiss: () -> Unit,
    onSave: (ScheduledTask) -> Unit
) {
    val context = LocalContext.current
    val now = remember { Calendar.getInstance() }

    var title by remember { mutableStateOf(initial?.title ?: "") }
    var prompt by remember { mutableStateOf(initial?.prompt ?: "") }
    var repeat by remember { mutableStateOf(initial?.repeat ?: ScheduledTask.Repeat.DAILY) }
    var hour by remember { mutableStateOf(initial?.hour ?: 8) }
    var minute by remember { mutableStateOf(initial?.minute ?: 0) }
    var days by remember { mutableStateOf(initial?.daysOfWeek ?: listOf(ScheduledTask.isoDayOfWeek(now))) }
    var onceAt by remember {
        mutableStateOf(
            initial?.onceAt?.takeIf { it > 0L }
                ?: (now.timeInMillis + 60 * 60 * 1000L)
        )
    }
    var error by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新建定时任务" else "编辑定时任务") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("要做什么", fontSize = 13.sp) },
                    placeholder = { Text("比如：打开微信签到领每日奖励", fontSize = 12.sp) },
                    minLines = 2,
                    maxLines = 4
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("名字（可留空）", fontSize = 13.sp) },
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(10.dp))
                Text("重复", style = MaterialTheme.typography.labelMedium, color = LunaOnSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = repeat == ScheduledTask.Repeat.DAILY,
                        onClick = { repeat = ScheduledTask.Repeat.DAILY },
                        label = { Text("每天", fontSize = 12.sp) }
                    )
                    FilterChip(
                        selected = repeat == ScheduledTask.Repeat.WEEKLY,
                        onClick = { repeat = ScheduledTask.Repeat.WEEKLY },
                        label = { Text("每周", fontSize = 12.sp) }
                    )
                    FilterChip(
                        selected = repeat == ScheduledTask.Repeat.ONCE,
                        onClick = { repeat = ScheduledTask.Repeat.ONCE },
                        label = { Text("只一次", fontSize = 12.sp) }
                    )
                }

                if (repeat == ScheduledTask.Repeat.WEEKLY) {
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (1..7).forEach { d ->
                            FilterChip(
                                selected = days.contains(d),
                                onClick = {
                                    days = if (days.contains(d)) days - d else (days + d).sorted()
                                },
                                label = { Text(ScheduledTask.WEEK_CN[d - 1], fontSize = 12.sp) }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (repeat == ScheduledTask.Repeat.ONCE) "执行时间" else "每天时刻",
                        style = MaterialTheme.typography.labelMedium,
                        color = LunaOnSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    if (repeat == ScheduledTask.Repeat.ONCE) {
                        LunaOutlinedButton(onClick = {
                            pickDate(context, onceAt) { at ->
                                val c = Calendar.getInstance().apply { timeInMillis = at }
                                val old = Calendar.getInstance().apply { timeInMillis = onceAt }
                                c.set(Calendar.HOUR_OF_DAY, old.get(Calendar.HOUR_OF_DAY))
                                c.set(Calendar.MINUTE, old.get(Calendar.MINUTE))
                                onceAt = c.timeInMillis
                            }
                        }) { Text(formatDate(onceAt), fontSize = 12.sp) }
                        Spacer(modifier = Modifier.width(6.dp))
                        LunaOutlinedButton(onClick = {
                            pickTime(context, 0, 0) { h, m ->
                                val c = Calendar.getInstance().apply { timeInMillis = onceAt }
                                c.set(Calendar.HOUR_OF_DAY, h)
                                c.set(Calendar.MINUTE, m)
                                onceAt = c.timeInMillis
                            }
                        }) { Text(formatClock(onceAt), fontSize = 12.sp) }
                    } else {
                        LunaOutlinedButton(onClick = {
                            pickTime(context, hour, minute) { h, m ->
                                hour = h
                                minute = m
                            }
                        }) { Text("%02d:%02d".format(hour, minute), fontSize = 12.sp) }
                    }
                }

                if (error.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(error, style = MaterialTheme.typography.bodySmall, color = LunaErrFg)
                }
            }
        },
        confirmButton = {
            LunaButton(onClick = {
                val text = prompt.trim()
                if (text.isEmpty()) {
                    error = "先写清楚要做什么"
                    return@LunaButton
                }
                if (repeat == ScheduledTask.Repeat.WEEKLY && days.isEmpty()) {
                    error = "至少选一个星期几"
                    return@LunaButton
                }
                if (repeat == ScheduledTask.Repeat.ONCE && onceAt <= System.currentTimeMillis()) {
                    error = "这个时间已经过去了，换个将来的时间"
                    return@LunaButton
                }
                val finalTitle = title.trim().ifBlank { text.take(12) }
                onSave(
                    ScheduledTask(
                        id = initial?.id ?: UUID.randomUUID().toString(),
                        title = finalTitle,
                        prompt = text,
                        repeat = repeat,
                        hour = hour,
                        minute = minute,
                        daysOfWeek = days,
                        onceAt = onceAt,
                        enabled = true,
                        lastRunAt = initial?.lastRunAt ?: 0L,
                        lastStatus = initial?.lastStatus ?: "",
                        lastSummary = initial?.lastSummary ?: ""
                    )
                )
            }) { Text("保存", fontSize = 14.sp) }
        },
        dismissButton = {
            LunaTextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

// ---------------------------------------------------------------------- 工具

private fun pickTime(context: Context, hour: Int, minute: Int, onPicked: (Int, Int) -> Unit) {
    TimePickerDialog(context, { _, h, m -> onPicked(h, m) }, hour, minute, true).show()
}

private fun pickDate(context: Context, base: Long, onPicked: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = base }
    DatePickerDialog(
        context,
        { _, y, m, d ->
            val picked = Calendar.getInstance().apply {
                set(Calendar.YEAR, y)
                set(Calendar.MONTH, m)
                set(Calendar.DAY_OF_MONTH, d)
            }
            onPicked(picked.timeInMillis)
        },
        c.get(Calendar.YEAR),
        c.get(Calendar.MONTH),
        c.get(Calendar.DAY_OF_MONTH)
    ).show()
}

private fun openExactAlarmSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
        data = Uri.parse("package:${context.packageName}")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
        .onFailure {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }
        }
}

/** 「今天 08:00」「明天 08:00」「周三 20:00（11月4日）」 */
private fun formatNext(ts: Long): String {
    if (ts <= 0L) return "未排程"
    val c = Calendar.getInstance().apply { timeInMillis = ts }
    val hm = "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    // 按「自然日」算差几天，不能直接拿毫秒数除——差 12 小时也跨不了天
    val targetDay = Calendar.getInstance().apply {
        timeInMillis = ts
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val today = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val dayDiff = Math.round(
        (targetDay.timeInMillis - today.timeInMillis).toDouble() / (24 * 60 * 60 * 1000.0)
    ).toInt()
    return when (dayDiff) {
        0 -> "今天 $hm"
        1 -> "明天 $hm"
        else -> "周${ScheduledTask.WEEK_CN[ScheduledTask.isoDayOfWeek(c) - 1]} $hm" +
            "（${c.get(Calendar.MONTH) + 1}月${c.get(Calendar.DAY_OF_MONTH)}日）"
    }
}

private fun formatDateTime(ts: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = ts }
    return "${c.get(Calendar.MONTH) + 1}/${c.get(Calendar.DAY_OF_MONTH)} " +
        "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
}

private fun formatDate(ts: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = ts }
    return "${c.get(Calendar.MONTH) + 1}月${c.get(Calendar.DAY_OF_MONTH)}日"
}

private fun formatClock(ts: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = ts }
    return "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
}
