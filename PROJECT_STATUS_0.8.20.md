# 0.8.20 项目状态同步快照

> 生成时间：2026-11-07 09:25（GMT+8）
> 生成方式：**只读扫描**（读代码 + 跑 git/gradle 只读命令），未修改任何业务代码。
> 对照基线：0.8.12 计划任务清单 + 当前 `master`（`d87f67e`）。
> 说明：本文件是新增文档，不属于业务代码改动。

---

## 一、版本与基线

### 1.1 版本号

来源：`app/build.gradle.kts:25-26`

| 项 | 值 |
|---|---|
| versionCode | **95** |
| versionName | **0.8.20** |
| applicationId | `com.nexus.agent` |
| minSdk / targetSdk | 24 / 36 |
| ABI | 仅 `arm64-v8a` |
| 当前 tag | `v0.8.20`（本地与远端均指向 `d87f67e`） |
| 发布产物 | `Desktop/LunaMacro-APK/LunaMacro-0.8.20.apk`（51,867,540 B，2026-11-07 08:52） |
| 更新配置 | `update.json` = 0.8.20 / `recommended`；`announcement.json` id `2026-11-07-02`，versions = 0.8.20 / 0.8.19 / 0.8.18 / 0.8.17 |

工作区状态：`git status --short` **无输出**（干净，无未提交改动）。

### 1.2 git log --oneline（最近 10 条）

```
d87f67e 0.8.20: 读屏做成能自证
2830d51 0.8.19: 修人格对话串台/半截话/复读
57186f9 feat(vision): 视觉收成单一入口 VisionHub，五条链路统一尺寸+保底重试，新增一键预热（0.8.18）
bc68aab docs(announce): 0.8.17 公告——视觉跑通与更新链路三修
283a394 fix(update): 镜像 CDN 缓存导致「已是最新」误报，改为双源并行取最大版本（0.8.17）
768086d perf(update): 镜像按实测延迟排序，gh-proxy 提到首位（0.8.16）
a48bd3f perf(update): 镜像优先 + 缩短握手超时，不挂代理也不再干等（0.8.15）
9354f89 fix(update): releases 列表按 tag 字符串排序导致 alpha10/11 丢失（0.8.14）
8fbb9ce fix(vision): 副驾/陪玩/Agent 本地视觉全部改走 :vision，模型常驻 10 分钟
582feda chore(update): alpha10 提为 recommended，让 App 主动弹更新提示
```

### 1.3 编译与单测

实际执行（本次扫描现场跑，不是读缓存结论）：

| 命令 | 结果 |
|---|---|
| `gradle :app:compileDebugKotlin --offline -q` | **BUILD SUCCESSFUL，退出码 0**（仅有 1 条无害警告：`SDK XML versions up to 3 but version 4 was encountered`） |
| `gradle :app:testDebugUnitTest --offline` | **BUILD SUCCESSFUL，26s，1 executed / 25 up-to-date** |

单测统计（解析 `app/build/test-results/testDebugUnitTest/*.xml`，先删除旧结果强制重跑）：

```
tests=174  failures/errors=0  skipped=0  files=14
```

**结论：编译通过，174 个单测全绿，0 失败 0 跳过。** 新增的清洗/记忆/视觉路由用例都已覆盖。

补充：代码规模（`app/src/main/java/com/nexus/agent/` 下 137 个 `.kt`，**36,404 行**；测试 14 个文件 / 174 个用例）。文件数前几名：`agent` 35、`game` 27、`ui` 16、`speech` 9、`copilot` 8。

---

## 二、0.8.12 计划任务落实确认（重点核对）

### 2.1 纯本地长记忆 —— ✅ 已实现且已接入

**实现文件**：`app/src/main/java/com/nexus/agent/persona/LocalDigitalBeing.kt`（584 行，`object`）

滑动窗口与滚动摘要的核心参数（`LocalDigitalBeing.kt:62-93`）：

