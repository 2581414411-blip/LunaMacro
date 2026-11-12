# 批次 F：无感后台自动化 · 架构评估报告

> 评估对象：虚拟屏刷抖音 → 大模型判趣 → 分享到 QQ → 强制中断
> 评估日期：2026-11-12
> 结论：**四个模块里两个可行、一个半可行、一个做不到。** 详见下表。

---

## 0. 结论摘要

| 模块 | 判定 | 一句话原因 |
|---|---|---|
| ① VirtualDisplay 隐形刷抖音 | **半可行** | `MediaProjection` 建的 VirtualDisplay 只能接收画面，**不能承载 Activity**；要真跑抖音得走 Shizuku 造 public display + hidden API |
| ② 大模型判断有趣 | **可行** | 截图 + 视觉模型，链路已存在 |
| ③ Intent 注入无感分享 QQ | **做不到** | Android 没有让第三方 App 无界面写入 QQ 的公开 API，`ACTION_SEND` 必然拉起 QQ |
| ④ 防御性强制中断 | **可行（有一条做不到）** | 前台应用/屏幕状态能监听；**用户触摸收不到** |

如果目标是「绝对不影响用户使用手机」，那么 ③ 与这条铁律**本身互斥** —— 这不是实现问题，是平台边界。

---

## 1. 模块一：Virtual Display 隐形刷抖音

### 1.1 方案原文与实现差距

原文设想：

> 申请 MediaProjection → 创建不可见 VirtualDisplay → 在虚拟屏幕中启动抖音进程 → 让抖音在后台渲染

**问题在于第二步。** `VirtualDisplay` 不是一块「能装 App 的屏幕」，它是一个**渲染目标**：

```kotlin
// MediaProjection 的 VirtualDisplay：把 display 的内容画到你给的 Surface 上
mediaProjection.createVirtualDisplay(
    "luna-hidden",
    width, height, dpi,
    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
    imageReader.surface,      // ← 输出目标
    null, handler
)
```

`FLAG_AUTO_MIRROR` 的含义是「镜像默认屏的内容」。它**不提供窗口管理**，也没有 `Display` 的 Activity 承载能力。在它上面 `startActivity` 要么失败，要么回落到主屏（Display 0）。

### 1.2 真要在虚拟屏上跑抖音，需要什么

| 步骤 | API | 所需权限 | shell(uid 2000) 有吗 |
|---|---|---|---|
| 1. 创建可承载窗口的虚拟屏 | `DisplayManager.createVirtualDisplay(...)`，flag 用 `VIRTUAL_DISPLAY_FLAG_PUBLIC \| OWN_CONTENT_ONLY` | `CREATE_VIRTUAL_DISPLAY`（signature\|system） | ✅ 有 |
| 2. 在该屏启动 Activity | `IActivityManager.startActivityAsUser(...)` + `ActivityOptions.setLaunchDisplayId(vdId)` | `INTERNAL_SYSTEM_WINDOW` | ✅ 有 |
| 3. 注入触摸到该屏 | `IInputManager.injectInputEvent(event, MODE_INJECT)` + `MotionEvent.setDisplayId(vdId)` | `INJECT_EVENTS` | ✅ 有 |
| 4. 截该屏画面 | `screencap -d <vdId>`（或反射 `SurfaceControl.captureDisplay`） | `READ_FRAME_BUFFER` | ✅ 有 |

**权限这一关是通的**，因为 Shizuku 拿到的是 shell uid，adb shell 持有上述全部 signature 权限。

**难点不在权限，在 API 形态**：

- 第 1 步要走 `DisplayManager` 的 hidden 方法，参数里带 `VirtualDisplayConfig`（Android 11+ 新增 Builder，形态改过两次）；
- 第 2 步的 `startActivityAsUser` 有多个重载，参数数量随版本变化，且 `ActivityOptions` 的 `setLaunchDisplayId` 在 API 30 前后签名不同；
- 每一步都得用 `ShizukuBinderWrapper` 包 Binder 再反射调用 —— 现有的 `ShizukuInputInjector` 已经验证过这条路能走通，但只针对 `injectInputEvent` 一个方法。

