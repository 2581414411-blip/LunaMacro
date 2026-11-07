# 0.9.9 项目状态同步快照

> 生成时间：2026-11-07 23:40　|　扫描方式：只读（未修改任何业务代码）
> 基线提交：`7434219`（master）　|　tag：`v0.9.9`
> 本文所有结论均附「文件:行号」证据，可逐条复核。

---

## 一、版本与基线

### 1. 构建版本

来源：`app/build.gradle.kts`

| 字段 | 值 | 行号 |
|---|---|---|
| `applicationId` | `com.nexus.agent` | 22 |
| `versionCode` | **107** | 25 |
| `versionName` | **0.9.9** | 26 |

`VERSION_RULES.md` 记录的下一次打新包：versionCode 108 / versionName 0.9.10（patch 顺延）。

### 2. 最近 8 条提交

```
7434219 chore: 更新配置与公告切到 0.9.9
7480a00 fix: 多模态全链路排障日志与UI兜底报错
ca54ca7 feat: 路由层失败分类——打印 HTTP 状态码/返回体/耗时，401与超时大声喊
fbbfcb5 feat: 发图链路第一站（UI 层）全量日志，修 isBusy 早退卡死
7d48e37 chore: 升版本 0.9.8（versionCode 106）
2901465 feat: 设置页新增独立云端视觉配置（模型下拉 + Key + Base URL）
cd0ff68 feat: 重构视觉路由——视觉提取与文本推理彻底分离
6909b73 chore: 升版本 0.9.7（versionCode 105）
```

**0.9.8 → 0.9.9 之间到底改了什么**：一句话概括是「0.9.8 换了路由，0.9.9 只加可观测性和兜底，没动路由架构」。

| 提交 | 性质 | 实质改动 |
|---|---|---|
| `7d48e37` | 收尾 | 0.9.8 升版本 |
| `fbbfcb5` | 观测 | 发图链路 UI 层四个入口（相册图 / 相册视频 / 拍照 / 直接位图）全打 `VisionDebug`；**顺手修了 `isBusy` 早退不回调 `onDone` 的卡死坑** |
| `ca54ca7` | 观测 + 修正 | 云端视觉异常不再被 `runCatching{}.getOrNull()` 吞成「超时」；引入 `FailKind` 分类；401/403 单独喊；打印 HTTP 状态码 + 返回体 + 耗时 |
| `7480a00` | 交付 | Prompt 分段日志、`ChatItem.SystemNotice` + 红字气泡、版本升到 0.9.9 |
| `7434219` | 配置 | `update.json` / `announcement.json` 切 0.9.9 |

⚠️ 注意：0.9.9 没有改变「怎么识别图片」的逻辑，只让它**可查、失败可见**。所以如果 0.9.8 的视觉识别率问题仍存在，0.9.9 只是把原因暴露出来，不会自动修好。

### 3. 编译与单测（实测）

```
$ gradle :app:compileDebugKotlin --offline -q
BUILD SUCCESSFUL（仅一条 SDK XML 版本告警，不影响构建）

$ gradle :app:testDebugUnitTest --offline
BUILD SUCCESSFUL in 7s
```

单测结果解析（`app/build/test-results/testDebugUnitTest/*.xml` 聚合）：

| 指标 | 数值 |
|---|---|
| 测试类文件 | 30 |
| 用例总数 | **395** |
| 失败 / 错误 | **0** |
| 跳过 | 0 |

覆盖到的关键测试类：`VisionRouterTest`、`VisionRouteTest`、`DanmakuGuardTest`、`EmotionEngineTest`、`HeartbeatSchedulerTest`、`InnerMonologueTest`、`LocalDigitalBeingTest`、`MemoryExtractorTest`、`PersonaPromptTest`、`BondStateTest`。

> 说明：单测全绿只代表纯函数与纯 JVM 逻辑正确，**不覆盖真机上的发图链路**。发图仍需按 `adb logcat -s VisionDebug` 实测。

---

## 二、核心痛点修复确认

### 1. 多模态看图：现在到底怎么处理？

**结论：既不直接塞给主模型，也不只有一条腿——是「云端视觉优先 → 本地 2B 兜底 → 明确失败」的三级级联。主模型全程只收文字。**

核心实现：`copilot/VisionRouter.kt:205` `processImage()`。

#### 1.1 路由判定

