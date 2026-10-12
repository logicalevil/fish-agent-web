# fish-agent-web

[Fish-Agent](../Fish-Agent) SDK 的 Spring Boot 测试程序：以**外部依赖**的方式消费 `com.zheng:fish-agent`，用来验证 SDK 在真实 Web 环境下的表现。

仓库地址：https://github.com/logicalevil/fish-agent-web

## 关于 Fish-Agent

Fish-Agent 是一个轻量级 Java Agent 框架，基于 ReAct 模式实现工具调用、多轮记忆与流式输出，运行时仅依赖 Jackson。

- 仓库：https://github.com/logicalevil/Fish-Agent
- 本程序依赖的版本：`0.1.0`

## 这个项目验证什么

| 能力 | 验证方式 |
|---|---|
| **工具调用** | 注解式（`@AgentTool`）与 Lambda 式（`FunctionTool`）两种注册方式 |
| **多轮记忆** | 同一会话连续提问，观察历史是否参与后续推理 |
| **流式输出** | 页面上显示实际接收到的分片数量 |
| **事件回调** | 可展开的过程事件时间线（start → thought → toolCall → toolResult → finish） |
| **两种模式** | 原生 Tool Calling 与文本 ReAct 可切换对比 |
| **会话隔离** | 每个会话独占一个 Agent 实例，由 `AgentSessionManager` 管理 |

## 前置条件

### 1. 安装 Fish-Agent SDK 到本地仓库

SDK **没有发布到公共仓库**，必须先安装：

```bash
cd ../Fish-Agent
mvn clean install -DskipTests -Dmaven.javadoc.skip=true
```

> `-Dmaven.javadoc.skip=true` 可以跳过 javadoc 打包。如果你的环境设置了 `JAVA_TOOL_OPTIONS=-Dfile.encoding=GBK`，不加这个参数可能因编码问题失败。

### 2. 启动 Ollama

```bash
ollama serve
ollama pull qwen2.5:7b
```

模型需要**支持 tool calling**。可选：`qwen2.5:7b`、`qwen3:8b`、`llama3.1:8b`。

## 运行

```bash
mvn spring-boot:run
```

然后打开 **http://localhost:8084**

## 配置

`src/main/resources/application.yml`：

```yaml
server:
  port: 8084

fish-agent:
  base-url: http://localhost:11434
  model: qwen2.5:7b
  mode: native          # native（原生 Tool Calling）/ react（文本 ReAct）
  streaming: true
  max-iterations: 8
  session-window-size: 20
```

改动 `mode` 可以在两种工具调用模式间切换，便于对比同一问题下的行为差异。

## 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/chat` | 发送消息，返回答案、过程事件、token 用量 |
| GET | `/api/sessions/{id}/messages` | 查看指定会话的记忆消息 |
| POST | `/api/sessions/{id}/reset` | 重置记忆（保留 Agent 实例） |
| DELETE | `/api/sessions/{id}` | 销毁会话（释放 Agent 与记忆） |
| GET | `/api/tools` | 已注册的工具与其自动生成的 Schema |
| GET | `/api/status` | 运行时状态：模式、工具数、活跃会话数 |

示例：

```bash
curl -X POST http://localhost:8084/api/chat \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"demo-1","message":"现在几点了？"}'
```

## 项目结构

```
src/main/java/com/zheng/fishagent/web/
├── FishAgentWebApplication.java    入口
├── api/ChatController.java         对话与运维接口
├── config/AgentConfiguration.java  装配：工具 → Factory → Store → SessionManager
├── agent/
│   ├── AgentFactory.java           每会话构建独立 Agent
│   ├── AgentSessionManager.java    会话管理 + 每会话一把锁
│   ├── SessionStore.java           会话存储扩展点
│   └── InMemorySessionStore.java   默认内存实现
└── tool/
    ├── DemoTools.java              注解式工具（@AgentTool）
    └── Calculator.java             本地表达式求值器

src/main/resources/
├── application.yml                 配置
└── static/index.html               演示页面（单文件，零外部依赖）
```

## 一个重要的设计点

`ReActAgent` 是**有状态**的（记忆绑定在实例上），**不是线程安全的**。Web 场景下 Tomcat 每个请求一个线程，因此：

- **不能**把 `Agent` 配成共享单例
- 本程序的做法是 `AgentFactory` 为每个会话创建独立实例，`AgentSessionManager` 用**每会话一把锁**串行化同一会话的请求

这也是这个测试程序存在的主要价值之一——验证 SDK 的「非线程安全」取舍在 Web 环境下如何被正确使用。

## 许可

MIT