### 1.3 三个必须先验证的不确定点

这三件事**决定整条路成不成立**，且只能真机测：

| 不确定点 | 若为真 | 后果 |
|---|---|---|
| 抖音是否检测「跑在非 0 号屏」 | 是 | 直接退出 / 降级到竖屏占位 / 播放黑屏 |
| 播放页是否有 `FLAG_SECURE` | 是 | `screencap -d` 截出全黑图，视觉模型无从判断 |
| 虚拟屏上的视频解码/音频是否正常 | 否 | 有画面无声音，或解码报错 |

### 1.4 判定

**半可行。** 技术路径存在，但：
- 依赖 4 处以上 hidden API 反射，版本兼容性极差；
- 成败取决于抖音的行为，不在我们控制内；
- 即使全通，后台持续解码 + 截图 + 推理的功耗在中端机上很可观。

**建议：先用 PoC 验证 1.3 的三点，再决定是否投入。**

---

## 2. 模块二：大模型判断「有趣」

### 2.1 可行性

完全可行，且项目里已有全部零件：

- 截图：虚拟屏走 `ImageReader`；
- 本地视觉：`game/LocalScreenRecognizer` + `VisionHub`（MNN，398MB `visual.mnn`）；
- 云端视觉：`VisionRouter`（已有 Key 路由与降级链）；
- 结构化输出：`util/MiniJson`（已用于装修/轨迹解析）。

### 2.2 实现要点

```kotlin
// 提示词要强制 JSON，且只给两个字段 —— 字段越多小模型越容易写错
val prompt = """
你在看一段短视频截图。判断它是否「有趣 / 好笑 / 有分享价值」。
只输出 JSON，不要解释：
{"share": true/false, "comment": "如果分享，配一句 20 字以内的话"}
""".trimIndent()
```

**限频是必须的**：视觉推理一次 1~3 秒，每 5 秒一次意味着 CPU/GPU 常驻高负载。建议**每 30~60 秒抽一帧**，而不是逐帧看。

---

## 3. 模块三：无感分享到 QQ

### 3.1 方案原文

> 利用 Shizuku 特权，在后台构建 Intent(ACTION_SEND) → 精准指定 QQ 的分享组件 → 执行 startActivity，如果可能利用系统级 API 静默发送

### 3.2 结论：**「静默」这一段做不到**

Android 的进程隔离决定了：**没有任何公开 API 能让 App A 无界面地往 App B 里写内容**。所有跨应用投递都必须经过用户可见的界面（分享面板、选择器、目标 App 的接收页）。

`ACTION_SEND` 能做的上限是**跳过选择器**：

```kotlin
val intent = Intent(Intent.ACTION_SEND).apply {
    type = "image/*"
    putExtra(Intent.EXTRA_STREAM, imageUri)      // 必须 content:// + grant 读权限
    putExtra(Intent.EXTRA_TEXT, comment)
    setPackage("com.tencent.mobileqq")           // ← 跳过选择器，直达 QQ
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
startActivity(intent)
```

更「精准」的写法是指定组件：

```kotlin
intent.setClassName(
    "com.tencent.mobileqq",
    "com.tencent.mobileqq.activity.JumpActivity"  // QQ 的分享入口
)
```

但：

- Android 11+ 包可见性需要 `<queries>` 声明，否则 `setClassName` 静默失败；
- 类名随 QQ 版本变化，硬编码等于埋雷；
- **仍然会拉起 QQ 的分享页**，用户必须点「发送」。

### 3.3 「弹出极小的分享卡片，2 秒内自动关闭」可行吗

不可行。原因：

- 那个界面属于 QQ 进程，我们无权关闭它；
- 唯一能关的手段是 `am force-stop com.tencent.mobileqq` —— 会杀掉 QQ、丢消息，且频繁强杀极易触发风控；
- 用我们自己的透明中转 Activity 也控制不了 QQ 的 Activity 生命周期。

### 3.4 真正无感的唯一通道

