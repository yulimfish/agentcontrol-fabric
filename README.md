# AgentControl Fabric

AgentControl Fabric 是 AgentControl 的 Minecraft 客户端侧部分。它运行在用户自己的 Fabric Minecraft 客户端中，并通过 `127.0.0.1` 暴露本地状态和动作接口。

它主要配合 `agentcontrol-mcp` 使用，也可以通过本地 HTTP 请求直接检查。

## 与其他仓库的关系

- `AgentControl`：总仓，协调 Fabric、MCP 和文档项目。
- `AgentControl-Fabric`：本项目。
- `AgentControl-MCP`：消费本模组的本地端点，并将能力暴露为 AI/MCP 工具。
- `AgentControl-Docs`：记录安装、架构和安全边界。

正常链路：

```text
agentcontrol-mcp -> http://127.0.0.1:17777 -> agentcontrol-fabric -> Minecraft 客户端 API
```

## 已实现能力

- 本地 `/state` 端点。
- 本地 `/action` 端点。
- 状态读取：游戏状态、当前界面、维度、坐标、朝向、血量、饥饿值、物品栏摘要、准星目标、附近方块和附近实体。
- 动作执行：移动、视角调整、攻击、使用物品、破坏准星方块、对准星方块放置/使用、关闭界面、释放鼠标。
- 可选 Mod Menu 集成。
- Mod Menu 设置项的英文和简体中文本地化。

## 端点

状态端点：

```text
GET http://127.0.0.1:17777/state
```

动作端点：

```text
GET http://127.0.0.1:17777/action?type=move&direction=forward&durationMs=1000
```

支持的动作：

- `type=move&direction=forward&durationMs=1000`
- `type=look&yaw=0&pitch=0`
- `type=attack`
- `type=use`
- `type=break_crosshair`
- `type=place_crosshair`
- `type=close_screen`
- `type=release_mouse`

## Mod Menu 设置

如果安装了 Mod Menu，AgentControl Fabric 会提供设置项：

```text
Capture mouse on release action
```

关闭时，`release_mouse` 会打开透明不暂停界面，让系统鼠标保持释放状态。

开启时，`release_mouse` 会关闭透明界面，让原版 Minecraft 重新捕获鼠标。

配置文件：

```text
config/agentcontrol.properties
```

## 构建

当前目标版本是 Minecraft `1.21.1`。

```sh
gradle clean build
```

用户安装用 jar：

```text
build/libs/agentcontrol-fabric-0.1.0.jar
```

不要把 `*-sources.jar` 安装进 Minecraft。

## 安全边界

AgentControl Fabric 只通过正常 Minecraft 客户端 API 控制本地客户端。它不会绕过服务器权限、反作弊、区域保护、冷却时间或游戏规则。