| 参数 | 值 | 作用 |
|---|---|---|
| `MAX_TURNS` | **20** | 窗口保留轮数（一问一答 = 1 轮） |
| `EVICT_TURNS` | **10** | 超窗时一次性移出、送去摘要的轮数 |
| `SUMMARY_MAX_CHARS` | 100 | 单段摘要上限 |
| `SUMMARY_TOTAL_MAX` | 400 | 滚动摘要总长，超出从头砍（越早越稀释） |
| `HARD_CAP` | 40（20+20） | 摘要一直失败时的硬上限，超了强丢最旧 |
| `MAX_SUMMARY_FAILURES` | 2 | 连续失败 2 次 → 退化为 20 轮环形缓冲 |
| `DEDUPE_WINDOW_MS` | 5000 | 双链路重复记账去重 |

**接入链路（不是死代码）**：

| 位置 | 事实 |
|---|---|
| `PersonaEngine.kt:196-197` | `init()` 里 `LocalDigitalBeing.init(app)` + `registerBrain(defaultBrain())` |
| `PersonaEngine.kt:670-676` | `respond()` 把人格提示词 / 画面描述 / 工具结果包进 `Brain`，主链路走 `LocalDigitalBeing.chat(text, brain)` |
| `PersonaEngine.kt:1134` | `addAssistantItem()` 走 `LocalDigitalBeing.recordTurn(u, assistant)` 记账 |
| `PersonaEngine.kt:485 / 883` | 拼 System Prompt 时注入 `LocalDigitalBeing.summaryText()`（滚动摘要） |
| `PersonaEngine.kt:274` | 冷启动 `restore` 从 Room 配对出历史 → `restoreWindow(...takeLast(MAX_TURNS))` |
| `PersonaEngine.kt:1160 / 1199` | 清空 / 重置时同步 `LocalDigitalBeing.clear()` |
| `CopilotEngine.kt:554 / 582` | 明确注释「这一轮已由 LocalDigitalBeing.chat 记账」，避免重复写 |

持久化：`filesDir/persona/long_memory.json`，**先写 `.tmp` 再 rename**（`LocalDigitalBeing.kt:463-488`），断电不会留半截 JSON；手写序列化，纯 JVM 单测可跑。

降级设计：摘要模型（优先 `deepseek-chat`，无 Key 跟随主 provider）连续 2 次不可用 → `degraded=true`，窗口退回 20 轮环形缓冲，并打 WARN，不会把 prompt 撑爆。

**判定：滑动窗口（20 轮）+ 滚动摘要均已实现、已接入主对话链路。** 唯一前提是「摘要需要云端 Key」；无 Key 时退化为环形窗口，属设计内行为。

### 2.2 视觉 P0 崩溃隔离 —— ✅ 已隔离，主进程不会再因装载模型崩

**进程层隔离**：

| 证据 | 内容 |
|---|---|
| `AndroidManifest.xml:84-88` | `VisionWorkerService`，`android:process=":vision"`，`exported=false` |
| `LocalScreenRecognizer.kt:203-217` | `ensureLoaded()` 里硬闸：`if (!isVisionProcess())` → `state=FAILED`，**主进程一律不装载 1.4GB 模型**，直接返回错误 |
| `LocalScreenRecognizer.kt:234-238` | 用 `/proc/self/cmdline` 判断，末尾是否 `:vision` |
| `NexusApplication.kt:47 / 71` | `:vision` 进程跳过数据库 / 定时任务等无关初始化 |

**调用层隔离（五条链路统一走 `VisionHub`，主进程零本地推理）**：

| 链路 | 文件:行 | 调用 |
|---|---|---|
| 人格聊天读屏 | `PersonaEngine.kt:802` | `VisionHub.look(...)`（`describeScreenInWorker` 内**没有任何** `LocalScreenRecognizer` 直接调用） |
| 副驾图文/纯文本 | `CopilotVision.kt:148 / 150` | `VisionHub.look` / `VisionHub.ask` |
| 情绪陪玩（气泡） | `CopilotEngine.kt:977` | `VisionHub.look` |
| Agent 状态识别（指挥台/语音） | `Executor.kt:495` | `VisionHub.look` |
| 本地动作规划 | `LocalActionPlanner.kt:77` | `VisionHub.look` |