**通知的 `RemoteInput`**（批次 E 已实现）：在通知栏里直接回复，不打开 QQ、不闪屏。

限制：
- 只能发**纯文本**，带不了图片；
- 需要 QQ 当前有一条带 RemoteInput 的通知；
- 只能回复**当前会话**，不能主动发起。

### 3.5 判定

**做不到「无感」。** 可选降级：

| 方案 | 用户感知 | 可行性 |
|---|---|---|
| `ACTION_SEND` + setPackage，用户点发送 | 闪一次 QQ 分享页 + 1 次点击 | ✅ 稳定 |
| 存相册 + 本机通知「这条可以发给 XX」 | 一条通知，用户自己发 | ✅ 最稳 |
| 通知 RemoteInput 回文本 | 完全无感 | ✅ 但只能回文本、只能被动 |
| 强杀 QQ 关分享页 | 无 | ❌ 高风控风险 |

---

## 4. 模块四：防御性强制中断

### 4.1 四条信号里三条可用

| 信号 | 可行性 | 实现 |
|---|---|---|
| 前台应用变化 | ✅ | 已有 `NexusAccessibilityService` 的 `TYPE_WINDOW_STATE_CHANGED` |
| 屏幕点亮 / 解锁 | ✅ | `ACTION_SCREEN_ON` 广播 + `KeyguardManager.isKeyguardLocked` |
| 用户切到非抖音应用 | ✅ | 同第一条 |
| **用户触摸屏幕** | ❌ | 无障碍服务**收不到**普通触摸事件 |

### 4.2 为什么「检测触摸」做不到

`AccessibilityService` 只有开启 `FLAG_REQUEST_TOUCH_EXPLORATION_MODE`（TalkBack 那套）才能收到 `TYPE_TOUCH_INTERACTION_START`，而那会**改变整机交互方式**（单击变选中、双击才点击），绝对不能为这个功能打开。

替代判据（够用）：

```
用户在用手机  =  屏幕亮着  &&  前台应用 != 抖音
```

### 4.3 中断动作清单

```kotlin
fun forceStop() {
    inputJob?.cancel()          // 停止注入协程
    injector.release()          // 释放 Shizuku 注入
    virtualDisplay?.release()   // 释放虚拟屏
    imageReader?.close()
    mediaProjection?.stop()     // 释放录屏（否则状态栏常驻图标不消失）
    state.value = Idle
}
```

**`mediaProjection.stop()` 必须调**：不调会留下常驻的「正在录屏」提示，用户会以为手机被监控。

---

## 5. 与现有架构的兼容性

| 现有模块 | 与本方案的交互 | 需要改动 |
|---|---|---|
| `input/ShizukuInputInjector` | 注入目标需从主屏切到虚拟屏 | 每次 `MotionEvent` 加 `setDisplayId(vdId)`；坐标按虚拟屏分辨率换算 |
| `agent/ScreenCapture` | 只能截主屏（无障碍 `takeScreenshot`） | 虚拟屏截图另起一套（`ImageReader` / `screencap -d`），**不要混用** |
| `game/VisionHub` / `VisionRouter` | 可直接复用 | 输入换成虚拟屏的 Bitmap 即可 |
| `accessibility/NexusAccessibilityService` | 提供前台应用变化 | 加一个回调给中断器 |
| `userservice/NexusForegroundService` | 长任务需要前台服务保活 | 加一个 `specialUse` 类型的子任务 |
| `qq/`（批次 E） | 分享链路与通知回复是两条独立通道 | 不冲突，但**不要**在自动回复线程里夹分享 |

**结论：不冲突，但注入与截图两处都要按「display 维度」改造，而不是简单的参数替换。**

---

## 6. 分级实施方案

### A 档（推荐先做）：她挑，你发

不刷抖音，改为「她替你发现有分享价值的东西」：

1. 监听相册新增（`ContentObserver` on `MediaStore.Images/Video`）；
2. 新增的图片/短视频 → 本地视觉模型判断「有趣度」；
3. 命中 → 生成一句配文；
4. 发本机通知：「这条可以发给小明：『哈哈哈这也太像你了』」；
5. 用户点通知 → `ACTION_SEND` 定向 QQ。

