# AgentControl Fabric Agent 指南

这个仓库包含 AgentControl 的 Fabric 客户端模组。

## 职责

- 在用户的 Minecraft Fabric 客户端内运行。
- 仅绑定 `127.0.0.1` 本地端点。
- 通过 `/state` 暴露客户端状态。
- 通过 `/action` 使用正常 Minecraft 客户端 API 执行动作。
- 提供可选 Mod Menu 配置界面。

## 相关仓库

- `AgentControl-MCP` 调用本模组的本地端点，并向 AI 客户端暴露工具。
- `AgentControl-Docs` 记录安装、架构和安全边界。
- `AgentControl` 总仓包含本项目以及 MCP 和 Docs 项目。

## 安全规则

- 不要添加服务端权限绕过。
- 不要添加隐藏的自动循环。
- 不要添加 RCON、命令方块或机器人账号控制路径。
- 除非用户明确要求并经过审查，不要让端点监听非本地地址。

#### 准星对准机制（新增 v0.1.1）
- `look_at`：传入世界坐标，自动计算 yaw/pitch 对准目标
- `look_facing`：传入方向名（north/south/east/west/up/down），面朝指定方位

### 已修复的问题（v0.1.1）

1. **ESC 菜单关闭**：`close_screen` 现在先调用 `screen.close()` 再 `setScreen(null)`，能正确关闭 ESC 菜单（`net.minecraft.class_433`）。
2. **GitHub Actions**：修复 YAML 语法错误，使用纯 `echo` 生成 release notes；移除硬编码 Gradle 版本，让 `setup-gradle` 自动检测项目所需版本。

### 已知问题与待办

1. **GitHub Actions 缓存**：GitHub 缓存服务偶尔返回 400 错误，不影响构建，但会拖慢后续构建速度。
2. **Node.js 20 弃用**：`actions/checkout@v4` 等 Action 基于 Node.js 20，GitHub 将在 2026-09-16 后移除支持。需要关注 Action 更新版本。
3. **移动精度**：当前移动通过 `KeyBinding.setPressed(true)` 实现，在后台线程延迟释放。移动距离取决于帧率，不够精确。如需精确移动，可能需要考虑其他方案（如使用 Minecraft 客户端的 `ClientPlayerEntity` 直接修改坐标，但这可能触发反作弊）。

## 验证

```sh
gradle build
```

发布前使用：

```sh
gradle clean build
```