**残留例外（重要，列进隐患）**：`game/GameAutoPilot.kt:88` 仍直接 `LocalScreenRecognizer.recognize(frame)`。因为跑在主进程，会被 `ensureLoaded` 的闸挡回 `null` —— **不会崩，但游戏托管的画面识别实际上永远是空**。`VisionSelfCheck.run()` 在主进程只做「文件是否齐全 + embedding 模式」静态检查，真推理走「测试视觉模型 → `:vision`」，这条是对的。

**判定：人格聊天读屏已完全隔离到 `:vision`；主进程不存在「装载模型 → native 崩 → 整个 App 消失」的路径**（闸门是釜底抽薪式的，逐处调用点漏掉也不会崩）。唯一缺口是 `isVisionProcess()` 读不到 cmdline 时**返回 true 放行**（保守设计），属极端情况下的理论风险。

### 2.3 插件防呆机制 —— ✅ 两项都已落地

**`allowedTools` 白名单**

| 位置 | 内容 |
|---|---|
| `PluginManifest.kt:159` | `val allowedTools: List<String>? = null` |
| `PluginManifest.kt:206-226` | 解析：字段缺失 = `null`（不限，兼容老插件）；显式 `[]` = 一个工具都不许 |
| `PluginManifest.kt:241` | 序列化回写 `allowedTools`，导入导出不丢 |
| `PluginScope.kt:69-77` | `begin(manifest)` 把白名单装进作用域，`null` 时不产生限制 |
| `PluginManager.kt:237-272` | `run()` 开跑前 `begin`，结束（含失败/中断/忙碌）一律 `end`，不污染后续对话 |
| `Executor` + `TaskStateMachine.kt:259-270` | 实际执行时按白名单拒绝越界工具 |
| `PluginSection.kt:296 / 386` | 插件卡片上直接展示「允许的工具」 |

内置示例已示范：原神日常 / 星铁日常都只放行 `launch_app, click_text, tap, swipe, back, wait, screenshot, find_text`（`PluginManager.kt:316-334`）。

**`SLIDER` 滑动条**

| 位置 | 内容 |
|---|---|
| `PluginManifest.kt:12-31` | `PluginFieldType` 新增 `SLIDER` |
| `PluginManifest.kt:34-38` | 别名兼容：`slider / range / percent / level` 都映射到 `SLIDER` |
| `PluginManifest.kt:56-68` | `PluginField(type, minValue, maxValue)`，`range` getter 自动纠正非法区间（默认 0..100） |
| `PluginManifest.kt:84-101` | 解析 + 校验：非 SLIDER 忽略 min/max，SLIDER 兜底默认区间 |
| `PluginManager.kt:309-314` | 内置「原神日常」新增字段「赶时间程度」`SLIDER 0..100`，默认 40 |

**判定：`allowedTools` 白名单与 `SLIDER` 字段均已实现、已接入执行链，并有内置插件作为可抄模板。**

### 2.4 三项汇总

| 0.8.12 计划项 | 实现 | 接入生产链路 | 备注 |
|---|---|---|---|
| 纯本地长记忆（20 轮滑窗 + 滚动摘要） | ✅ | ✅ | 摘要依赖云端 Key，无 Key 退化为环形缓冲 |
| 视觉 P0 崩溃隔离到 `:vision` | ✅ | ✅ | 主进程硬闸；`GameAutoPilot` 例外（功能失效但不崩） |
| 插件 `allowedTools` 白名单 | ✅ | ✅ | `null` = 不限，兼容老插件 |
| 插件 `PluginField` 的 `SLIDER` | ✅ | ✅ | 含 min/max 校验与别名 |

---

## 三、核心模块现状盘点

### 3.1 视觉模型：链路已收口，真机「聊天读屏」仍未闭环

**真机实测证据（来自 0.8.13-alpha10 的 nexus_log）**：

- 同一台 vivo V2307A、同一个 `:vision` 子进程，288×640 的两张截屏分别输出 **314 字节**（「该截图显示了手机的设置界面…」）与 **1080 字节**真实描述 → **本地 Qwen2-VL-2B 在子进程里是能看图的**。
- 但人格聊天链路当时自己截 324×720，同一 prompt 下输出 **1 字节**（空）→ 用户侧表现就是「对话看不到屏幕」。

**0.8.18 ~ 0.8.20 的处置**：