| 项 | 评估 |
|---|---|
| 工作量 | 1~2 天 |
| 风险 | 低（不碰抖音、不碰风控、不占前台） |
| 用户感知 | 一条通知 |
| 是否违反铁律 | 不违反 |

**代价**：需要用户先把内容存下来（截图/保存视频）。**收益**：立刻可用、零风险。

### B 档（值得先做 PoC）：验证虚拟屏能不能截到抖音

只写 150 行探针，不写引擎：

```
1. Shizuku 建 public virtual display（1080×1920, dpi 420）
2. 反射 startActivityAsUser 在该 display 上启动抖音
3. sleep 8s，screencap -d <vdId> 存一张 PNG
4. 人工看一眼：是黑屏？是抖音首页？是别的？
5. 若能截图，注入一次上滑，再截一张，看画面变没变
```

**判定标准**：

| 观察结果 | 结论 |
|---|---|
| 全黑 | 抖音设了 FLAG_SECURE 或拒绝虚拟屏 → **停，别再投入** |
| 抖音正常显示 | 路通，可以继续做引擎 |
| 启动失败 / 回落主屏 | hidden API 没打通，换 Android 版本再试成本极高 → **停** |

### C 档（不建议）：静默分享 + 强杀关页

无公开 API，只能靠强杀 QQ 实现「无感」，代价是丢消息 + 风控。**不建议做。**

---

## 7. 风险清单

| 风险 | 概率 | 后果 | 缓解 |
|---|---|---|---|
| 抖音检测虚拟屏 | 中高 | 黑屏 / 退出，功能不存在 | PoC 先验证 |
| `FLAG_SECURE` | 中 | 截图全黑 | 同上 |
| hidden API 随版本崩 | 高 | 换机就废 | 每处反射都 try-catch + 明确降级日志 |
| 后台解码发热 | 高 | 中端机烫手、被 ROM 杀 | 限时（如 5 分钟）+ 限帧率 |
| QQ 分享风控 | 中 | 限流 / 临时封禁 | 全局限流（每小时 ≤ 3 次） |
| 录屏图标残留 | 中 | 用户以为被监控 | `stop()` 放 finally |
| 权限叠加告警 | 高 | 无障碍 + 通知 + 悬浮窗 + 录屏 + Shizuku | 设置页集中说明用途 |

---

## 8. 决策建议

**建议选 B 档的 PoC 先跑一次。** 理由：

- 虚拟屏这条路的核心不确定性（抖音配不配合）**只能用 150 行代码换一个确定答案**；
- 如果黑屏，省下的是几百行反射代码和后续无穷的兼容性维护；
- 如果通了，再投入引擎才有意义。

**同时把 A 档做掉。** 它不依赖任何不确定前提，交付后立刻可用 —— 而且「她注意到你相册里这条很好笑」这个交互，其实比「她在后台偷偷刷抖音」更像陪伴。

---

## 9. 附：关键 API 速查

```kotlin
// 造一个能承载 Activity 的虚拟屏（需要 CREATE_VIRTUAL_DISPLAY，shell 有）
val dm = context.getSystemService(DisplayManager::class.java)
val vd = dm.createVirtualDisplay(
    "luna-vd", w, h, dpi, surface,
    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
    null, handler
)   // ← 这个重载是 @hide，要反射

// 在该屏启动 Activity（需要 INTERNAL_SYSTEM_WINDOW，shell 有）
val opts = ActivityOptions.makeBasic()
opts.setLaunchDisplayId(vd.display.displayId)   // API 26+
am.startActivityAsUser(intent, opts.toBundle(), UserHandle.CURRENT)  // @hide

// 注入到该屏
val ev = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_DOWN, x, y, 0)
ev.setDisplayId(vd.display.displayId)           // public API 29+
inputManager.injectInputEvent(ev, MODE_INJECT)  // @hide，Shizuku 已封装

// 截图
Runtime.getRuntime().exec(arrayOf("screencap", "-d", "$vdId", "/sdcard/luna.png"))
```