```kotlin
// VisionRouter.kt:214-218
val cloudKey      = LlmClientFactory.getVisionApiKey(context)
val mainProvider  = LlmClientFactory.getProvider(context)
val mainConfig    = LlmClientFactory.getConfig(context, mainProvider)
val mainCanVision = LlmClientFactory.supportsVision(mainProvider, mainConfig.third)
val useCloud      = shouldUseCloud(cloudKey, mainCanVision, mainConfig.first)

// VisionRouter.kt:157-161
internal fun shouldUseCloud(visionApiKey, mainCanVision, mainApiKey): Boolean =
    visionApiKey.isNotBlank() || (mainCanVision && mainApiKey.isNotBlank())
```

即：**配了独立视觉 Key 就走云端视觉；没配但主模型本身是多模态且有 Key，也走云端视觉；两者都不满足就跳过云端，直接本地。**

#### 1.2 三级流水线

| 优先级 | 走什么 | 代码位置 | 关键约束 |
|---|---|---|---|
| ① 云端视觉 | `describeCloud()` → 默认 `qwen-vl-max` + dashscope 兼容地址 | `VisionRouter.kt:412` | 25 秒硬超时（`CLOUD_TIMEOUT_MS`）；打 HTTP 状态码 + 返回体前 300 字 + 耗时 |
| ② 本地视觉 | `LocalScreenRecognizer` / `VisionHub.look()`，Qwen2-VL-2B 跑在 `:vision` 子进程 | `VisionRouter.kt:512` | 缺模型文件直接短路（`missing.isNotEmpty()` 即放弃，不起子进程白等） |
| ③ 失败兜底 | 返回 `Route.FAILED` + `failNotice` + `userHint` | `VisionRouter.kt:320-380` | 不返回空描述，绝不假装看到 |

#### 1.3 主模型收到的是什么

`PersonaEngine.respond` 里图片分支（`PersonaEngine.kt:912-968`）：

```kotlin
// PersonaEngine.kt:968
b64 = null   // 图片已变成文字，主模型（DeepSeek 等纯文本）收到的只有描述
```

拼装结果固定为：

```
用户说：你看看这张图。 [图片内容描述：一只橘猫躺在沙发上]
```

标头常量：`PersonaEngine.kt:1451` `IMAGE_NOTE_HEADER = "[图片内容描述："`。
提示词段标题：`PersonaEngine.kt:1635` 【他刚给你发来的{图片/视频/照片}——你眼里的画面】。

**致命防范已落地**：`b64` 在发图路径恒为 `null`（另见 `:1309`、`:1341` 两处兜底）。日志里有一行专门自证：`VisionRouter.kt:235-239`「base64 不会进 messages（图片只发给视觉模型）」。

#### 1.4 「配了 DeepSeek 纯文本，能不能识别并回复？」

| 场景 | 结果 |
|---|---|
| 配了独立视觉 Key（`vision_api_key`） | ✅ 云端视觉转文字 → DeepSeek 正常回复，日志 `route=Cloud_VL` |
| 没配视觉 Key，但本地视觉 6 个模型文件齐全 | ✅ 本地 2B 转文字 → DeepSeek 正常回复，日志 `route=Local_VL` |
| 没配视觉 Key，本地模型也缺 | ❌ 不回复图片内容，但**当场红字**：`⚠️ 用户发了一张图片，但视觉解析失败（原因…），请前往 设置 检查` |
| 视觉 Key 填错（401/403） | ❌ 同上红字，原因明确写「云端视觉 Key 无效（401）」，不降级装傻 |

也就是说：**在关键路径上「能不能看懂」取决于配置，但「看不清时必须说出来」这条现在是硬保证的。**

#### 1.5 一个需要知道的代价（P2）

因为 `b64` 恒为 `null`，即使主模型本身就是多模态（例如把 `qwen-vl-max` 设成主模型），发图也会走「云端视觉提取 → 再拿文字做文本推理」两次调用，而不是一次多模态直传。这是用一次额外往返换取「路由确定性」的取舍，当前没有「主模型可直接看图时跳过提取」的短路。

#### 1.6 全链路日志（排查入口）

统一 TAG：`VisionRouter.DEBUG_TAG = "VisionDebug"`

```
UI层(选图/解码/落盘) → Engine(收图/转交) → 路由层(主模型/canVision/Key/本地模型)
→ 提取(云 HTTP 码+返回体 / 本地 look 全字段) → Prompt(最终 system 分段原文) → UI 红字
```

