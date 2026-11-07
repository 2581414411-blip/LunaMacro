# 项目状态同步快照 · PROJECT_STATUS_LATEST

> 生成时间：2026-11-07 14:50（GMT+8）
> 扫描方式：**只读**。代码未做任何修改，仅执行 `git log` / `gradlew compileDebugKotlin` / `gradlew testDebugUnitTest` 与静态检索。
> 项目根：`NexusAgent`（applicationId `com.nexus.agent`）

---

## 一、版本与基线

### 1.1 当前版本

`app/build.gradle.kts` → `defaultConfig`：

| 字段 | 值 |
|---|---|
| applicationId | `com.nexus.agent` |
| versionCode | **101** |
| versionName | **0.9.3** |
| minSdk / targetSdk | 24 / 36 |
| abiFilters | 仅 `arm64-v8a` |

### 1.2 最近 5 条提交

```
4a94830 chore: 发版 0.9.3（版本号 101、更新说明与公告）
8b89f97 feat: 心跳提速到每小时4次机会，主动消息入档并直达聊天页
5aa6f8e chore(release): 0.9.2 发版元数据
72559e6 feat: 注入灵魂！激活好感度驱动、主动问候与临场感
150c22b feat: 临场感注入，她知道现在几点、你手机还剩多少电
```

工作区 `git status` 为空（干净），当前 HEAD = `4a94830`，远端 tag `v0.9.3` 指向同一提交。

### 1.3 编译与单测

实际执行（非引用历史结论）：

```
$ gradle :app:compileDebugKotlin --offline
BUILD SUCCESSFUL in 19s
16 actionable tasks: 5 executed, 11 up-to-date

$ gradle :app:testDebugUnitTest --offline
BUILD SUCCESSFUL in 19s
```

单测结果（解析 `app/build/test-results/`）：**tests = 306，failures = 0，errors = 0，skipped = 0**。

结论：**编译通过，单测 306 条全绿。**

---

## 二、核心痛点修复确认

### 2.1 感知轻量化（屏幕读取）—— 已实装，闲聊不再强制截图

**文件**：`app/src/main/java/com/nexus/agent/copilot/ScreenContextProvider.kt`（三态模型）

| 判定 | 条件 | 行为 |
|---|---|---|
| `ScreenContext.Text` | 有效文字节点 **> MIN_TEXT_NODES（3）** | 文字直接进 Prompt，**不截图、不调用视觉模型** |
| `ScreenContext.NeedVision` | 文字节点 ≤ 3（游戏 / 视频 / Canvas 自绘界面） | 才回头要图 → 走 `:vision` 子进程本地视觉 |
| `ScreenContext.Unavailable` | 无障碍未连接 / 拿不到窗口 / 前台是宏月自己 | 两者都不做，仅在用户问「你看到什么」时解释一句 |

**调用链**：`PersonaEngine.respond()` 第 645–681 行

```kotlin
val sensing = if (allowScreenRead) ScreenContextProvider.getScreenContext() else null
when (sensing) {
    is ScreenContext.Text -> screenNote = sensing.toPromptNote()   // 不截图
    is ScreenContext.NeedVision -> needVision = true                // 才截图
    is ScreenContext.Unavailable -> ...
}
```

**「聊天时还会强制截图吗？」——不会（人格聊天路径）。**

- `CopilotEngine` 的截图入口已改成惰性 `ensureShot()`（第 257 行），且第 277 行明确写了：`if (!sensitive && !chatty) ensureShot()` —— **人格模式（`chatty = true`）不预先截图**，只有感知层判定 `NeedVision` 才回头调用 `ScreenCapture.captureBitmap()`，且自己截的图在 `finally` 里回收（`ownedShot`）。
- 老路径（非人格模式、工具执行）仍然会截图，这是设计如此（`agent/Executor.kt`、`audit/AuditLogger.kt`）。
- 可观测性：日志 tag `ScreenContext` / `PersonaVision` 会打印 `模式: Text|Vision|None`、文字长度、节点数，排障时能直接看出这一轮有没有看图。

