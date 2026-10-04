# 语音助手现状检查报告

> 本文档为只读检查结果，供外部审阅。检查范围：`speech/`、`wakeword/`、`overlay/ThinkingOverlay.kt`、`MainActivity.kt`（语音部分）、`agent/`（安全策略相关）。

---

## 一、文件清单

### speech/ 目录（4 个文件，共 999 行）

| 文件 | 行数 | 职责 |
|---|---|---|
| `VoiceAssistant.kt` | 418 | 语音助手状态机协调器（object 单例），串联「唤醒→录音→ASR→执行→TTS→续听」全链路 |
| `VoiceCommandRecorder.kt` | 276 | 录音 + 静音端点检测（自适应阈值、静音判定、超时保护） |
| `AsrClient.kt` | 194 | DashScope paraformer-realtime-v2 实时语音识别 WebSocket 客户端 |
| `TtsSpeaker.kt` | 111 | 封装 Android 自带 TextToSpeech，离线中文播报 |

### wakeword/ 目录（1 个文件）

| 文件 | 行数 | 职责 |
|---|---|---|
| `WakeWordDetector.kt` | 529 | ONNX 唤醒词检测（MFCC + 模型推理），唤醒词「你好高总」 |

### overlay/ 目录（1 个文件）

| 文件 | 行数 | 职责 |
|---|---|---|
| `ThinkingOverlay.kt` | 315 | 思考过程悬浮窗（语音状态、执行过程实时显示，含「停止」按钮） |

---

## 二、状态机现状

### 1. 当前语音流程的状态

状态机**没有显式枚举**，靠 `@Volatile` 布尔标志表示：

| 标志 | 含义 |
|---|---|
| `listening` | 是否在唤醒监听（KWS 运行中） |
| `busy` | 是否正在处理一次指令（录音/ASR/执行中） |
| `retryCount` | 识别失败重试计数（最多 2 次） |

### 2. 文字版状态图

```
┌────────────┐   唤醒词触发 handleWakeWord()   ┌────────────┐
│   待命     │ ───────────────────────────────→ │  录音中    │
│ listening  │  busy=true，停KWS，立即录音       │  busy=true │
└────────────┘                                   └─────┬──────┘
      ↑                                                │ 静音端点/超时
      │ restartWake()                                  │ onPcmReady()
      │ （busy=false，重启KWS）                         ▼
┌─────┴──────┐                                  ┌────────────┐
│ 续听/待命  │ ←── isContinuousEnabled()? ───── │  ASR识别   │
└────────────┘    （续听则 enterContinuousListening│  识别中    │
      ↑            否则 restartWake）             └─────┬──────┘
      │                                                │ onCommand()
      │                                                ▼
      │                                        ┌────────────┐
      │               reportExecuteResult()    │  执行中    │
      └────────────────────────────────────── │ busy仍true │
                TTS播报完延迟1500ms             └────────────┘
```

### 3. 核心类

`VoiceAssistant`（object 单例），位于 `app/src/main/java/com/nexus/agent/speech/VoiceAssistant.kt:29`。

### 4. 状态转换校验

**无显式状态机校验，属于「自由切换 + 单一 busy 防护」**。唯一的防护是 `handleWakeWord()` 里的 `if (busy) return`（`VoiceAssistant.kt:316-319`），防止处理中重复唤醒。另有 `cancel()`（`VoiceAssistant.kt:223-231`）支持外部打断。没有状态合法性断言、没有非法转换拦截。

---

## 三、唤醒流程现状

### 1. 唤醒词触发后的执行顺序

1. `WakeWordDetector` 推理命中 → `onWakeWord` 回调 → `VoiceAssistant.handleWakeWord()`（`VoiceAssistant.kt:315`）
2. `busy` 检查（已忙则忽略）→ `RunningIndicator.acquire()` 点亮运行灯
3. **停 KWS**（`wakeWordDetector?.stop()`，释放麦克风）
4. **立即启动录音**（`startRecording()`，零空窗）
5. **并行播放提示音**（`playWakeTone()`，不阻塞录音）
6. `postStatus("👂 正在听…")` 显示状态

