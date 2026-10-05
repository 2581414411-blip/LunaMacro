package com.nexus.agent.audit

/**
 * 一次 Agent 任务的完整审计日志。
 */
data class TaskAuditLog(
    val taskId: String,
    val userCommand: String,
    val gameName: String?,
    val startTime: Long,
    val endTime: Long?,
    val status: String,               // running / completed / failed / cancelled
    val plannedSteps: List<String>,
    val entries: List<AuditEntry>,
    val summary: String?
)

/**
 * 单步执行记录。
 */
data class AuditEntry(
    val timestamp: Long,
    val stepIndex: Int,
    val stepDescription: String,
    val actionType: String,           // click_text / click_id / tap / swipe / open_app / wait / screenshot ...
    val actionParams: Map<String, String>,
    val targetElement: String?,
    val coordinates: Pair<Float, Float>?,  // 归一化坐标 (x, y)
    val result: String,               // success / failed / skipped
    val resultDetail: String?,
    val screenshotPath: String?,
    val reasoning: String?,
    val screenType: String?,
    val durationMs: Long
)