- 断点检索关键字：`[断点]`
- 成功标 `✓`，失败标 `✗`
- 失败分类 `VisionRouter.FailKind`：`no_bitmap / no_vision / cloud_auth / cloud_timeout / cloud_network / cloud_empty / cloud_encode / local_missing / local_failed`

---

### 2. 灵魂归一（UnifiedBrain）：统一大脑存在吗？

**结论：不存在。全仓库没有任何名为 `UnifiedBrain` 的类，也没有任何文档提出过这个命名的规划。**

验证方式：

```bash
grep -rn "UnifiedBrain" app/src/main/java/ --include=*.kt        # 无结果
grep -rn "UnifiedBrain\|统一大脑\|灵魂归一" --include=*.md .      # 无结果
```

`DEV_ROADMAP.md` 里只有「云端规划 / 本地识别 / 规则执行」三层游戏 Agent 架构，没有会话中枢的设计。

#### 2.1 现在事实上的「中枢」是两个，不是一个

| 组件 | 职责 | 位置 |
|---|---|---|
| `LocalDigitalBeing` | 对话滑窗（20 轮）、滚动摘要、L1 原文档案（FlexiMem）、落盘、`recordProactive` | `persona/LocalDigitalBeing.kt`，679 行 |
| `PersonaEngine` | 人设、提示词拼装、情绪/好感度、聊天流 `ChatItem`、长期记忆抽取 | `copilot/PersonaEngine.kt`，2158 行 |

两者不是统一大脑，是「记忆层 + 人格层」两段式：`respond()` → `LocalDigitalBeing.chat(text, brain, extras)` → 成功后 `LocalDigitalBeing.recordTurn` 记账。

#### 2.2 VoiceAssistant 有没有把对话回写？

**没有。语音链路完全绕开人格系统。**

证据链：

```kotlin
// MainActivity.kt:462-474
VoiceAssistant.onCommand = { command ->
    CopilotEngine.ask(this@MainActivity, command, ...)   // chatty 使用默认值 false
}
```

`CopilotEngine.ask` 的 `chatty` 默认是 `false`（`CopilotEngine.kt:256+` 参数表），而 `chatty` 是整个分流的唯一开关：

- `chatty = true` → `runDualBrain` → `PersonaEngine.respond` → 记忆窗口 / 聊天流 / 情绪
- `chatty = false` → 走 `CopilotVision.ask(...)` 老路径（`CopilotEngine.kt:481-495`），注入的是 `buildFullSystemPrompt`（工具型助手提示词），**不调 `PersonaEngine.recordTurn`、不调 `LocalDigitalBeing`、不调 `EmotionEngine`、`say(toChat=false)` 也不写聊天流**

`speech/VoiceAssistant.kt` 里 `grep CopilotEngine|PersonaEngine|LocalDigitalBeing|EmotionEngine` — **零命中**。它只做：唤醒 → ASR →（指代澄清）→ `onCommand?.invoke()` → 等 30 秒兜底 → TTS 播报。

**后果**：用语音跟她聊天，她的语气是「助手模式」不是「宏月模式」；这几句话不进记忆、不进聊天记录、不涨好感度，下次文字聊天她完全不记得。

#### 2.3 同样不进人格的还有两处

| 入口 | 代码 | 后果 |
|---|---|---|
| 灵动岛输入框 | `CopilotEngine.kt:240` `CopilotIslandOverlay.onUserMessage = { text -> ask(ctx, text) }`（默认 chatty=false） | 岛上打的字同样不进记忆、不改好感度 |
| 首页主控台问答 | `MainActivity.kt:481-483` `onAskCopilot` → `ask(this, text, onReply)` | 同上 |

只有人格页聊天框（`ui/PersonaTab.kt:313` `chatty = true`）是真正的「她」。

#### 2.4 结论一句话

**「统一大脑」不只是没建，现在是「一个大脑 + 三个平行入口」**：人格页是她，语音/灵动岛/首页是另一个工具助手。这是当前拟人感最大的结构性缺口（详见第四节）。

---

### 3. 灵动岛复读机：强制静默加了吗？

**结论：加了，而且是四道闸叠加，不是只靠提示词。**

#### 3.1 第一道：提示词给可执行出口

`CopilotEngine.kt:111-125`：