### 2. 是否有跳转 MainActivity 逻辑

**无**。0.2.0 已删除「唤醒后跳转 MainActivity」的残留逻辑。当前唤醒后通过 `UiState.pendingTab.value = 1` 切换到「任务」Tab（`MainActivity.kt:109`，在 `onCommand` 回调内），不跳转 Activity。

### 3. 唤醒到开始录音的延迟

**零空窗**（约 0ms 量级）。`handleWakeWord()` 里 KWS 停止后立即 `startRecording()`，不做任何耗时操作、不等提示音、不等 delay（`VoiceAssistant.kt:328-329` 注释明确）。实际 KWS `stop()` 内部的 `job.join()` 有数毫秒的协程退出等待。

### 4. 是否播放提示音

**是**。`playWakeTone()`（`VoiceAssistant.kt:99-111`）用 Android `ToneGenerator` 播放 80ms 短音（`TONE_PROP_BEEP`），播完延迟 100ms 释放，不阻塞录音。

---

## 四、录音与端点检测现状

### 1. 录音类

`VoiceCommandRecorder`，位于 `app/src/main/java/com/nexus/agent/speech/VoiceCommandRecorder.kt`。

### 2. 采样率 / 声道 / 位深

| 参数 | 值 |
|---|---|
| 采样率 | 16000 Hz |
| 声道 | 单声道（CHANNEL_IN_MONO） |
| 位深 | PCM 16bit（ENCODING_PCM_16BIT） |

### 3. 静音阈值

**自适应**（非固定值）。录音前 500ms 采集环境噪声，取 RMS 中位数 `noiseFloor`，动态阈值 = `noiseFloor × 2.5`，并 clamp 到 `[300, 2000]`（RMS 值）。代码位置 `VoiceCommandRecorder.kt:44-48, 173-187`。

### 4. 静音判定时长

连续 **60 帧 × 20ms = 1.2 秒**静音判定「说完」（`SILENCE_END_FRAMES = 60`，`VoiceCommandRecorder.kt:57`）。

### 5. 最大录音时长

**30000ms（30 秒）** 兜底超时（`DEFAULT_MAX_DURATION_MS = 30000L`，`VoiceCommandRecorder.kt:60`）。0.4.0 从 6 秒放宽到 30 秒以支持长指令。

### 6. 录音超时（一直不说话）处理

采样期结束后 **3 秒**内无说话 → 判定 `REASON_NO_SPEECH` 退出（`NO_SPEECH_TIMEOUT_MS = 3000L`，`VoiceCommandRecorder.kt:51`）。上层 `onPcmReady` 收到 no_speech 直接回待命，不播报、不打扰（`VoiceAssistant.kt:348-352`）。

### 7. 录音结束后是否释放 AudioRecord

**是**。录音循环 `finally` 块调用 `releaseRecord()`，内部 `audioRecord.stop()` + `release()` + 置空（`VoiceCommandRecorder.kt:230-235, 248-256`）。

---

## 五、ASR 与 TTS 现状

### 1. ASR provider 与模型

| 项 | 值 |
|---|---|
| Provider | 阿里云 DashScope |
| 模型 | `paraformer-realtime-v2` |
| 协议 | WebSocket 实时识别（`wss://dashscope.aliyuncs.com/api-ws/v1/inference/`） |
| 认证 | 复用 `qwen` provider 的 API Key（`LlmClientFactory.getConfig(ctx, "qwen").first`，`VoiceAssistant.kt:365`） |

### 2. ASR 返回后的文本合理性检查

| 检查 | 处理 |
|---|---|
| 识别失败 / 空文本 | 重试，最多 2 次（`handleRecognizeRetry`） |
| 文本 < 2 字 | 重试，最多 2 次 |
| 文本长度上限 | **无上限**（0.4.0 移除 50 字限制，长指令直接下发） |

