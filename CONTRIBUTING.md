# 贡献指南（CONTRIBUTING）

感谢你对宏月 LunaMacro 的关注！欢迎通过 Issue、Pull Request 等方式参与贡献。

---

## 提交前必读

### 🚫 千万不要提交这些东西

| 禁止提交 | 原因 |
|---|---|
| `app/src/main/assets/models/` | 本地视觉模型（1.3GB），体积过大 |
| `app/src/main/assets/sensevoice/` | SenseVoice 模型（229MB） |
| `*.keystore` / `*.jks` / `keystore.properties` | 签名密钥与密码 |
| `local.properties` | 本机 SDK 路径 |
| API Key / 手机号 / 密码 | 敏感信息 |
| 构建产物（`build/`、`*.apk`、`*.aab`） | 已 gitignore |

> 请确保 `.gitignore` 生效。提交前用 `git status` 检查，确认没有误加上述文件。

### ✅ 提交前自查

1. 代码能编译通过（`./gradlew assembleDebug`）
2. 没有硬编码的敏感信息
3. 遵循现有代码风格（Kotlin 官方规范）
4. Commit message 清晰（见下方规范）

---

## 如何提交代码

1. **Fork** 本仓库
2. 创建你的特性分支：`git checkout -b feature/你的特性`
3. 提交改动：`git commit -m "feat: 描述你的改动"`
4. 推送分支：`git push origin feature/你的特性`
5. 发起 **Pull Request**

---

## Commit Message 规范

采用 Conventional Commits 风格：

```
<类型>: <简短描述>

类型：
- feat    新功能
- fix     修复 Bug
- docs    文档
- style   代码风格（不影响逻辑）
- refactor 重构
- chore   杂项（构建/依赖等）
```

示例：

```
feat: 支持自定义唤醒词
fix: 修复开启语音助手崩溃
docs: 更新编译指南
```

---

## 代码规范

- **语言**：Kotlin，遵循 [官方编码规范](https://kotlinlang.org/docs/coding-conventions.html)
- **架构**：保持现有分层（agent / game / speech / ui / macro / overlay …）
- **UI**：使用项目定制的 `LunaComponents.kt` 组件（LunaButton / LunaCard 等），不要直接引入新的 Material 默认样式
- **线程**：耗时操作放 `Dispatchers.IO`，UI 更新切回 `Dispatchers.Main`
- **错误处理**：外部依赖（模型加载、网络）必须 try-catch，失败要优雅降级

---

## 提 Issue 规范

- 🐛 **Bug**：附上手机型号、系统版本、复现步骤、日志（`adb logcat`）
- 💡 **功能建议**：说明使用场景与期望行为
- ❓ **问题**：先搜索是否已有同类 Issue

---

## 行为准则

请保持友善、专业。尊重每一位贡献者，就事论事地讨论技术问题。