> 3. 如果这一帧里没有任何值得说的事（静止的桌面、在设置里翻页、聊天、刷列表、看网页、画面和刚才差不多），**必须只输出 `<SILENT>`**，不要解释。
> 4. 宁可十次里有九次 `<SILENT>`，也不要为了说话而说话。
> 输出只有两种可能：一句 20 字以内的弹幕，或者 `<SILENT>`。

注释里写明了设计理由：**「光说『不要说什么』模型会挑一件别的事说，必须给它一个可执行的出口 `<SILENT>`」**。

#### 3.2 第二道：`DanmakuGuard` 代码硬拦截（`copilot/DanmakuGuard.kt`）

| 层 | 判据 | 位置 | 结果 |
|---|---|---|---|
| 主动沉默 | 输出含 `<SILENT>`（容忍 `[SILENT]` / `（SILENT）` / `沉默` / `静默`） | `:66-76` | 直接丢弃，不弹 |
| 套话 | 命中 25 个客观描述短语：`我看到 / 我看到了 / 当前界面 / 屏幕上 / 画面显示 / 这张截图 / 显示的是 / 这是一个手机` … | `:56-63`、`:79-83` | 丢弃并静默 |
| 撞车 | 与最近 5 条之一相似度 ≥ **0.7**（编辑距离 + 子串包含双判据） | `:36`、`:45`、`:92-105` | 丢弃并静默 |

注释说明了为什么不用「包含」判重：「模型复读时通常是换一两个字（『吧』→『了』），那是距离不是包含」。

#### 3.3 第三道：画面差分开源节流

| 阈值 | 值 | 位置 | 作用 |
|---|---|---|---|
| `MAD_THRESHOLD` | 15 | `CopilotEngine.kt:74` | 相邻帧差异小于它，**根本不推理** |
| `SAME_SCENE_MAD` | 8 | `:86` | 和「上次开口那一帧」比，几乎没变就跳过（治时钟/光标的微动） |
| `COOLDOWN_MS` | 5000 | `:89` | 触发后 5 秒强制冷却 |

#### 3.4 第四道：去掉「保底重试」这个复读制造机

`CopilotEngine.kt:1193-1216` `generateBubble()`：

```kotlin
// 0.9.4：**不许保底重试**。保底那句是「这张手机截图里显示的是什么？」，
// 模型被要求无事发生时输出 <SILENT>，沉默就是空输出；一重试就等于逼它解说屏幕，
// 空 → 重试 → 「这是一个桌面」，复读废话正是这么来的。
VisionHub.look(context, frame, emotionPrompt(context), fallbackPrompt = null)
```

空输出被当作「她选择不说话」，`lastSkipReason = "本轮没有值得说的画面（模型沉默）"`，不报错、不重试。

此外换应用/重开陪玩会 `danmaku.reset()`（`:224`、`:1112`），避免「同一句吐槽在新场景被误判复读」。

#### 3.5 残留风险

- `BOILERPLATE` 是硬关键词匹配，「这波走位我看到机会了」这类**正常表达**会被误杀（概率不高，但存在）。
- 「复读」只按字面相似度判，**语义复读**（换个说法讲同一件事）拦不住，仍需靠提示词。
- `DanmakuGuard` 是进程内单例，进程重启后历史清空 —— 重启后头几句可能重复。

---

## 三、核心模块现状盘点

### 1. 好感度与情绪：夸她，界面会实时涨吗？

**数据结构与 UI 刷新链路是通的，但触发点在正常闲聊主路径上缺失 —— 这是本次扫描发现的最高优先级问题。**

#### 1.1 设计上应该怎么走

```
用户说「谢谢你，你真好」
  → CopilotEngine.ask(chatty=true)
  → … → PersonaEngine.recordTurn(user, assistant)        // 1918
      → LocalDigitalBeing.recordTurn                         // 1925
      → EmotionEngine.onTurn(appCtx, user)                   // 1930
          → signalOf() = PRAISE, deltaOf() = +1.0            // EmotionEngine.kt:86-106
          → 余数累加（carry 持久化在 SharedPreferences）
          → PersonaEngine.bumpAffinity(+1)                   // EmotionEngine.kt:172
              → profile.copy(affinity=next) → store.saveProfile → notifyChanged()  // PersonaEngine.kt:1847-1856
                  → PersonaTab.onChanged → refreshBond()     // PersonaTab.kt:264-266
                      → bondAffinity 变化 → BondCard 重组     // PersonaTab.kt:330
```