| 版本 | 动作 |
|---|---|
| 0.8.18 | 新建 `VisionHub` 作**唯一入口**：统一长边 **640**、宽高对齐 **8**、空输出用保底 prompt 重试一次；五条链路只认它；新增 `ACTION_WARM` 一键预热与设置页按钮 |
| 0.8.19 | 修人格对话串台/半截话（与视觉无关，但同时污染了读屏结果的呈现） |
| 0.8.20 | 预热升级为 **`VisionHub.diagnose()` 端到端自检**：装载 → 截屏 → 本地描述 → 把模型**原话**贴在设置页；断在「装载 / 截图 / 推理」哪一步直接写明；`look()` 只在「子进程活着但吐空」时重试，超时与崩溃不再白等第二个 90 秒 |

**当前状态判定（必须如实说）**：**部分验证，未闭环。** 本地 2B-VL 在 `:vision` 能出描述有日志实证；但「人格聊天里能稳定看到屏幕」**尚未拿到 0.8.20 的真机自检回传**，因此不能记为已修复。

**降级策略（三级）**：

1. 本地 `:vision` 优先：`VisionHub.look()` → 描述注入角色提示词【你这一轮看到的屏幕】（截到 240 字，明确「只当背景、禁止罗列界面元素」）；
2. 本地不可用（文件缺失 / 子进程崩 / 吐空）→ 若云端 provider 支持图片则走云端视觉；否则纯文本；
3. 纯文本时**不再静默**：通过 `onNotice` 回调弹出**独立提示气泡**（`CopilotVision.NO_VISION_NOTICE` / `diedNotice(reason)`，见 `PersonaEngine.kt:522-525, 655-660`），正文不再挂一长串括号。

**其余保护**：

- 推理前内存闸 `MIN_INFER_RAM_MB = 600`（`LocalScreenRecognizer.kt:116, 702-708`），不够就返回一句人话，不硬闯被 lmkd 收走；
- 模型常驻：`IDLE_STOP_MS = 10min` 空闲才卸载，任务期间 `cancelIdleStop()`；
- 模型 6 件套（`llm.mnn / llm.mnn.weight / visual.mnn / visual.mnn.weight / tokenizer.mtok / config.json`），另有隐形杀手 `embeddings_bf16.bin`（缺了装载成功、一推理 SIGABRT），自检已单独报 `SEPARATE_MISSING`。

### 3.2 人格与记忆

**长期记忆：有，且是两层。**

| 层 | 实现 | 内容 |
|---|---|---|
| 事实层（0.8.0） | `persona/MemoryExtractor` → `long_term_memory` 表 | 每轮 `recordTurn` 后异步抽「用户是谁 / 偏好 / 约定」，进 System Prompt |
| 过程层（0.8.12） | `persona/LocalDigitalBeing` | 20 轮原话滑窗 + ≤400 字滚动摘要，落 JSON 存档 |

**UI 入口：只有「计数 + 清空」，没有记忆管理页。**

- `PersonaTab.kt:159-166`：顶部副标题一行 —— 「已记住最近 N 轮 · 长期记忆 M 条」；
- `PersonaTab.kt:170-175`：右上「清空」→ `PersonaEngine.clearAll()`（会同时清记忆表，不会出现「界面空了它还记着」的矛盾）；
- 右上齿轮 → `PersonaSettingsScreen`：**只有名字 / 身份 / 口癖 / 三个性格滑块**（外向、感性、叛逆），**没有好感度滑块，也没有记忆列表**；
- 全项目 `ui/` 目录下 `grep affinity` **零命中** → 好感度对用户不可见、不可调。

**「羁绊」相关：字段有，驱动没有。** `persona_profile.affinity`（0~100，默认 50）、`mood`、DB 的 `updateAffinity/updateMood` DAO 都在；`PersonaEngine.bumpAffinity()` / `updateMood()` 也写好了，但**全仓库业务代码零调用**，只有单测引用过 —— 也就是说，好感度和心情目前是**冻结的常量**，只会被拼进 System Prompt（`PersonaEngine.kt:475, 875`，并明确叮嘱模型「绝对不要说出这些数字」）。

### 3.3 游戏与插件

