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

## 验证

```sh
gradle build
```

发布前使用：

```sh
gradle clean build
```
