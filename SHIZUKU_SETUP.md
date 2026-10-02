# Shizuku 自动启动配置指南

## 为什么需要这个配置

Shizuku 默认在手机重启后失效，需要连接电脑重新激活。通过以下配置，可以实现重启后自动恢复。

## 前提条件

1. 已安装 **thedjchi 版 Shizuku**（不是官方版）
   - 下载地址：https://github.com/thedjchi/Shizuku/releases
2. 手机已通过无线调试完成首次配对

## 配置步骤（仅需一次）

### 第一步：安装 thedjchi 版 Shizuku

- 从 GitHub Releases 下载最新版 APK
- 安装后，按照正常流程通过无线调试完成配对
- 注意：如果之前装过官方版 Shizuku，请先卸载

### 第二步：开启 Start on Boot

1. 打开 Shizuku App
2. 进入「设置」
3. 找到「Start on boot (Wireless ADB)」开关，打开它
4. 系统会请求 WRITE_SECURE_SETTINGS 权限，允许它

### 第三步：配置电池优化白名单

1. 进入手机「设置」→「电池」→「后台耗电管理」
2. 找到 Shizuku，设置为「允许后台高耗电」
3. 找到 Nexus Agent，同样设置为「允许后台高耗电」

### 第四步：配置自启动

1. 进入手机「设置」→「应用管理」→「自启动管理」
2. 找到 Shizuku，允许自启动
3. 找到 Nexus Agent，允许自启动

### 第五步：验证

1. 重启手机
2. 打开 Nexus Agent
3. 观察 Shizuku 状态是否显示「已连接」
4. 如果显示未连接，等待 10 秒后点击「重连」

## 常见问题

### Q: 重启后 Shizuku 显示未连接

A: 等待 10-30 秒，Shizuku 会自动唤醒。如果仍未连接，检查无线调试是否开启。

### Q: 无线调试在重启后关闭了

A: 部分 ROM 会在重启后关闭无线调试。需要在开发者选项中开启「无线调试保持开启」（如果支持）。

### Q: 仍然需要连接电脑

A: 确认使用的是 thedjchi 版 Shizuku，官方版不支持非 Root 设备开机自启。