**插件本质：JSON 清单 + 自然语言目标 + 云端模型 + Executor，不是确定性脚本。**

- `PluginManifest` 能声明的：`id / name / description / packageName / goal / settingsSchema / recommendedModel / allowedTools / builtin`；
- 执行方式：`PluginManager.buildTaskText()` 把 goal + 用户配置拼成一句话，交给 `CopilotEngine.ask()`，由 LLM 规划 + `Executor` 调工具；
- 内置两个示例（`PluginManager.ensureBuiltins`）：
  - **原神日常**（`com.miHoYo.Yuanshen`）：goal「完成原神今天的每日委托并领取奖励」，4 个字段（次数 / 顺手采集 / 操作风格 / 赶时间程度 SLIDER），白名单 8 个工具；
  - **星穹铁道日常**（`com.miHoYo.hkrpg`）：2 个字段，同款白名单。
- 静态游戏知识：`assets/knowledge/genshin.json`（1.2KB）、`starrail.json`（1.0KB），只有 `flowDescription / screenTypes / commonActions`，是很粗的骨架，不含坐标或确定性流程。

**实际落地能力（客观评估）**：

| 环节 | 现状 |
|---|---|
| 稳定性 | 没有真机「跑通一次原神日常」的证据记录；执行完全依赖云端 LLM 逐步决策 + 无障碍读屏，属**尽力而为**，不具备可复现性 |
| 失败恢复 | `GameAutoPilot` 有「超时/失败 → 升级主模型重规划」逻辑，但重规划质量同样取决于云端 |
| 画面识别 | `GameAutoPilot.kt:88` 直调 `LocalScreenRecognizer.recognize`（主进程）→ 被闸挡 → **恒返回 null**，这一步现在等于没有 |
| 安全边界 | 白名单 + `SafetyPolicy` + `ReflectionModule`（敏感界面关键词二次兜底）+ Prompt 层禁支付/密码，三道防线在 |

**一句话**：插件框架、防呆、配置 UI 都是真的；**「原神日常」目前只是「让云端模型自己看着办」的封装，不是能一键稳定跑完的产品级脚本。**

---

## 四、隐患与待办

### 4.1 P0 / P1 隐患

| 级别 | 问题 | 位置 | 影响 | 说明 |
|---|---|---|---|---|
| **P0** | 「聊天能看到屏幕」未闭环验收 | `PersonaEngine.describeScreenInWorker` + `VisionHub` | 核心卖点是否成立未知 | 0.8.20 已把断点做成可视化自检，**等真机回传「预热并看一屏」的结果**；不回传就无法判定是拼装问题还是推理问题 |
| **P1** | 游戏托管画面识别恒失败 | `GameAutoPilot.kt:88` | 托管退化为「闭眼点」 | 主进程直调 `recognize` → 被 `ensureLoaded` 闸挡回 null；应改走 `VisionHub.look` |
| **P1** | `:vision` 服务不是前台服务 | `AndroidManifest.xml:84-88` | 后台/息屏时子进程可能起不来或被收走 | `VisionWorkerClient` 用 `runCatching { startService }` 兜住 → 只报「子进程启动失败」，不崩；影响定时任务、主动问候这类后台链路 |
| **P1** | 1.3GB 模型常驻的内存压力 | `LocalScreenRecognizer`（`MIN_INFER_RAM_MB=600`） | 低内存机型推理被 lmkd 收走 | 已有内存闸 + 常驻 10 分钟；仍建议在低配机默认不预热 |
| **P1** | `embeddings_bf16.bin` 缺失 → 推理阶段 SIGABRT | `ModelManager.EmbeddingMode.SEPARATE_MISSING` | 装载成功但一推理就 abort，极难自诊 | 自检已单独识别并给出「只补 embedding 文件」路径 |
| **P1** | 视觉隔离在极端情况下可能失效 | `LocalScreenRecognizer.isVisionProcess()` 读不到 cmdline 时 `return true` | 主进程可能装载模型 → 闪退 | 保守放行的取舍；建议日志里记录「闸门判定失败已放行」以便取证 |
| **P2** | 长记忆摘要依赖云端 Key | `LocalDigitalBeing.requestSummary` | 无 Key 用户退化为 20 轮环形窗口 | 设计内降级，会打 WARN；「过程性记忆」因此对纯离线用户不可用 |
| **P2** | 人格设置项无法感知关系状态 | `PersonaSettingsScreen` | 用户看不到、也改不了好感度 | 与 4.3 的「养成感」直接相关 |