### 2.2 API 诊断 —— DeepSeek 余额查询已实现，401/402 有明确提示

**余额查询**：`app/src/main/java/com/nexus/agent/agent/ApiDiagnostics.kt`

- DeepSeek：`GET {origin}/user/balance`（第 269 行 `balanceUrl()`）。
  ⚠️ 关键细节：余额接口挂在**域名根**上，不在 `/v1` 下面，代码用 `originOf(baseUrl)` 剥路径并保留端口，拼错就会 404。
- 其他 OpenAI 兼容服务：走 `GET /models` 验证 Key + 可达性；再兜底一次 `max_tokens=1` 的极简对话。
- 返回结构化 `Report`，`formatBalance()` 用项目自带的 MiniJson 解析（不能用 `org.json`，纯 JVM 单测里是空壳）。

**状态码分类**（`Kind`）：`OK / AUTH / QUOTA / RATE_LIMIT / BAD_URL / SERVER / TIMEOUT / NETWORK / NO_KEY / UNKNOWN`

- `401 / 403 → AUTH`
- `402 → QUOTA`
- `429 → RATE_LIMIT`
- `400 + body 含 insufficient/balance/余额 → QUOTA`（DeepSeek 欠费时会给 400）

**聊天时遇到 401/402 的提示**：`copilot/ApiErrorState.kt` → `notice()`，原文如下：

| 类型 | 聊天里出现的系统气泡 |
|---|---|
| AUTH (401) | `（系统提示：云端 API Key 无效或已失效，请前往设置 → LLM 配置检查。）` |
| QUOTA (402/欠费) | `（系统提示：云端 API 额度耗尽或 Key 失效，请前往设置检查。）` |
| RATE_LIMIT (429) | `（系统提示：云端接口被限流了（HTTP 429），等半分钟再说。）` |
| TIMEOUT | `（系统提示：云端请求超时，网络太慢或代理挂了，请检查网络后重试。）` |
| NETWORK | `（系统提示：连不上云端（网络或代理问题），请检查网络后重试。）` |

**传递路径**（这条链路以前是断的）：
`askRoleLlm` catch → `ApiErrorState.record()` → `PersonaEngine.respond` 拿到空回复后 `takeRecent()` → 直接 `return ApiErrorState.notice(failure)`（第 856–860 行）→ 气泡。前缀「（系统提示：…）」是刻意的，避免用户以为角色本人在抱怨余额。

### 2.3 模型连接问题 —— 已定位到具体层，不再「笼统报错」

排查与修复进度：

| 层 | 现状 |
|---|---|
| 路由 | `LlmClientFactory.create()` 打 `routing()` 日志；`hasUsableProvider()` 可判空；`sanitizeBaseUrl` 回退时留 WARN |
| Key 读写一致性 | 统一走 `${provider}_api_key`（第 87 / 161 行），历史坑「设置页写 `qwen_api_key`、聊天读 `qwen-vl_api_key`」已消除 |
| 本地 HTTP 白名单 | `res/xml/network_security_config.xml` 放行 `127.0.0.1 / localhost / 10.0.2.2 / 192.168.31.46 / 192.168.191.170`（`<domain>` 不支持 CIDR，换网要改真实 IP） |
| 失败归因 | 异常被 `LocalDigitalBeing.chat` 吞掉的问题，改用「现场记录 + 事后回查」绕开；`ApiErrorState` 只在**首个**失败上记账（第一个通常才是真因） |
| 剩余不确定项 | 没有真机日志就无法继续收敛的部分：具体某个 provider 的超时阈值是否偏短（`REPLY_TIMEOUT_MS`）、代理挂掉时的重试次数。需要 `adb logcat -s ApiErrorState PersonaVision LlmClientFactory` 实机验证。 |

