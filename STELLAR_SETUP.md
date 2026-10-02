# Stellar 安装与配置指南

## 为什么用 Stellar 而不是原版 Shizuku

原版 Shizuku 在手机重启后失效，必须连接电脑重新激活。
Stellar 是 Shizuku 的增强版，支持看门狗自动重启和开机自启，重启后无需电脑。

## 安装步骤

### 1. 卸载原版 Shizuku

如果之前安装过原版 Shizuku，先卸载。Stellar 和原版包名冲突，不能共存。

### 2. 下载 Stellar

从官方仓库下载最新版：
https://github.com/roro2239/Stellar/releases

### 3. 首次激活（仅需一次）

1. 手机设置 → 关于手机 → 连续点击版本号 7 次，开启开发者模式
2. 设置 → 系统管理 → 开发者选项 → 开启「USB 调试」和「无线调试」
3. 打开 Stellar App → 点击「无线调试 - 启动」
4. 下拉通知栏，在 Stellar 通知中输入无线调试页面的 6 位配对码
5. 显示「正在运行」即激活成功

### 4. 开启关键设置

在 Stellar 设置中开启以下开关：
- 看门狗（Watchdog）：服务意外停止时自动重启
- 开机启动（Start on Boot）：重启后自动恢复服务
- 跟随启动（Follow Startup）：应用随 Stellar 服务自动启动

### 5. iQOO 12 / OriginOS 6 专属配置

1. 设置 → 电池 → 后台耗电管理 → 找到 Stellar 和 Nexus Agent → 设为「允许后台高耗电」
2. 设置 → 应用管理 → 自启动管理 → 允许 Stellar 和 Nexus Agent 自启动
3. 设置 → 电池 → 更多电池设置 → 关闭「睡眠模式」对 Stellar 和 Nexus Agent 的限制

### 6. 验证

1. 重启手机
2. 等待 30 秒
3. 打开 Nexus Agent，观察 Shizuku 状态
4. 如果显示「Stellar 已连接」，说明配置成功
5. 执行一个简单任务（如"打开设置"），确认注入功能正常

## 常见问题

### Q: 重启后显示未连接

A: 等待 30 秒。Stellar 需要系统完成启动、Wi-Fi 可用后才能自动恢复。如果超过 1 分钟仍未连接，打开 Stellar App 手动点击「启动」。

### Q: 无线调试在重启后关闭

A: 部分 ROM 会在重启后关闭无线调试。需要在开发者选项中开启「无线调试保持开启」（如果该选项存在）。

### Q: 仍然需要连接电脑

A: 确认使用的是 Stellar 而非原版 Shizuku。原版不支持非 Root 设备的开机自启。
