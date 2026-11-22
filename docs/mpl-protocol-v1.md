# MPL ↔ 宏月（LunaMacro）动作协议 v1

> **状态：冻结（FROZEN）**
> 冻结起点：宏月 `0.9.31` 开发开始前（2026-11-22）
> 冻结范围：**信封 + 错误码语义 + 鉴权约定 + health/event 字段**。动作取值清单不冻结（靠 `health.actions` 协商）。
> 变更规则：任何字段改名 / 语义变化 → `v: 2`；纯新增字段 → 留 v1，双方必须忽略未知字段。
> 校验方式：本文件在两个仓库中逐字节相同，各 README 记 `sha256`。不一致即表示有一方未同步。
> 修订：`v1.1`（2026-11-22）新增可选字段 `sentAt`，见 §4.1。`v` 仍为 `1`。

---

## 0. 角色与边界

```
MPL   = 脑（记忆、意图、人格、二次确认、失败措辞）
宏月   = 手（执行、风控、幂等、超时、审计）
```

四条不可协商的边界：

1. MPL **永不**直接操作界面，只发 JSON。
2. 宏月 **永不**生成动作语义，只执行。
3. 一票否决两边都做，宏月侧是最终裁决（一票否决权在宏月）。
4. 动作结果由宏月回报，**记忆只由 MPL 写**。宏月不写人格记忆。

---

## 1. 传输

| 项 | 值 |
|---|---|
| 监听 | `127.0.0.1:18080`（**只绑 loopback**，不绑 0.0.0.0） |
| 协议 | HTTP/1.1，`Content-Type: application/json; charset=utf-8` |
| 方法 | 仅 `POST`（`/v1/ping`、`/v1/health` 为 `GET`） |
| 请求体上限 | 16 KiB |
| 连接 | `Connection: close`（不要求 keep-alive） |
| 事件端口 | MPL 侧 `127.0.0.1:18081`（同样只绑 loopback） |

### 1.1 禁止项（安全）

```
✗ 不发任何 Access-Control-* 响应头
✗ OPTIONS 预检一律 404（不做 CORS 预检）
✗ 不支持 ?token= 查询参数
✓ token 只认请求头 X-MPL-Token
```

理由：自定义头会让浏览器发起预检，预检 404 即被浏览器拦下。若允许 CORS 或接受查询参数，手机浏览器里任意网页都能向 loopback 发起跨域 POST（CORS 只阻止读响应，不阻止副作用）。

---

## 2. 鉴权与配对

### 2.1 Token

- 32 字节随机（hex 64 位），由 **MPL 生成**并显示一次。
- 用户手工粘贴进宏月「设置 → MPL 桥」，宏月只做本地格式校验后存入私有 SharedPreferences。
- **不存在配对端点**。`/v1/pair` 永不实现。
- 比对使用常量时间比较（`MessageDigest.isEqual`）。
- `ping` / `health` 响应中不得出现 token 的任何片段。

### 2.2 配对完成的判定

```
MPL  → GET /v1/ping     （匿名）200 + {"v":1,"app":"LunaMacro"}   ← 端口上确实是宏月
MPL  → GET /v1/health   （带 token）200                            ← 配对成立
                        401                                        ← 配对码不对，提示用户重新复制
```

`paired` 是 MPL 的**本地 UI 状态**，不是服务端字段。

### 2.3 401 的处理

```
收到 401 → 本次动作不发
         → 提示「配对码可能变了，去宏月重新复制一次」
         → 用户确认后才清除本地 token；不自动清
```

---

## 3. 风险分级（`risk` 字段）

| 值 | 含义 | `confirm` 要求 |
|---|---|---|
| `safe` | 只读 / 导航，不改变用户数据 | 可 `false` |
| `caution` | 会点击或输入 | 必须 `true`，否则 `NEEDS_CONFIRM` |
| `forbidden` | 命中一票否决 | 宏月直接 `FORBIDDEN` |

**宏月不信任客户端报的 `risk`**：按 `action + args + 当前前台包名` 自己重算，取更严者。`reason` 字段只用于日志与弹窗文案，不参与任何判断（防提示注入）。

### 3.1 一票否决（宏月侧独立维护词表，客户端不得据此推断）

```
支付 / 付款 / 转账 / 红包 / 充值 / 提现 / 购买 / 下单 / 结算 / 还款
卸载 / 注销 / 删除账号 / 退出登录 / 改密码 / 密码 / 验证码 / 免密
```

命中即 `FORBIDDEN`，**不产生候选、不执行、不重试**。

### 3.2 敏感界面

前台包名/应用名属于「钱袋子」类（支付宝、银行、证券、基金、钱包等）时：