UI 侧确实是实时的：`PersonaTab.kt:264-267` 在 `DisposableEffect` 里挂 `PersonaEngine.onChanged`，回调里 `items = items(); refreshBond()`；`bumpAffinity` / `updateMood` 都调 `notifyChanged()`（`:1842`、`:1854`），主线程分发。

#### 1.2 实际上：正常聊天根本不触发

`PersonaEngine.respond()`（`:756`，函数体 427 行）内部统计：

| 关键字 | 出现次数 |
|---|---|
| `recordTurn` | **0** |
| `EmotionEngine` | **0** |
| `bumpAffinity` | **0** |

而 `EmotionEngine.onTurn` 全仓唯一调用点是 `PersonaEngine.kt:1930`，位于 `PersonaEngine.recordTurn` 内部。

正常闲聊的实际路径是：

```
CopilotEngine.ask(chatty=true)
  → runDualBrain() → ProbeLLM 判 Chat
  → PersonaEngine.respond()                       // CopilotEngine.kt:730
      → LocalDigitalBeing.chat()                  // PersonaEngine.kt:1131
          → b.replyWith(...) → recordTurn(text, answer)   // LocalDigitalBeing.kt:264
              ↑ 这是 LocalDigitalBeing 自己的 recordTurn，只写滑窗/档案/摘要，不碰情绪
  → say(reply) + addAssistantItem(reply)          // CopilotEngine.kt:737-738
```

`CopilotEngine.kt:739-740` 的注释写得很清楚：

> 0.8.12：这一轮已经由 `LocalDigitalBeing.chat` 记进滑动窗口了，这里再记一次会变成「同一句话占两轮」，摘要也会跟着失真

**为了避免双记窗口，绕开了 `PersonaEngine.recordTurn`，连带把情绪更新也绕掉了。**

#### 1.3 哪些路径才会涨好感度

| 路径 | 是否更新好感度 |
|---|---|
| 人格页普通闲聊（探针可用 → Chat） | ❌ 不涨 |
| 发图片 / 发视频 / 拍照 | ❌ 不涨（走 respond） |
| 探针不可用时的闲聊（`!looksLikeActionRequest`） | ❌ 不涨（走 respond） |
| 自动化关闭被拦截时 | ✅ 涨（`:572` 走 recordTurn） |
| 老路径 Chat intent（本地 2B 出 JSON） | ✅ 涨（`:587`） |
| 动作/任务执行完成 | ✅ 涨（`:614`、`:631`） |
| 语音 / 灵动岛 / 首页问答 | ❌ 不涨（chatty=false，连人格都不进） |

**换成用户视角**：你每天在人格页跟她正常聊天，夸她一百句，好感度基本不动；只有你让她帮你点个外卖、开个微信，那一轮才 +1。这解释了「为什么好感度仪表盘像摆设」。

#### 1.4 情绪规则本身是合理的

| 信号 | 关键词 | 好感变化 | 心情 |
|---|---|---|---|
| 被夸 | 谢谢/好棒/喜欢你/可爱…（25 个） | **+1.0** | 开心 |
| 被凶 | 滚/烦死/闭嘴/笨蛋/蠢…（19 个） | **-1.0** | 难过 |
| 普通 | 其他 | **+0.2** | 平静 |

`EmotionEngine.kt:96-106`。设计意图写在注释里：「负向惩罚是正向奖励的 5 倍，骂一句要五句好话才缓得回来」。
`SOFT_EXCEPTIONS`（`滚去睡/滚去休息/滚去吃饭`）会把「凶」放行成普通 —— 这个细节做得不错。

小数部分用 `affinity_carry` 持久化（`:39-41`、`:197-217`），冷启动不丢 0~0.9 点，避免「+0.2 永远凑不满 1」。

#### 1.5 等级与文案

`BondState.kt:24-30` 五档：陌生(0) / 熟人(30) / 朋友(50) / 知己(70) / 羁绊(90)。
`toneBlock(affinity, mood)`（`EmotionEngine.kt:130`）会把好感度转成**强制语气约束**注入提示词：<30 冷淡简短、≥80 亲昵关心。

---

### 2. 话题种子：主动发消息是靠时间无脑发，还是有依据？

**结论：有依据。心跳开口前会先查「记挂着的事」，没有话题才退化成泛泛问候。**

#### 2.1 种子从哪来