**结论**：从「不知道哪一步坏了」推进到「能明确说出是 Key 错、没钱、限流、断网、地址错、服务端故障中的哪一种」。**但没有真机日志，无法宣称 100% 修完。**

---

## 三、灵魂与养成状态（重点核对）

### 3.1 好感度 / 情绪 —— 已真正接入业务链路

**结论：不再是死数据。** 调用链完整闭合：

```
用户发一句话
  → PersonaEngine.recordTurn()               (第 1479 行)
      → EmotionEngine.onTurn(appCtx, u)
          → PersonaEngine.bumpAffinity(whole)     (EmotionEngine:172)
          → PersonaEngine.updateMood(mood)        (EmotionEngine:175)
              → 落库 store.saveProfile()
              → notifyChanged()   ← 关键：不补这句，卡片数字永远不动
```

- 只有**真正回完一句**才计分：模型没接上、被拦截、报错这些轮次不扣她的心情。
- 规则：夸奖 `+1`/开心、抱怨 `-1`/难过、普通 `+0.2`/平静；负向惩罚是正向的 5 倍。
- `affinity` 是 Int 字段，`+0.2` 用「小数余数存 prefs、攒满进位」实现，**不改表结构**。
- 软例外：`「滚去睡」「滚去吃饭」` 这类关心话不扣分。
- 语气强绑定：`EmotionEngine.toneBlock(affinity, mood)` 进 System Prompt，`< 30` 冷淡且极短、`30~79` 正常、`>= 80` 亲昵。

### 3.2 主动心跳 —— 已实装，且刚把频率拉到 15 分钟

**文件**：`persona/HeartbeatScheduler.kt` + `HeartbeatWorker`（WorkManager）

| 参数 | 当前值 |
|---|---|
| `CHECK_INTERVAL_MINUTES` | **15 分钟**（WorkManager 周期下限） |
| `IDLE_MINUTES` | **15 分钟**（多久没说话算「没理我」） |
| `MIN_GAP_MINUTES` | **15 分钟**（两条主动消息最小间隔） |
| `WAKE_HOUR` / `SLEEP_HOUR` | 9 / 22（夜里不发） |
| 每日上限 | **无** |
| 失败退避 | 30 分钟（`Verdict.BACKOFF`） |

注册点：`NexusApplication.onCreate()` 第 119 行 `HeartbeatScheduler.schedule(this)`。

发送后的完整动作（0.9.3 新增）：

1. `CopilotConfig.bumpUnread()` —— 未读 +1；
2. `deliverToChat()` → `PersonaEngine.addAssistantItem()`（进聊天流 + 落库）+ `PersonaEngine.rememberProactiveLine()`（进对话记忆窗口，`LocalDigitalBeing.recordProactive`，`Turn.user` 留空串标记「这轮没有用户发言」）；
3. `notifyGreeting()` —— 通知点击带 `MainActivity.EXTRA_OPEN_PERSONA`，直达「对话」页；
4. `markHeartbeatNow()`。

判定是纯函数 `decide()`，六态 + 退避：`SEND / DISABLED / NIGHT / NEVER_CHATTED / NOT_IDLE / COOLDOWN / BACKOFF`，单测逐条钉死。

### 3.3 临场感 —— 时间与电量已进 Prompt

- `copilot/PresenceSense.kt`：`Now(hour, minute, batteryPercent, charging)` + `describe / presenceBlock / greetingTimeHint`。
- 注入点：`PersonaEngine.buildReplyPrompt(..., presence: PresenceSense.Now? = null)` 第 1149 / 1160 行，**参数可注入**（单测传固定值，否则「深夜」断言会随真实时间随机红）。
- 规则：深夜（`>= 23` 或 `< 5`）才提困意早睡；`<= 20%` 提醒充电，`<= 9%` 才说「个位数」；**充电时不催电量**；白天满电不加任何环境约束。
- 主动问候走独立的 `buildGreetingPrompt(now)`（第 1534 行），只放人设 + 心情 + 记忆 + 时间，不塞屏幕/工具段。