**内存泄漏**：本次只读扫描**未发现明确泄漏点** —— 长记忆是有界数据结构（≤20 轮 + ≤400 字），Bitmap 在 `VisionHub.normalize` / `VisionSelfCheck` 里都有 `recycle()` 兜底，`VisionWorkerClient` 断连有 `unbindService` 清理。风险主要在 native 常驻内存，不在 Java 层引用堆积。

### 4.2 BUG.txt 遗留项

`C:\Users\25814\Desktop\BUG.txt` 当前内容只有一行说明：

```
本文件用于记录BUG的代办事项没完成一个就删一个
```

**没有任何未完成条目。** 也就是说：0.8.19 / 0.8.20 的问题都已归档，当前没有用户显式登记的待办；上面 4.1 的隐患是本次扫描新发现的，尚未写进 BUG.txt。

### 4.3 如果现在做「养成感」，最大的技术阻碍

**结论：最大的阻碍不是模型能力，而是「关系状态没有任何驱动源」。** 具体四层：

1. **好感度 / 心情是死数据（最致命）**
   `affinity`、`mood` 字段、DAO、`bumpAffinity()`、`updateMood()` 全都写好了，但**业务代码一次都没调用**（只有单测调过）。所以聊一百句，好感度还是 50、心情还是初始值，System Prompt 里那句「好感度 50/100」永远不变 —— 无论 UI 做得多漂亮，背后都没有可增长的数值。
   → 最小改动方案：在 `PersonaEngine.respond()` 成功后做一次**轻量判定**（规则式：轮次/长度/情绪词/是否被感谢，或一次便宜的 JSON 打分），调 `bumpAffinity(±N)` + `updateMood()`，并落库。不需要新表，不需要改 UI。

2. **没有「主动」调度器**
   现在只有用户手建的**定时任务**（`ScheduleSection` → `ScheduleRunner` → `CopilotEngine.ask`），语义是「到点执行一条指令」，不是「她想你了来找你」。缺少：上次对话时间、空闲时长、早晚时段、事件回访（「昨天你说的那件事怎么样了」）这些触发条件。
   → 需要新增一个「主动意图」判定层（本地规则 + 轻量模型），并复用现有 `ScheduleRunner` 的通知/播报通道。

3. **后台存活是硬约束**
   主动找用户发生在 App 不在前台时。而视觉常驻依赖 `:vision` 服务（非前台服务），后台 `startService` 可能直接被系统拒绝（`VisionWorkerClient` 只能兜住并报错）；vivo/iQOO 的后台清理又格外激进。**主动链路如果还要读屏，失败率会很高**；不读屏（纯文本问候）则可行。
   → 取舍：养成感的第一版做「纯文本主动问候 + 基于长记忆的话题回访」，不依赖读屏。

4. **记忆不产出「关系事件」，也没有承载 UI**
   长记忆目前只服务「这轮怎么接话」，不产出「里程碑 / 纪念日 / 共同经历」这类可展示、可累积的东西；UI 上也没有羁绊页、回忆录、好感进度条（`PersonaTab` 只有一行计数）。
   → 需要一层「关系事件」抽取（从 `long_term_memory` + 滚动摘要里挑出值得记住的事），再把好感度曲线可视化。

**建议的落地顺序**：① 让好感度/心情真的会动（改动最小、收益最大）→ ② 加羁绊页把数值和记忆事件显示出来 → ③ 加纯文本主动问候 → ④ 最后才考虑「主动 + 读屏」的组合。

---

## 附：本次扫描的判定口径

- 「已实现」= 代码存在且有生产调用点；「已接入」= 主链路真的走它，不是死代码。
- 所有行号对应当前 `master`（`d87f67e`），后续提交会漂移。
- 视觉真机结论区分「日志实证」与「用户反馈」，未拿到证据的一律标注为未闭环，不用推测填补。