### 3. TTS 用系统还是云端

**系统 TTS**（Android 自带 `TextToSpeech`，`TtsSpeaker.kt:36`），离线、免费。中文（`Locale.CHINESE`），无中文引擎则回退英文。

### 4. TTS 播报与 KWS 是否互斥

**互斥**。实现：播报前 `wakeWordDetector?.pause()`（保留模型、仅释放麦克风），播报完成后延迟 1500ms 再 `restartWake()`（其中恢复 KWS）。代码位置 `VoiceAssistant.kt:189-191, 205-213`。pause/resume 见 `WakeWordDetector.kt:169-199`。

---

## 六、多轮续听与上下文现状

### 1. 是否有续听模式

**有**。`enterContinuousListening()`（`VoiceAssistant.kt:281-288`），续听时长默认 **5000ms**（`voice_continuous_duration_ms`，`VoiceAssistant.kt:263-265`），可通过开关 `voice_continuous_enabled`（默认 true）控制。

### 2. 续听模式下是否需要重新喊唤醒词

**不需要**。执行完直接 `startRecording()`，检测到说话就进入识别流程；5 秒无说话由录音器 no_speech 回调退回待命。

### 3. 是否有 session 概念

**无**。没有会话标识，也没有同一会话的指令历史记录。每句指令独立处理。

### 4. 指令历史是否传给 LLM

**否**。`onCommand` 直接收到纯文本指令，原样丢给 `AgentRunner.runTask()`（`MainActivity.kt:107-119`），无任何历史上下文拼接。

### 5. 结论

**当前为「无上下文的连续对话」模式**：支持执行完免唤醒继续说下一句，但不支持跨句指代（如「再打开一个」「把刚才那个关掉」）。

---

## 七、防误判现状

### 1. KWS 层

| 项 | 值 | 说明 |
|---|---|---|
| 唤醒阈值 | `0.9f` | softmax 后类别 1（唤醒词）概率超过 0.9 触发（`WakeWordDetector.kt:68`） |
| 连续帧确认 | **无** | 单个 1000ms 窗口推理命中即触发，无「连续 N 窗命中」确认 |
| 冷却时间 | `2000ms` | 一次唤醒后 2 秒内忽略重复触发（`COOLDOWN_MS = 2000L`，`WakeWordDetector.kt:56, 449-455`） |
| 能量跳变检测 | **无** | 纯 MFCC + 模型推理，无额外的能量/频谱跳变前置判断 |

### 2. ASR 层

文本合理性检查：太短（<2 字）重试、空/失败重试（最多 2 次）。**无**置信度检查、无乱码/非中文内容检测。

### 3. 执行层 SafetyPolicy

| 项 | 状态 |
|---|---|
| SafetyPolicy 类 | ✅ 存在（`safety/SafetyPolicy.kt`） |
| `checkTaskText` | ✅ 已实现（`SafetyPolicy.kt:50-59`） |
| `checkToolCall` | ✅ 已实现（`SafetyPolicy.kt:67-77`） |
| 在 Executor 中调用 | ✅ `checkToolCall` 在 `Executor.executeTool` 开头调用（`Executor.kt:339-340`） |
| 在 AgentRunner 中调用 | ✅ `checkTaskText` 在 `AgentRunner.runTask` 开头调用（`AgentRunner.kt:84-85`） |

### 4. 危险应用黑名单

**已配置**，但为**应用名关键词**（非包名）。`DANGEROUS_APP_KEYWORDS`（`SafetyPolicy.kt:25-28`）包含：

`银行、支付、云闪付、证券、股票、基金、理财、保险、贷款、金融、借贷、钱包、支付宝、财付通、同花顺、东方财富`

匹配方式：`open_app` 的 `appName` 参数「包含」任一关键词即拦截。

---

## 八、问题与建议

### 1. 发现的问题（按严重程度）

**P1（重要，影响核心体验）**

