# 项目现状扫描报告

> 生成时间：2026-10-05
> 说明：只读扫描结果，对应 DEV_ROADMAP.md 的「现状」部分。

---

## 一、版本状态

| 项 | 值 |
|---|---|
| versionCode | 12 |
| versionName | 0.6.2 |

**git log --oneline（最近 15 条）**：
```
50ca99d docs: 开发进度同步报告
ed2db1c fix: 修复唤醒词能量门限误伤，版本 0.6.2
365b584 chore: 发布 0.6.1
e541954 feat: 游戏Agent与界面集成
a5d4858 feat: 游戏Profile系统
2540d10 feat: 游戏规则引擎
7b97b40 feat: 区域提取与游戏状态识别
cb87be6 feat: 游戏高频截图循环
65da8a7 fix: 修复Macro录制，增加游戏场景手动坐标记录
99ef45f docs: 新增版本号管理规则
20448b9 brand: 文档与项目名改为宏月
9528730 brand: TTS播报文案改为宏月（无改动）
e572dfa brand: 悬浮窗与通知文案改为宏月
0acfc6c brand: 应用名改为宏月
e5e2675 feat: Macro 系统（录制/回放/管理）
```

**git tags**：`v0.2.0` `v0.3.0` `v0.3.3` `v0.4.0` `v0.5.1` `v0.6.0-alpha` `v0.6.1` `v0.6.2`

---

## 二、目录结构与代码规模

包根：`app/src/main/java/com/nexus/agent/`，共 16 个子目录、63 个 `.kt` 文件、**11420 行**。

| 目录 | 文件数 | 行数 | 说明 |
|---|---|---|---|
| ui | 4 | 2311 | MainActivity / MacroTab / GameTab / OnboardingActivity |
| agent | 7 | 1709 | AgentRunner / Planner / Executor / PromptBuilder / LlmClient / LlmClientFactory / ScreenCapture |
| speech | 6 | 1178 | VoiceAssistant / TtsSpeaker / AsrClient / VoiceCommandRecorder / ConversationContext / VoiceState |
| agent/tools | 18 | 1099 | 语义工具（15 个 Tool） |
| overlay | 2 | 968 | ThinkingOverlay（含录制/十字准星）/ RunningIndicator |
| input | 2 | 865 | ShizukuManager / ShizukuInputInjector |
| macro | 4 | 594 | Macro / MacroStep / MacroStore / MacroRecorder / MacroPlayer |
| game | 7 | 590 | GamePerceptionLoop / RegionExtractor / GameStateRecognizer / RuleEngine / GameRuleRunner / GameProfile / GameProfileStore |
| wakeword | 1 | 591 | WakeWordDetector（KWS ONNX + 手写 MFCC） |
| system | 4 | 542 | BootReceiver / LogExporter / StabilityHelper / StellarInstaller |
| accessibility | 2 | 378 | NexusAccessibilityService / UiTreeParser |
| userservice | 2 | 281 | NexusForegroundService / UserServiceImpl |
| safety | 1 | 94 | SafetyPolicy |
| util | 1 | 83 | AnyJsonAdapter |
| ui/theme | 1 | 69 | Theme |
| data | 0 | 0 | （空目录） |
| （根） | 1 | 68 | NexusApplication |

---

## 三、已实现模块清单

### 1. Agent 核心层
| 模块 | 状态 |
|---|---|
| AgentRunner | ✅ |
| Planner | ✅ |
| Executor | ✅ |
| PromptBuilder | ✅ |
| LlmClient / LlmClientFactory | ✅ |
| ConversationContext（多轮上下文） | ✅ |

### 2. 语义工具层（ToolRegistry 已注册 15 个工具）
`click_text` / `click_id` / `open_app` / `press_back` / `press_home` / `scroll` / `wait` / `long_press` / `drag` / `double_tap` / `multi_touch` / `play_macro` / `list_macros` / `start_game_auto` / `stop_game_auto`

### 3. 输入注入层
| 模块 | 状态 |
|---|---|
| ShizukuManager | ✅ |
| ShizukuInputInjector | ✅ |
| UserServiceImpl | ✅ |
| Stellar 集成 | ✅（内置 assets/stellar.apk v1.1） |

### 4. 屏幕感知层
| 模块 | 状态 |
|---|---|
| NexusAccessibilityService | ✅ |
| UiTreeParser | ✅ |
| Qwen-VL 视觉模式 | ✅（qwen-vl-max，DashScope，UI 树 <3 元素时降级用截图定位） |

### 5. 语音助手层
| 模块 | 状态 |
|---|---|
| WakeWordDetector（唤醒） | ⚠️ 模型可用但误触发待修 |
| VoiceCommandRecorder（录音） | ✅ |
| AsrClient（ASR） | ✅（DashScope 云端） |
| VoiceAssistant（状态机） | ✅ |
| TtsSpeaker（TTS） | ✅ |
| 续听模式 | ✅ |

### 6. 游戏层（game/ 目录 7 个文件）
| 模块 | 状态 |
|---|---|
| GamePerceptionLoop | ✅ |
| RegionExtractor | ✅ |
| GameStateRecognizer | ⚠️ 仅血条颜色检测 |
| RuleEngine | ✅ |
| GameRuleRunner | ✅ |
| GameProfile / GameProfileStore | ✅ |
| MacroRecorder / MacroPlayer | ✅（在 macro/ 目录） |

### 7. 浮窗层
| 模块 | 状态 |
|---|---|
| ThinkingOverlay | ✅（思考窗 + 录制模式 + 十字准星） |
| RunningIndicator | ✅ |

### 8. 安全层
| 模块 | 状态 |
|---|---|
| SafetyPolicy.checkTaskText | ✅ |
| SafetyPolicy.checkToolCall | ✅ |

---

## 四、品牌状态

- `strings.xml` 中 `app_name` = **宏月** ✅
- 用户可见文案中**已无** "Nexus Agent" 残留；剩余 "Nexus" 均为类名/包名/日志 tag/代码注释（按约定保留，不属用户可见文案）。

---

## 五、已知问题

1. **TODO/FIXME 注释**：无（grep 未发现真实 TODO/FIXME）。
2. **编译警告**：无（compileDebugKotlin BUILD SUCCESSFUL，无 warning）。
3. **测试覆盖**：项目**无单元测试/仪器测试**，所有模块仅靠编译 + 人工验证，缺乏自动化测试覆盖。