- 任何写动作 → `FORBIDDEN`
- `read_screen` → `ok:true` + `data.redacted=true` + 占位文本（**不报错**，报错会诱发重试）

---

## 4. 请求

```json
{
  "v": 1,
  "id": "a7f3c1c8-3b21-4f0e-9d6a-2c7e5b1f0a44",
  "action": "click_text",
  "args": { "text": "发送", "index": 0 },
  "risk": "caution",
  "timeoutMs": 5000,
  "sentAt": 1795300000000,
  "reason": "她想帮你把这条发出去",
  "confirm": true
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `v` | ✅ | 必须为 `1`，否则 `UNSUPPORTED_VERSION` |
| `id` | ✅ | UUIDv4，幂等键。重发必须沿用同一 `id` |
| `action` | ✅ | 见 §5，须在 `health.actions` 中 |
| `args` | ✅ | 随 action 变化，可为 `{}` |
| `risk` | ✅ | `safe` / `caution` / `forbidden` |
| `timeoutMs` | | 默认见 §5，服务端按 action clamp |
| `sentAt` | | 客户端发出时的 epoch 毫秒，见 §4.1 |
| `reason` | | 给用户看的解释，仅日志/弹窗 |
| `confirm` | | 默认 `true`；`false` 只允许 `safe` |

### 4.1 `sentAt` 与陈旧请求（v1.1 新增）

```
服务端在执行之前先看年龄：

  age = now - sentAt
  age > timeoutMs + 2000   →  不执行，直接回 TIMEOUT
  否则                      →  正常执行