### 3.4 羁绊面板 —— 已是可视化卡片

`PersonaTab.kt` 第 172 行 `BondCard(...)`，取代原来那行「宏月 / 已记住最近 N 轮」。

卡片包含：

- **关系等级**：`BondState` 五档 `Lv.1 陌生 → Lv.2 熟人 → Lv.3 朋友 → Lv.4 知己 → Lv.5 羁绊`；
- **心情**：大号 Emoji + 文字（开心 / 平静 / 难过）；
- **进度条**：自绘 `BondBar`（不用 Material3 的 `LinearProgressIndicator`，那个有固定最小高度和两端留白，塞进卡片对不齐）+ `xx / 100`；
- **一行状态**：「已记住最近 N 轮 · 长期记忆 M 条」；
- **心跳开关**：「她会主动找你」+ 「现在试」（走 `force` 分支，跳过所有闸门）。

---

## 四、隐患与待办

### 4.1 扫描发现的问题

#### 🔴 P0 —— 升级到 0.9.3 的用户，心跳可能仍然是 6 小时一次

`HeartbeatScheduler.schedule()` 第 180 行：

```kotlin
.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
```

`WORK_NAME` 仍是 `"luna_heartbeat"`（0.9.2 就在用）。`KEEP` 的语义是**「已有同名未完成任务就什么都不做」**——WorkManager 的任务存在 app 私有数据库里，**覆盖安装 APK 不会清数据**。

所以：0.9.2 已经排过「6 小时周期」的任务，升级到 0.9.3 后这次 `KEEP` 会直接忽略新的 15 分钟请求，实际仍是 6 小时。而第 181 行日志照样打印「每 15 分钟检查一次」——**日志会骗人，排查时极易误判。**

修复方向（三选一，均一行级改动）：

1. 改 `ExistingPeriodicWorkPolicy.UPDATE`（WorkManager 2.8+ 支持，当前依赖 2.10.0 可用）；
2. `WORK_NAME` 改成 `"luna_heartbeat_v2"`（最稳，旧任务自然残留但不同名不冲突，可顺手 cancel 旧名）；
3. 先 `cancelUniqueWork(WORK_NAME)` 再 `enqueue`。

已验证：受影响的是**从 0.9.2 升级上来的用户**；全新安装不受影响。

#### 🟠 P1 —— 15 分钟心跳的调用成本与耗电

理论最大频率：白天 13 小时 × 4 次 = **约 52 次模型调用/天**。每次都要真发一次 LLM 请求（`proactiveGreeting` 必然调 `pickReplyProvider` + `client.chat`）。

- 若用户用的是按量付费的云端 Key，这是一笔持续支出；
- 若长时间不看手机，`IDLE_MINUTES=15` 会一直成立，她会一路发下去（无每日上限，是用户明确要求的）；
- 已有缓解：失败退避 30 分钟（`BACKOFF`）；夜里 9 点前 22 点后不发。

建议观察项：真机盯一天的电量曲线与 API 账单，若偏高就加「每日总条数上限」或「连续 N 条无人回应后自动拉长间隔」（后者更像人）。

#### 🟠 P1 —— `fallbackToDestructiveMigration()` 仍是最后兜底

`data/db/AppDatabase.kt` 第 79 行。当前 `version = 4`，已有 `MIGRATION_1_2 / 2_3 / 3_4` 显式迁移，正常路径不会触发。

风险：**以后加字段若漏写迁移，Room 会静默清库**，用户聊天记录、好感度、长期记忆全部归零，而且不会报错给用户看。这是「数据无价」类隐患，建议在 CI 或 pre-commit 加一条「版本号 +1 必须同时出现 Migration」的检查。