`suspend fun pickTopicSeed()`：`HeartbeatScheduler.kt:416-423`

```kotlin
val dao = AppDatabase.get(context).topicSeedDao()
runCatching { dao.purgeExpired(nowMs) }          // 先清过期，防止三周前的「头还疼吗」
dao.nextCandidate(nowMs, nowMs - TOPIC_REASK_HOURS * 3_600_000L)
```

`nextCandidate` 的 SQL（`Daos.kt:191-197`）：

```sql
SELECT * FROM topic_seed
WHERE (expireAt = 0 OR expireAt > :nowMs)
  AND (askCount = 0 OR lastAskedAt <= :reAskBefore)   -- 48 小时内问过的不再问
ORDER BY askCount ASC, createdAt ASC LIMIT 1          -- 问得少的优先，同档按加入时间
```

抽取来源：`MemoryExtractor.saveTopics()`（`MemoryExtractor.kt:394-425`），从聊天里提炼具体话题，走 `looksLikeSeed()` 过滤掉「不够具体」的，唯一索引 + `IGNORE` 幂等去重。

过期时间按语义粗分类（`MemoryExtractor.kt:368-390`）：

| 话里含 | 保鲜时长 |
|---|---|
| 后天 | 60h |
| 明天 / 今晚 | 36h |
| 下周 / 下个月 | 192h |
| 周末 / 这周 | 96h |
| 生日 | 168h |
| 计划类词 | 72h |
| 身体类词 | 24h |
| 其他 | 48h |

表上限 100 条（`TopicSeed.MAX_SEEDS`，`Entities.kt:226`），超了清最老。

#### 2.2 心跳怎么用它

`HeartbeatScheduler.perform()`（`:339-406`）：

1. `pickTopicSeed()` 拿种子；
2. `PersonaEngine.proactiveGreeting(context, seed?.content)`；
3. 生成失败 → `markHeartbeatFail` 退避 30 分钟，**不计入今日配额**；
4. 成功 → 先入聊天记录再弹通知（保证点通知进来那句话已在对话里）→ 记账 → `markAsked(seed.id)`。

日志会明确区分：`已主动开口（话题「明天体检」第 2 次）` / `已主动开口（无话题种子，泛泛问候）`。

#### 2.3 心智引擎也吃种子

`InnerMonologueEngine.kt:273-279`：一个人待着想事情时，也会拿 `nextCandidate` 的话题当引子，让独白不是凭空瞎想。这段独白会在用户下次开口时注入提示词，然后标记消费（`PersonaEngine.kt:1155-1159`），避免「一天问八遍体检结果」。

---

### 3. 心跳机制：频率多少？会不会疯狂烧 API？

#### 3.1 排程参数

| 参数 | 值 | 位置 |
|---|---|---|
| 检查周期 | 15 分钟（WorkManager 周期下限） | `HeartbeatScheduler.kt:88` |
| 活跃时段 | 09:00 – 22:00 | `:91-92` |
| 最短空闲 | 15 分钟（刚聊完不插嘴） | `:95` |
| 最小间隔 | 15 分钟 | `:98` |
| 一天上限 | **10 条** | `CopilotConfig.kt:79` |
| 被无视降频 | 连续 2 次 → 改为 2 小时一条 | `CopilotConfig.kt:82`、`:88` |
| 失败退避 | 30 分钟 | `CopilotConfig.kt:76` |
| 话题重问 | 48 小时 | `HeartbeatScheduler.kt:104` |

排程用 `ExistingPeriodicWorkPolicy.UPDATE` + `SCHEDULE_SIGNATURE` 指纹（`:80`、`:256-257`），并先用 `getWorkInfosForUniqueWork(...).get(5s)` 回源确认任务真在（`:230-232` 注释里记着 0.9.4 那个「日志说 15 分钟、实际还是 6 小时」的坑）。

#### 3.2 API 消耗估算（真实上限）

| 来源 | 频率 | 单日上限 |
|---|---|---|
| 主动问候（心跳） | 15 分钟检查一次，只在 9–22 点、距上次聊 >15 分钟、间隔 >15 分钟 | **≤ 10 次**（DAILY_LIMIT 硬顶） |
| 内心独白（心智引擎） | 每 2 小时醒一次；距上次聊 >20 分钟、间隔 >90 分钟、夜间 23–7 静默 | **≤ 约 7 次** |
| 每轮聊天主回复 | 每句 1 次 | 按聊天量 |
| 每轮记忆抽取 | 每轮异步 1 次（`extractMemoryAsync`） | 按聊天量（与主回复 1:1） |
| 情绪陪玩弹幕 | 本地模型 | 0 |
| 屏幕理解 | 本地模型优先 | 通常 0 |