```

**为什么要这个字段**：进程被系统冻结（ColorOS / MIUI 等）时，内核照常完成握手、把请求留在 socket 缓冲区，`accept()` 要等解冻才返回。用户十分钟后随手打开宏月 → 积压的请求被一次性执行 → **屏幕上突然自己点了一下「发送」**。超时只是没做成，这个是做错了。

约定：

- 单位 **epoch 毫秒**，与服务端 `System.currentTimeMillis()` 同一时基。
- 双方在**同一台设备**上，共用一个系统时钟 → **不做时差容差**，不需要 NTP、不需要偏移量。哪天两侧拆到不同设备，这条要重新讨论。
- **缺字段按新鲜处理**：不带 `sentAt` 的请求照常执行（兼容旧客户端），风险在双方都升级前仍然存在。
- 判定只看 `sentAt`，**不能**用服务端 accept 时间代替——冻结时 accept 发生在解冻那一刻，拿不到真实发送时间。
- 被拒的请求**不写幂等账本**（它没有执行），客户端补一次新 `id` 也不算重复。

---

## 5. 动作

`health.actions` 是能力声明。客户端**必须先读再用**；未上报的动作一律 `UNKNOWN_ACTION`。

| action | args | risk | 默认/上限 ms | 引入版本 |
|---|---|---|---|---|
| `read_screen` | `{maxChars?: 2000}` | safe | 3000 / 5000 | 0.9.31 |
| `open_app` | `{appName}` | safe | 20000 / 30000 | 0.9.31 |
| `click_text` | `{text, index?: 0}` | caution | 5000 / 8000 | 0.9.32 |
| `input_text` | `{text, target?}` | caution | 8000 / 12000 | 0.9.32 |
| `run_macro` | `{macroId}` | caution | 60000 / 120000 | 0.9.32 |
| `list_macros` | `{}` | safe | 3000 / 5000 | 0.9.32 |

### 5.1 `read_screen`

只回文字，**永不回坐标**。

```json
{ "text": "微信\n聊天\n文件传输助手\n发送", "chars": 28, "truncated": false, "redacted": false }
```

- 敏感界面 → `{"text":"（敏感界面，不读取）","chars":0,"redacted":true}`
- 密码输入框节点直接跳过，不进入 `text`
- 超长截断时 `truncated: true`，并附 `warnings:["text_truncated"]`

### 5.2 `open_app`

`appName` 为中文应用名（如「微信」「设置」）。**不接受 `packageName`**。

### 5.3 `click_text`

只按文字/描述匹配控件，**绝不退回坐标兜底**。找不到即 `TARGET_NOT_FOUND`。

### 5.4 `input_text`

`target` 为输入框提示文字/描述，省略则取当前焦点框或页面唯一输入框。语义为**替换**而非追加。

### 5.5 `run_macro`

- `macroId` = 宏月 `MacroStore` 的 UUID（由 `list_macros` 获得）。
- 宏月对 MPL 来源**强制** `stopOnFail=true`，并在 `warnings` 回 `forced_stop_on_fail`。
- 中途某步失败 → `STOPPED_AT_STEP`，`error.step` 为失败步骤序号（1 起）。

### 5.6 `list_macros`

```json
[
  { "id": "…", "name": "每日签到", "steps": 6, "coordinateSteps": 0 },
  { "id": "…", "name": "刷图", "steps": 24, "coordinateSteps": 19 }
]
```

`coordinateSteps` 供 MPL 在确认弹窗中提示「这个动作里有 N 步是按坐标点的，可能点错」。

---

## 6. 响应

```json
{
  "v": 1,
  "id": "a7f3c1c8-…",
  "ok": true,
  "elapsedMs": 2400,
  "data": {},
  "warnings": [],
  "error": null
}
```

```json
{
  "v": 1,
  "id": "a7f3c1c8-…",
  "ok": false,
  "elapsedMs": 900,
  "data": null,
  "warnings": [],
  "error": { "code": "TARGET_NOT_FOUND", "message": "找不到「发送」按钮", "step": 2, "retryAfterMs": null, "reason": null }
}
```

- 业务错误**一律 HTTP 200 + `ok:false`**（HTTP 4xx/5xx 只用于传输层：401 鉴权失败）。
- `error` 字段：`code` 必填；`message` 人类可读（仅日志/调试，不作为判断依据）；`step` 仅 `STOPPED_AT_STEP`；`retryAfterMs` 仅 `BUSY`；`reason` 仅 `PERMISSION_DENIED`（取值 `locked` / `no_accessibility` / `no_shizuku`）。
- `warnings` 取值：`forced_stop_on_fail` / `contains_coordinate_steps` / `text_truncated` / `redacted`。

### 6.1 错误码（12 个）

| code | 触发 | 客户端该做什么 |
|---|---|---|
| `UNSUPPORTED_VERSION` | `v != 1` | 停用，提示升级 |
| `UNKNOWN_ACTION` | 不在 `health.actions` 中 | 停用该动作 |
| `FORBIDDEN` | 一票否决 / 敏感界面 / 密码框 / 自动化总闸关闭 | 不重试、不换动作绕 |
| `NEEDS_CONFIRM` | `caution` 且 `confirm=false` | 补确认后重发同 `id` |
| `APP_NOT_FOUND` | 查不到启动 Intent | 让用户说清是哪个 App |
| `TARGET_NOT_FOUND` | 找不到控件 / `index` 越界 | 让用户手动点 |
| `STOPPED_AT_STEP` | 宏中途失败 | 「卡在第 N 步」 |
| `TIMEOUT` | 服务端超时 / 请求已陈旧（§4.1） | **不自动重发** |
| `BUSY` | 引擎在忙 / 宏在跑 / 宏月自身在前台 | **以 `retryAfterMs` 为准**；缺字段时从 3s 起翻倍，最多 3 次 |
| `PERMISSION_DENIED` | 无障碍未绑定 / 需要 Shizuku 而无 / 锁屏 | 按 `reason` 给不同文案 |
| `UNKNOWN_RESULT` | 上次执行中宏月进程被杀 | **不重试**，让用户看一眼 |
| `CANCELLED` | 用户在本机主动中断（宏月侧「中断」按钮） | **不重试、不道歉**，说「好，停了」 |

### 6.2 `BUSY` 与「无响应」是两回事

| | 服务端回什么 | 含义 | 客户端做法 |
|---|---|---|---|
| `BUSY` | 200 + `ok:false`，带 `retryAfterMs` | **明确拒绝**：它在、它听见了、它现在不接 | 等，重试（有上限） |
| 连上但读不到任何字节 | 无响应 | **进程被冻**：它在、但人不在 | 不重试，提示「切到前台 / 关掉省电限制」 |
| `ECONNREFUSED` | 无 | **没开** | 提示「宏月没开」，不重试 |

三种情况的建议完全不同，**UI 文案不能复用**。`BUSY` 说「等一下」，冻结说「动一下手机」，拒连说「把宏月打开」。

---

## 7. 幂等

服务端按 `id` 去重，**两段写**：

```
收到 id → 写 {"id","status":"running"}
执行完 → 写 {"id","status":"done","result":{...}}
```

| 命中状态 | 服务端行为 |
|---|---|
| `done` 且未过期（10 分钟） | 直接返回缓存的完整响应，**不执行** |
| `running` 且未过期 | 后来的请求等待前一个完成，然后返回同一结果 |
| `running` 且已过期 / 进程重启后 | 返回 `UNKNOWN_RESULT`，**不重跑** |
| 无记录 | 执行 |

客户端侧同样保留最近 64 条 `id → 结果`，重发前先查本地缓存。

---

## 8. `GET /v1/ping`（匿名）

```json
{ "v": 1, "app": "LunaMacro" }
```

只用于确认「端口上是宏月」。**不得**据此判定已连接——必须再用带 token 的 `health` 200 才算连上。

## 9. `GET /v1/health`（需 token）

```json
{
  "v": 1,
  "app": "LunaMacro",
  "version": "0.9.31",
  "port": 18080,
  "ts": 1795300000000,
  "ready": true,
  "injection": "shizuku",
  "accessibility": true,
  "screenOn": true,
  "keyguard": false,
  "foreground": "com.tencent.mm",
  "sensitiveUi": false,
  "busy": false,
  "actions": ["read_screen", "open_app"],
  "limits": { "defaultTimeoutMs": 15000, "maxTimeoutMs": 120000 }
}
```

| 字段 | 说明 |
|---|---|
| `ready` | 至少 `read_screen` 可用 |
| `injection` | `shizuku` / `accessibility` / `none` —— 描述**写动作**的注入能力 |
| `screenOn` | 息屏时手势落不到控件，客户端不应发写动作 |
| `keyguard` | 锁屏时无障碍根节点是锁屏界面，写动作必然失败 |
| `busy` | 与 `BUSY` 错误码同源 |

客户端缓存 `health` 不超过 30 秒，并在 `injection=none` 时**强制刷新一次**再放弃。

## 10. 事件（宏月 → MPL）

```
POST http://127.0.0.1:18081/v1/event
X-MPL-Token: <同一个 token>
```

```json
{
  "v": 1,
  "id": "a7f3c1c8-…",
  "type": "action_done",
  "at": 1795300000000,
  "action": "click_text",
  "summary": "帮他在微信里点了「发送」",
  "ok": true,
  "code": null
}
```

- `id` 必填：客户端按 `id` 去重（保留最近 200 个），并用于把事件对回请求。
- `code` 在 `ok:false` 时填写错误码，供客户端区分「不想做 / 做不了 / 卡住了」。
- 不带 token 或 token 不符 → **401，不入库、不回 body**。
- 投递失败：服务端 30 分钟待发队列（上限 50 条，超出丢最旧），退避 0.5s / 2s / 8s；补发保持 **FIFO 顺序**。
- 客户端**按 `at` 排序插入记忆**，不按到达顺序（补发可能导致乱序到达）。

---

## 11. 兼容与演进

```
信封字段改名 / 类型变化 / 错误码语义变化  → v: 2，双方共存期 ≥ 6 个月
新增 action 取值                          → 留 v1，靠 health.actions 协商
新增响应字段 / warnings 取值               → 留 v1，双方忽略未知
新增错误码                                → 留 v1（客户端必须按 code 字符串分支，不得用数值）
```

永不进入动作清单（无论版本）：

```
schedule_task   —— 让外部 App 在宏月里埋未来的自动执行
start_game_auto —— 长时托管，无法用单次动作语义约束
screenshot      —— 屏幕内容不交给外部 App（v2 若确有必要，走 content:// 一次性授权 + 当次确认）
```

---

## 12. 威胁模型（明确边界）

| 防护对象 | 是否覆盖 |
|---|---|
| 同一设备上其他普通 App 读写本接口 | ✅ 32 字节 token + loopback + 无 CORS |
| 手机浏览器内网页跨域调用 | ✅ 仅认自定义头 + 预检 404 |
| 恶意事件污染 MPL 记忆 | ✅ 事件通道同样要 token + 按 id 去重 |
| 用户误确认导致误操作 | ✅ 一票否决 + 敏感界面 + 密码框，三层在服务端独立兜底 |
| 已 root 设备读取 token | ❌ 不防（token 明文存 SharedPreferences，不引入额外依赖） |
| 用户主动交出 token | ❌ 不防 |
| 宏月自身被替换成同包名假应用 | ⚠️ 部分（`ping` 可被冒充；以带 token 的 `health` 200 为准） |

---

## 13. 变更记录

| 日期 | 版本 | 变更 |
|---|---|---|
| 2026-11-22 | v1 | 冻结。相对最初草案：删 `screenshot`；`open_app` 参数 `{app\|packageName}` → `{appName}`；health 拆 `ping`；事件补 `id`/`code`；新增 `UNKNOWN_RESULT` / `CANCELLED` / `keyguard` / `warnings`；明确 CORS 禁令与两段式幂等 |
| 2026-11-22 | v1.1 | 新增可选字段 `sentAt`（§4.1）：服务端按年龄拒执行陈旧请求，消灭「冻结期间积压的点击在解冻时集中执行」。`v` 仍为 `1`，旧客户端不带该字段则按新鲜处理。`BUSY` 改为以 `retryAfterMs` 为准。新增 §6.2 区分 `BUSY` 与「无响应」。**本行变更使 sha256 换新，双方需重新记录** |