#### 🟡 P2 —— 多条主动消息只留最后一条通知

`NOTIFY_ID` 固定为 `0x4C55`，`nm.notify(NOTIFY_ID, n)` 会互相覆盖。用户离开手机两小时回来，通知栏只有一条，但聊天里可能有七八条。

建议：改成 `NOTIFY_ID + unread` 或按时间戳派生 id，让每条通知独立存在；或者干脆改成 `MessagingStyle` 累积成一段对话（更像微信）。

#### 🟡 P2 —— `ApiErrorState` 是全局单例，多 provider 并发可能串

`@Volatile private var last: Failure?`，`record()` 里「已有记录就不覆盖」。单轮对话只有一个 provider 时没问题；但语音、定时任务、心跳、聊天同时打不同 provider 时，可能出现「A 的错误被当成 B 的」。影响是提示文案可能指错 provider，不会崩溃。

#### 🟡 P2 —— 心跳依赖 WorkManager，国产 ROM 清后台后可能长时间不跑

WorkManager 在部分 ROM（vivo / OPPO / 小米的激进省电）里会被延后很久甚至不执行。App 已有前台服务（`specialUse|microphone`）保活，但心跳没有绑在前台服务上。

表现会是「她今天一条都没说」。可考虑：在前台服务存活时用 `Handler` 做一次轻量补充检查，或提示用户把 App 加入电池白名单。

#### 🟡 P2 —— `PersonaEngine.onChanged` 仍是单槽

0.9.3 已新增 `addChangeListener / removeChangeListener` 多播来绕开（首页红点用多播，聊天页继续用单槽）。功能正常，但两套机制并存属于设计债：以后新增第三个订阅方时容易踩「谁把 onChanged 置 null 了」的坑。

### 4.2 「让 AI 真正活起来」——最欠缺的一环

**不是功能不够，是「她开口没有理由」。**

现在的主动消息触发条件是纯时间的：`15 分钟没说话 + 15 分钟冷却 = 可以开口`。至于「说什么」，是每轮临时从长期记忆 / 周记 / 摘要里捞一条。这导致两个一眼能看穿的破绽：

1. **同一件事被反复问**——你上次提的报销，她今天问、明天问、后天还问。因为代码里没有「这件事我问过了」的记账；
2. **开口时机与事情无关**——她不知道你今天下午三点有个会，也不知道你昨天说头疼，所以关心永远是泛泛的。

记忆层其实**已经有料**（`long_term_memory` 事实、FlexiMem 周记、滚动摘要），缺的是把这些料变成**有状态的话题种子**：

> 话题种子 = { 内容, 来源, 首次提及时间, 是否已问过, 下次可问时间, 过期时间 }

有了它，`proactiveGreeting` 就不再是「一次随机取款」，而是「到期提醒」：你昨天说头疼 → 今天中午问一句「头还疼吗」 → 标记已问、两天内不再提。**这比再加十个 UI 组件更能让人觉得「她记得」。**

第二欠缺的是**事件感知**：她现在只能「读屏幕」和「等你说话」，接触不到通知栏（外卖到了、微信消息）、日历（下午三点开会）、未接来电。这也是为什么她的关心总差半拍。

如果要排优先级，我的建议是：

| 优先级 | 事项 | 理由 |
|---|---|---|
| 1 | 修 P0（`KEEP` → `UPDATE`） | 否则这次「每小时四次」的改动对老用户根本不生效 |
| 2 | 话题种子状态机（含「已问过」标记） | 从「随机关心」升级为「记得关心」，是活的质感拐点 |
| 3 | 多条通知不覆盖（`MessagingStyle`） | 成本和收益比最高的一次体验提升 |
| 4 | 通知栏监听（`NotificationListenerService`） | 让她知道你刚收到外卖 / 有人找你，但要过隐私关 |
| 5 | 每日上限 / 无人回应自适应 | 防长期使用的边际烦躁 |