**后台静默消耗 ≈ 17 次/天封顶**（10 + 7）。这个量级不算疯狂，属于合理区间。

**真正的消耗大头不是心跳，是「每轮聊天 = 主回复 + 记忆抽取两次调用」**。如果主 provider 按 token 计费，长期聊天的成本约是表面上的 2 倍。

#### 3.3 但心跳有一个真实的判据缺陷

`decide()` 依赖 `lastChatAt` 判定「他是不是刚说完话」（`:180`）：

```kotlin
if (idleMs < IDLE_MINUTES * MINUTE_MS) return Verdict.NOT_IDLE
```

而 `markChatNow()` 全仓唯一调用点是 `PersonaEngine.kt:1933`，在 `recordTurn` 内部 —— 和好感度问题同源。

于是出现两种失真：

| 情况 | 表现 |
|---|---|
| 用户只走正常闲聊（从不触发 recordTurn） | `lastChatAt = 0` → `Verdict.NEVER_CHATTED`（`:174`）→ **心跳一次都不会开口**，功能像不存在 |
| 用户曾触发过一次 recordTurn（比如让她执行过一个动作） | 锚点停在那一次 → 之后即使一直在聊，`idleMs` 仍会超过 15 分钟 → **可能每 15 分钟来一条问候，且「被无视」判定也会误记** |

也就是说：心跳现在要么完全不工作，要么在用户正聊天时打扰，取决于历史上有没有走过 recordTurn。**这不是频率参数问题，是锚点没刷新。**

---

## 四、隐患与待办

### 1. P0 / P1 隐患清单

| 级别 | 问题 | 证据 | 影响 |
|---|---|---|---|
| **P0** | 正常闲聊不更新好感度与心情 | `PersonaEngine.respond`（`:756`，427 行）内 `recordTurn`/`EmotionEngine` 出现次数为 0；`EmotionEngine.onTurn` 唯一调用点 `:1930` 在 `recordTurn` 内；正常路径是 `respond → LocalDigitalBeing.chat` | 「夸她好感度涨」这个核心拟人反馈在 95% 的对话里失效，仪表盘像假的 |
| **P0** | 正常闲聊不刷新 `markChatNow` | `grep markChatNow` 全仓仅 `PersonaEngine.kt:1933` 一处调用，同在 `recordTurn` 内 | 心跳要么永不开口（NEVER_CHATTED），要么在用户正聊天时打扰；「被无视」连击判定也会误计 |
| **P1** | 语音 / 灵动岛输入 / 首页问答全部不进人格 | `MainActivity.kt:462-474`（语音 → `ask` 默认 chatty=false）；`CopilotEngine.kt:240`（灵动岛 → `ask(ctx,text)`）；`MainActivity.kt:481-483`（首页）；`VoiceAssistant.kt` 内 CopilotEngine/PersonaEngine/LocalDigitalBeing/EmotionEngine 零引用 | 四个入口是「两个完全不同的人」：语音不记记忆、不写聊天流、不涨好感度、语气是助手不是宏月 |
| **P1** | 「统一大脑」不存在，无单一会话入口 | 全仓无 `UnifiedBrain`；事实双中枢 `LocalDigitalBeing` + `PersonaEngine` | 任何「人格相关」的新功能都必须同时改 3~4 条链路，改漏就出现今天这种「一半生效」 |
| **P2** | 主模型本身可看图时仍走两级调用 | `PersonaEngine.kt:968` `b64 = null` 无条件生效；`shouldUseCloud` 只判「有没有 Key」不判「主模型能否一次搞定」 | 多一次网络往返与 token，延迟增加 ~1–3s |
| **P2** | 弹幕套话过滤误杀正常表达 | `DanmakuGuard.kt:56-63` 硬关键词包含匹配 | 「这波走位我看到机会了」会被丢；语义复读仍拦不住 |
| **P2** | 情绪判定是硬关键词 | `EmotionEngine.kt:44-56`；如「你这笨蛋好可爱」同时命中 COLD 与 PRAISE，COLD 优先 | 玩笑式互怼会被判成被凶，好感度 -1 |
| **P2** | 心跳密度上限偏高 | `HEARTBEAT_DAILY_LIMIT = 10` + `MIN_GAP = 15min`（`CopilotConfig.kt:79`、`HeartbeatScheduler.kt:98`） | 修好锚点后，理论上 13 小时活跃时段内最多 10 条，仍偏密（建议 4~6 条） |
| **P3** | 视觉失败提示依赖 `onSystem`，非人格链路只走 `notice` | `PersonaEngine.kt:930-940`；`CopilotEngine.kt:399` `onSystem` 只在 `chatty` 时插气泡 | 非人格链路发图（目前没有入口，属预防项）只有弹幕没有红字 |
| **P3** | `LocalDigitalBeing.recordProactive` 不进 FlexiMem | `LocalDigitalBeing.kt:284-296` 注释已说明是刻意取舍 | 重启后她「忘了自己说过什么」，主动问候的连续性偏弱 |