| # | 问题 | 位置 |
|---|---|---|
| P1-1 | **多轮上下文缺失**：无 session、无历史、无跨句指代理解，续听只是"无脑继续说" | `MainActivity.kt:107-119`、`VoiceAssistant.kt:281-288` |
| P1-2 | **拦截时无 TTS 播报 + 无 blocked_reason 日志格式**：文档《软件大纲》5.1 第 4 条承诺了「悬浮窗 3 秒 + TTS 播报 + 日志 blocked_reason=xxx」，但代码仅实现悬浮窗 + `Log.w("拦截任务文本：xxx")` | `SafetyPolicy.kt:54, 73` |
| P1-3 | **ASR 强耦合 qwen Key**：语音识别复用 qwen provider 的 API Key，用户没配 qwen Key 则语音识别整体不可用（与通用 LLM 用 deepseek 默认不一致） | `VoiceAssistant.kt:365-370` |

**P2（次要，优化）**

| # | 问题 | 位置 |
|---|---|---|
| P2-1 | KWS 无能量跳变检测、无连续帧确认，理论上有非语音误触发风险（阈值 0.9 + 冷却 2s 已缓解） | `WakeWordDetector.kt` |
| P2-2 | 状态机用布尔标志（listening/busy）而非显式枚举，无状态转换校验，扩展性一般 | `VoiceAssistant.kt:53-57` |
| P2-3 | 指令长度无上限（0.4.0 改动），超长指令可能触发 30s 执行超时兜底，且长语音 ASR 识别质量下降 | `VoiceAssistant.kt:384, 394-401` |
| P2-4 | TtsSpeaker 的 onDone 用全局单一回调，并发播报时理论上回调可能错乱（当前语音流程串行，实际风险低） | `TtsSpeaker.kt:85-94` |

### 2. 修复建议

- **P1-1**：新增 `ConversationContext`（最近 N 轮指令+结果），语音指令在 `onCommand` 前先经一次 LLM「指令澄清」（结合历史把指代改写成明确指令），执行结果回填历史。仅当指令含指代词（再/刚才/那个/它/接着/也）时触发澄清，避免额外延迟。
- **P1-2**：在 `SafetyPolicy` 拦截命中处，补 TTS 播报（`VoiceAssistant` 或 `TtsSpeaker`）+ 结构化日志 `blocked_reason=xxx, target_app=xxx, task_text=xxx`。
- **P1-3**：为 ASR 增加独立 provider 配置，或明确「语音识别需要 qwen Key」的引导（部署向导已含注册跳转）。
- **P2-1**：可加能量门限前置判断（RMS 低于某值直接跳过推理，省电 + 减误触发）。
- **P2-2**：可选将状态机重构为 `enum State { IDLE, LISTENING, RECOGNIZING, EXECUTING, SPEAKING }`，带转换校验。
- **P2-3**：可恢复一个宽松的上限（如 200 字），或对超长指令做摘要截断。
- **P2-4**：TtsSpeaker 的完成回调改为按 utteranceId 映射，支持多并发。

### 3. 与目标状态（待机→唤醒→指令→执行→续听→待机）的差距

| 环节 | 状态 |
|---|---|
| 待机 → 唤醒 | ✅ 已实现 |
| 唤醒 → 指令（录音+ASR） | ✅ 已实现 |
| 指令 → 执行 | ✅ 已实现 |
| 执行 → 续听 | ✅ 已实现（续听模式） |
| 续听 → 待机 | ✅ 已实现（超时回待命） |

**主要差距**：主流程五环节齐全，但「指令」环节**无上下文理解**——续听模式下能继续说，却不记得上一句说了什么。这是 0.5.1「语音助手逻辑升级」的核心待补项（对应 P1-1）。

---

## 附：检查信息

- **检查时间**：2026-10-04 13:07（GMT+8）
- **versionCode / versionName**：8 / 0.5.0-beta
- **最后 git commit**：`3de29ef` — `fix: 注册跳转加 NEW_TASK + 失败 Toast 提示`