### 2. 「拟人感」当前最欠缺的一环

**不是提示词，不是记忆厚度，是「统一会话入口」——她还没有一个唯一的自我。**

具体表现：

- **人格分裂**：文字聊天是宏月（有人设、有记忆、有好感度），语音和灵动岛是助手（工具提示词、无记忆、无情绪）。用户换个入口说话，就像换了个人。
- **状态更新挂错了层**：情绪、记忆抽取、聊天锚点这三件事写在 `PersonaEngine.recordTurn` 里，但主闲聊路径为了「避免窗口双记」绕开了它 —— 结果**为了不重复记一句话，把整个情绪系统跳过了**。
- **结果**：「好感度」这个最直观的拟人指标，在真实使用中几乎不动。用户会觉得「她根本不在乎我」，而代码里其实写了 1.0/-1.0/0.2 的完整模型。

#### 建议的最小改动（不改架构，只挪触发点）

把「一轮成功对话之后的副作用」从 `PersonaEngine.recordTurn` 下沉到 `LocalDigitalBeing.chat` 的成功处（`LocalDigitalBeing.kt:264` 之后），由它统一触发：

```
LocalDigitalBeing.chat()
  → val ok = recordTurn(text, answer)          // 已有 5 秒去重，返回 false 表示重复
  → if (ok) {
        EmotionEngine.onTurn(appCtx, text)      // 好感度/心情
        CopilotConfig.markChatNow(appCtx)       // 心跳锚点
        // 长期记忆抽取保持在 PersonaEngine.respond（已有，避免重复）
    }
```

理由与注意事项：

1. **两个入口都会经过 `LocalDigitalBeing.chat`**（`PersonaEngine.respond` 唯一回复出口），改一处即全覆盖；
2. `LocalDigitalBeing.recordTurn` 已有 5 秒去重（`DEDUPE_WINDOW_MS`），返回 `false` 时不加情绪，避免「respond 内部记一次 + 调用方再记一次」导致双加好感；
3. 同时要把 `CopilotEngine` 里那几处 `PersonaEngine.recordTurn` 改成只做「补记忆/聊天流」，避免情绪被加两遍（`:570`、`:587`、`:614`、`:631`）。

第二优先是把语音并入人格：把 `VoiceAssistant.onCommand` 的 `CopilotEngine.ask` 改成 `chatty = true`，并让 TTS 播报 `PersonaEngine` 的回复而不是工具结果。这一步需要处理「语音下指令」和「语音闲聊」的分流，建议先用探针判 Chat/Action 决定 chatty，而不是一刀切。

---

## 附：本次扫描的验证命令清单

```bash
# 版本
grep -n "versionCode\|versionName" app/build.gradle.kts

# 提交
git log --oneline -8

# 构建与测试（实测通过）
gradle :app:compileDebugKotlin --offline -q
gradle :app:testDebugUnitTest --offline
python -c "统计 test-results/*.xml"   # 30 files / 395 tests / 0 failures

# 关键证据
grep -n "b64 = null" app/src/main/java/com/nexus/agent/copilot/PersonaEngine.kt
grep -rn "EmotionEngine.onTurn" app/src/main/java/
grep -rn "markChatNow" app/src/main/java/
grep -rn "CopilotEngine.ask(" app/src/main/java/
grep -rn "UnifiedBrain" app/src/main/java/ --include=*.kt
```

---

*本报告为只读扫描产物，未修改任何业务代码。*
